"""A separate collection using the existing embedding, Qdrant client, and VectorStore adapter."""
import asyncio
import uuid
import hashlib
from langchain_core.documents import Document
from qdrant_client import models
from ..rag.vector_store import VectorStore


class UserMemoryIndex:
    def __init__(self, base):
        self.version = base.version
        self.store = VectorStore(base.url, base.key, base.version, base.embedding, base.schema_version, client=base.client)
        self.store.collection = 'aiops_user_memory_s'+base.schema_version+'_'+hashlib.sha256(base.version.encode()).hexdigest()[:24]

    def scope(self, user, memory=None, version=None, all_embeddings=False):
        if type(user) is not int or user <= 0:
            raise ValueError('INVALID_MEMORY_USER')
        conditions = [models.FieldCondition(key='metadata.user_id', match=models.MatchValue(value=user)),
                      models.FieldCondition(key='metadata.kind', match=models.MatchValue(value='user_memory'))]
        if not all_embeddings:
            conditions.append(models.FieldCondition(key='metadata.embedding_version', match=models.MatchValue(value=self.version)))
        if memory:
            conditions.append(models.FieldCondition(key='metadata.memory_id', match=models.MatchValue(value=memory)))
        if version is not None:
            conditions.append(models.FieldCondition(key='metadata.version', range=models.Range(lte=version)))
        return models.Filter(must=conditions)

    def point_id(self, user, memory, version):
        return str(uuid.uuid5(uuid.NAMESPACE_URL, f'user-memory:{user}:{memory}:{version}:{self.version}'))

    async def delete(self, user, memory, through_version):
        # No embedding/model load is required to delete an existing collection.
        def purge():
            # Embedding upgrades must not leave a deleted user's older vectors behind.
            collections=self.store.client.get_collections().collections
            for collection in collections:
                if collection.name.startswith('aiops_user_memory_s'):
                    self.store.client.delete(collection.name, points_selector=models.FilterSelector(filter=self.scope(user, memory, through_version,all_embeddings=True)), wait=True)
        await asyncio.to_thread(purge)

    async def upsert(self, user, memory):
        store = await asyncio.to_thread(self.store._store)
        document = Document(page_content=memory['content'], metadata={
            'kind':'user_memory', 'user_id':user, 'memory_id':memory['id'],
            'version':int(memory['version']), 'category':memory['category'], 'embedding_version':self.version,
        })
        await store.aadd_documents(documents=[document], ids=[self.point_id(user,memory['id'],memory['version'])])
        # Out-of-order old jobs can never delete a newer version.
        await self.delete(user, memory['id'], int(memory['version'])-1)

    async def search(self, user, query, top_k=5, threshold=.72):
        store = await asyncio.to_thread(self.store._store)
        hits = await store.asimilarity_search_with_score(query=query[:2000], k=top_k, filter=self.scope(user))
        result=[]
        for document, score in hits:
            meta=document.metadata
            if meta.get('user_id') != user or meta.get('kind') != 'user_memory' or score < threshold:
                continue
            result.append({'id':meta['memory_id'], 'version':int(meta['version']), 'score':float(score),
                           'category':meta.get('category'), 'content':document.page_content})
        return result

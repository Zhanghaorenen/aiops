"""LangChain dense vector store with versioned nested metadata payloads."""
import asyncio
import hashlib
from threading import RLock
import uuid
from langchain_core.documents import Document
from langchain_qdrant import QdrantVectorStore
from qdrant_client import QdrantClient, models
from qdrant_client.http.exceptions import UnexpectedResponse


class VectorStore:
    def __init__(self, url, key, version, embedding=None, schema_version='2', client=None):
        self.url, self.key, self.version = url, key, version
        self.embedding, self.schema_version = embedding, schema_version
        self.collection = 'aiops_s' + schema_version + '_' + hashlib.sha256(version.encode()).hexdigest()[:24]
        self.legacy_collection = 'aiops_' + hashlib.sha256(version.encode()).hexdigest()[:24]
        self.client = client or QdrantClient(url=url, api_key=key or None, timeout=60, check_compatibility=False)
        self._vectorstore = None
        self._lock = RLock()
        from .hybrid import LexicalIndex, LocalReranker
        self.lexical = LexicalIndex(self.client,self.collection)
        self.reranker = LocalReranker()

    def _store(self):
        with self._lock:
            if self._vectorstore is None:
                if self.embedding is None:
                    raise ValueError('EMBEDDING_NOT_CONFIGURED')
                dimension = len(self.embedding.embed_query('dimension check'))
                if not self.client.collection_exists(self.collection):
                    try:
                        self.client.create_collection(self.collection, vectors_config=models.VectorParams(size=dimension, distance=models.Distance.COSINE))
                    except UnexpectedResponse as exc:
                        if exc.status_code not in (400,409) or not self.client.collection_exists(self.collection): raise
                config = self.client.get_collection(self.collection).config.params.vectors
                if not isinstance(config, models.VectorParams) or config.size != dimension or config.distance != models.Distance.COSINE:
                    raise ValueError('EMBEDDING_DIMENSION_MISMATCH')
                self._vectorstore = QdrantVectorStore(
                    client=self.client, collection_name=self.collection, embedding=self.embedding,
                    content_payload_key='page_content', metadata_payload_key='metadata',
                    validate_collection_config=False,
                )
            return self._vectorstore

    @staticmethod
    def scope(allowed, version):
        if not allowed: raise ValueError('EMPTY_KNOWLEDGE_SCOPE')
        return models.Filter(must=[
            models.FieldCondition(key='metadata.knowledge_base_id', match=models.MatchAny(any=allowed)),
            models.FieldCondition(key='metadata.status', match=models.MatchValue(value='READY')),
            models.FieldCondition(key='metadata.embedding_version', match=models.MatchValue(value=version)),
        ])

    async def upsert_chunks(self, kb_id, doc_id, version, chunks, title):
        store = await asyncio.to_thread(self._store)
        docs, ids = [], []
        for i, chunk in enumerate(chunks):
            chunk_id = f'doc{doc_id}-v{version}-{i:05d}'
            docs.append(Document(page_content=chunk.page_content, metadata={
                **chunk.metadata, 'chunk_id': chunk_id, 'document_id': doc_id,
                'knowledge_base_id': kb_id, 'index_version': version,
                'embedding_version': self.version, 'status': 'READY', 'document_title': title,
            }))
            ids.append(str(uuid.uuid5(uuid.NAMESPACE_URL, chunk_id)))
        await store.aadd_documents(documents=docs, ids=ids, batch_size=64)
        from ..telemetry import enabled
        if enabled('AIOPS_HYBRID_ENABLED'):
            await asyncio.to_thread(self.lexical.put,ids,docs)

    from ..telemetry import traced

    @traced('qdrant.search','retriever')
    async def search(self, query, allowed, top_k, document_versions):
        if not allowed or not document_versions: return []
        store = await asyncio.to_thread(self._store)
        filters = self.scope(allowed, self.version)
        filters.must.append(models.Filter(should=[models.Filter(must=[
            models.FieldCondition(key='metadata.knowledge_base_id',match=models.MatchValue(value=kb)),
            models.FieldCondition(key='metadata.document_id',match=models.MatchValue(value=doc)),
            models.FieldCondition(key='metadata.index_version',match=models.MatchValue(value=index)),
        ]) for kb, doc, index in document_versions]))
        from ..telemetry import enabled, span
        from .hybrid import fuse, log
        hybrid = enabled("AIOPS_HYBRID_ENABLED")
        hits = await store.asimilarity_search_with_score(query=query,k=20 if hybrid else top_k,filter=filters)
        dense = [{**doc.metadata, "content":doc.page_content, "score":score} for doc,score in hits]
        if not hybrid:
            return dense
        try:
            with span("qdrant.bm25","retriever"):
                sparse = await asyncio.to_thread(self.lexical.search,query,filters,20)
        except Exception as exc:
            log.warning("BM25 unavailable: %s",type(exc).__name__)
            return [{**hit,"dense_rank":i,"dense_score":hit["score"],"retrieval_source":["dense"]}
                    for i,hit in enumerate(dense[:top_k],1)]
        candidates = fuse(dense,sparse)[:20]
        if enabled("AIOPS_RERANKER_ENABLED"):
            with span("rag.rerank","retriever"):
                candidates = await asyncio.to_thread(self.reranker.rank,query,candidates)
        return [{**hit,"final_rank":i} for i,hit in enumerate(candidates[:top_k],1)]

    def as_retriever(self, allowed, document_versions, top_k=5):
        # Internal adapter: caller must obtain scope from Java; never bind a user JWT here.
        if not document_versions or not 1 <= top_k <= 10: raise ValueError('INVALID_RETRIEVER_SCOPE')
        filters = self.scope(allowed, self.version)
        filters.must.append(models.Filter(should=[models.Filter(must=[
            models.FieldCondition(key='metadata.knowledge_base_id',match=models.MatchValue(value=kb)),
            models.FieldCondition(key='metadata.document_id',match=models.MatchValue(value=doc)),
            models.FieldCondition(key='metadata.index_version',match=models.MatchValue(value=index)),
        ]) for kb, doc, index in document_versions]))
        return self._store().as_retriever(search_kwargs={'k':top_k, 'filter':filters})

    def _delete(self, kb_id, doc_id):
        # Explicit document deletion cleans both payload layouts, never deletes collections.
        suffix = '_' + hashlib.sha256(self.version.encode()).hexdigest()[:24]
        names = [c.name for c in self.client.get_collections().collections]
        targets = [(name, 'metadata.') for name in names if name.startswith('aiops_s') and name.endswith(suffix)]
        targets.extend((n,'metadata.') for n in names if n.startswith('aiops_s') and n.endswith(suffix+'_bm25_v1'))
        targets.append((self.legacy_collection, ''))
        for collection, prefix in targets:
            if self.client.collection_exists(collection):
                self.client.delete(collection, points_selector=models.FilterSelector(filter=models.Filter(must=[
                    models.FieldCondition(key=prefix+'knowledge_base_id',match=models.MatchValue(value=kb_id)),
                    models.FieldCondition(key=prefix+'document_id',match=models.MatchValue(value=doc_id)),
                ])), wait=True)

    async def delete_document_chunks(self, kb_id, doc_id):
        await asyncio.to_thread(self._delete, kb_id, doc_id)

    def close(self):
        self.client.close()

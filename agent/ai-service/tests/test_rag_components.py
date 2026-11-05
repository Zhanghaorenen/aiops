import math
import os
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch, Mock, AsyncMock
from langchain_core.documents import Document
from langchain_core.embeddings import Embeddings
from qdrant_client import QdrantClient, models
from app.config import Settings
from app.rag.text_splitter import split_documents
from app.rag.embedding_service import EmbeddingService
from app.rag.vector_store import VectorStore
from app.rag.retrieval_service import RetrievalService
from app.rag.resources import RagResources
from types import SimpleNamespace

class SplitterTests(unittest.TestCase):
    def test_chinese_english_offsets_and_metadata(self):
        for text in [('显存不足需要检查 GPU。 '*80), ('gpu memory pressure and cuda errors '*80), 'X'*500]:
            chunks=split_documents([Document(page_content=text,metadata={'page':3,'section':'GPU'})],100,20)
            self.assertGreater(len(chunks),1)
            for c in chunks:
                self.assertEqual(c.metadata['page'],3);self.assertEqual(c.metadata['section'],'GPU')
                start=c.metadata['start_index'];self.assertEqual(text[start:start+len(c.page_content)],c.page_content)
                self.assertLessEqual(len(c.page_content),100)
        chunks=split_documents([Document(page_content='X'*500)],100,20)
        self.assertEqual(chunks[1].metadata['start_index'],80)
    def test_invalid_boundaries_and_chunk_limit(self):
        for size, overlap in [(99,0),(1601,0),(100,100),(100,-1),(True,0)]:
            with self.assertRaises(ValueError):split_documents([],size,overlap)
        self.assertEqual(split_documents([],100,0),[])
        with self.assertRaisesRegex(ValueError,'TOO_MANY_CHUNKS'):
            split_documents([Document(page_content='a')]*10001,100,0)

class EmbeddingTests(unittest.TestCase):
    def test_lazy_offline_options_and_failure(self):
        with tempfile.TemporaryDirectory() as directory, patch('app.rag.embedding_service.HuggingFaceEmbeddings') as factory:
            service=EmbeddingService(directory,'rev1','cpu',8);factory.assert_not_called()
            factory.return_value.embed_documents.return_value=[[1.,0.]]
            factory.return_value.embed_query.return_value=[1.,0.]
            self.assertEqual(service.embed_documents(['gpu']),[[1.,0.]])
            self.assertEqual(service.embed_query('gpu'),[1.,0.]);factory.assert_called_once()
            self.assertEqual(factory.call_args.kwargs['model_kwargs'],{'device':'cpu','local_files_only':True,'trust_remote_code':False})
            self.assertEqual(factory.call_args.kwargs['encode_kwargs'],{'normalize_embeddings':True,'batch_size':8})
        with self.assertRaisesRegex(ValueError,'NOT_CONFIGURED'):EmbeddingService('','').embed_query('gpu')
        with self.assertRaisesRegex(ValueError,'DIRECTORY_NOT_FOUND'):EmbeddingService('nonexistent-model-path','rev1').embed_query('gpu')
        with tempfile.TemporaryDirectory() as directory, patch('app.rag.embedding_service.HuggingFaceEmbeddings',side_effect=RuntimeError('model broken')):
            with self.assertRaisesRegex(ValueError,'LOAD_FAILED'):EmbeddingService(directory,'rev1').embed_query('gpu')
    def test_real_offline_huggingface_embeddings(self):
        # Tiny locally generated test artifact: no Hub downloads, not the user's model.
        from sentence_transformers import SentenceTransformer, models as st_models
        with tempfile.TemporaryDirectory() as directory:
            model=SentenceTransformer(modules=[st_models.BoW(['gpu','memory','cuda','oom']),st_models.Normalize()])
            model.save(directory)
            service=EmbeddingService(directory,'test-bow-v1')
            vectors=service.embed_documents(['gpu memory','cuda oom'])+[service.embed_query('gpu')]
            for vector in vectors:
                self.assertEqual(len(vector),4);self.assertAlmostEqual(math.sqrt(sum(v*v for v in vector)),1,places=5)
    def test_configuration_bounds(self):
        for opts in [{'embedding_device':'bad'},{'embedding_batch_size':0},{'rag_vector_schema_version':'1'},{'rag_chunk_overlap':1000}]:
            with self.assertRaises(ValueError):Settings('http://localhost:8080',**opts)

class TestEmbeddings(Embeddings):
    def embed_documents(self,texts):return [self.embed_query(text) for text in texts]
    def embed_query(self,text):return [1.,0.,0.] if 'gpu' in text.lower() else [0.,1.,0.]

class VectorTests(unittest.IsolatedAsyncioTestCase):
    def client(self):return QdrantClient(':memory:')
    async def test_write_query_versions_idempotence_and_delete(self):
        client=self.client();store=VectorStore('http://localhost:6333','','rev1',TestEmbeddings(),client=client)
        try:
            chunks=[Document(page_content='gpu memory pressure',metadata={'page':4,'section':'显存'})]
            await store.upsert_chunks(1,2,2,chunks,'manual.pdf')
            await store.upsert_chunks(1,2,2,chunks,'manual.pdf')
            self.assertEqual(client.count(store.collection).count,1)
            await store.upsert_chunks(9,3,1,chunks,'private.pdf')
            hits=await store.search('gpu',[1],5,[(1,2,2)])
            self.assertEqual(len(hits),1);self.assertEqual(hits[0]['page'],4);self.assertEqual(hits[0]['index_version'],2)
            self.assertEqual(await store.search('gpu',[1],5,[(1,2,1)]),[])
            docs=await store.as_retriever([1],[(1,2,2)]).ainvoke('gpu');self.assertEqual(docs[0].metadata['document_id'],2)
            payload=client.scroll(store.collection,limit=1)[0][0].payload
            self.assertIn('metadata',payload);self.assertIn('page_content',payload)
            self.assertNotIn('knowledge_base_id',payload)
            # Legacy collection remains present even after explicit document cleanup.
            client.create_collection(store.legacy_collection,vectors_config=models.VectorParams(size=3,distance=models.Distance.COSINE))
            await store.delete_document_chunks(1,2);await store.delete_document_chunks(1,2)
            self.assertTrue(client.collection_exists(store.legacy_collection));self.assertEqual(await store.search('gpu',[1],5,[(1,2,2)]),[])
        finally:store.close()
    async def test_embedding_schema_isolation_and_dimension_mismatch(self):
        client=self.client();a=VectorStore('http://localhost:6333','','rev1',TestEmbeddings(),client=client)
        b=VectorStore('http://localhost:6333','','rev2',TestEmbeddings(),client=client)
        c=VectorStore('http://localhost:6333','','rev1',TestEmbeddings(),schema_version='3',client=client)
        self.assertEqual(len({a.collection,b.collection,c.collection}),3)
        try:
            client.create_collection(a.collection,vectors_config=models.VectorParams(size=9,distance=models.Distance.COSINE))
            with self.assertRaisesRegex(ValueError,'DIMENSION_MISMATCH'):a._store()
        finally:client.close()
    async def test_current_java_version_two_and_source_preserved(self):
        from test_rag import RagTests
        platform=RagTests().platform();platform.list_knowledge_documents.return_value[0]['indexVersion']=2
        platform.get_knowledge_document.return_value['indexVersion']=2
        hit={**RagTests().hit(),'index_version':2,'page':5,'section':'GPU'}
        store=SimpleNamespace(search=AsyncMock(return_value=[hit]));embedding=SimpleNamespace(version='test-v1')
        results=await RetrievalService(platform,embedding,store).retrieve('gpu',[1])
        self.assertEqual(results['results'][0]['page'],5)
        self.assertEqual(store.search.await_args.args[-1],[(1,2,2)])
        platform.list_knowledge_bases.return_value[0]['status']='DELETING'
        with self.assertRaises(Exception):await RetrievalService(platform,embedding,store).retrieve('gpu',[1])
    async def test_resource_reuse_and_shutdown(self):
        settings=Settings('http://localhost:8080');manager=RagResources()
        a=manager.get(settings);self.assertIs(a,manager.get(settings));self.assertIsNone(a[0]._model)
        with patch.object(a[1],'close') as close:
            await manager.close();close.assert_called_once()

@unittest.skipUnless(os.getenv('AIOPS_TEST_QDRANT_URL'),'Real Qdrant server not configured: set AIOPS_TEST_QDRANT_URL')
class RealQdrantTests(unittest.IsolatedAsyncioTestCase):
    async def test_server_roundtrip_isolation_delete_and_legacy_preservation(self):
        client=QdrantClient(url=os.environ['AIOPS_TEST_QDRANT_URL'],api_key=os.getenv('AIOPS_TEST_QDRANT_KEY'),timeout=15)
        version='integration-'+uuid.uuid4().hex
        store=VectorStore('http://localhost:6333','',version,TestEmbeddings(),client=client)
        try:
            chunks=[Document(page_content='gpu memory',metadata={'page':2})]
            await store.upsert_chunks(1,2,2,chunks,'gpu.pdf')
            await store.upsert_chunks(1,2,2,chunks,'gpu.pdf')
            self.assertEqual(client.count(store.collection).count,1)
            self.assertEqual(len(await store.search('gpu',[1],5,[(1,2,2)])),1)
            self.assertEqual(await store.search('gpu',[9],5,[(1,2,2)]),[])
            self.assertEqual(await store.search('gpu',[1],5,[(1,2,1)]),[])
            client.create_collection(store.legacy_collection,vectors_config=models.VectorParams(size=3,distance=models.Distance.COSINE))
            await store.delete_document_chunks(1,2)
            self.assertTrue(client.collection_exists(store.legacy_collection))
            self.assertEqual(await store.search('gpu',[1],5,[(1,2,2)]),[])
        finally:
            # Only the two collections created with this test's random version.
            for name in (store.collection,store.legacy_collection):
                if client.collection_exists(name):client.delete_collection(name)
            client.close()

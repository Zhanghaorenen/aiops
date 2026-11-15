import json
import unittest
import uuid
import os
from types import SimpleNamespace as NS
from unittest.mock import AsyncMock
from pydantic import ValidationError
from langchain_core.embeddings import Embeddings
from qdrant_client import QdrantClient
from app.memory.schemas import MemoryExtraction
from app.memory.extraction import extract
from app.memory.index import UserMemoryIndex
from app.memory.recall import UserMemoryRecall, needs_memory
from app.rag.vector_store import VectorStore
from app.config import Settings


class TestEmbedding(Embeddings):
    """Deterministic test embedding only; never used in application configuration."""
    def embed_query(self,text): return [1.,0.,0.] if 'GPU 0' in text else [0.,1.,0.] if 'GPU 1' in text else [0.,0.,1.]
    def embed_documents(self,texts): return [self.embed_query(t) for t in texts]


class ExtractionTests(unittest.IsolatedAsyncioTestCase):
    def payload(self):
        return {'previous_summary':'','existing_memories':[], 'messages':[
            {'id':1,'role':'user','content':'我偏好 GPU 0，习惯保留安全显存余量'},
            {'id':2,'role':'assistant','content':'建议改用 GPU 1'}]}
    def candidate(self,**fields):
        result={'category':'USER_PREFERENCE','key':'gpu_preference','content':'用户偏好 GPU 0',
                'source_kind':'USER_STATED','source_message_id':1,'evidence_quote':'我偏好 GPU 0',
                'confidence':.95,'subject':'SELF'}
        result.update(fields);return result
    async def run_extraction(self,memories,payload=None):
        llm=NS(chat=AsyncMock(return_value=NS(content=json.dumps({'summary':'用户说明了习惯。','memories':memories},ensure_ascii=False))))
        result=await extract(llm,'task-test',payload or self.payload())
        return result,llm
    async def test_explicit_user_preference_is_structured_and_idempotency_header_is_requested(self):
        result,llm=await self.run_extraction([self.candidate()]);self.assertEqual(len(result.memories),1)
        self.assertEqual(llm.chat.await_args.kwargs['idempotency_key'],'chat-memory:task-test')
        self.assertTrue(llm.chat.await_args.kwargs['json_mode'])
    async def test_assistant_suggestions_and_user_questions_are_not_preferences(self):
        result,_=await self.run_extraction([self.candidate(source_message_id=2,evidence_quote='建议改用 GPU 1')]);self.assertEqual(result.memories,[])
        result,_=await self.run_extraction([self.candidate(evidence_quote='你建议用哪张卡')]);self.assertEqual(result.memories,[])
    async def test_secrets_logs_third_party_and_live_metrics_are_filtered_before_model(self):
        payload=self.payload();payload['messages'][0]['content']='password=unit-only-private-value'
        result,llm=await self.run_extraction([self.candidate(evidence_quote='password=unit-only-private-value')],payload)
        self.assertEqual(result.memories,[]);self.assertNotIn('unit-only-private-value',str(llm.chat.await_args.args))
        result,_=await self.run_extraction([self.candidate(content='当前 GPU 利用率 99%')]);self.assertEqual(result.memories,[])
        result,_=await self.run_extraction([self.candidate(content='服务器 CPU 占用 56%')]);self.assertEqual(result.memories,[])
        result,_=await self.run_extraction([self.candidate(content='我同事偏好 GPU 0')]);self.assertEqual(result.memories,[])
    async def test_conflict_reuses_owned_topic_and_expected_version(self):
        payload=self.payload();mid=str(uuid.uuid4())
        payload['existing_memories']=[{'id':mid,'category':'USER_PREFERENCE','version':2,'semantic_key':'preferred_gpu','content':'用户偏好 GPU 1'}]
        result,_=await self.run_extraction([self.candidate(replaces_memory_id=mid,expected_version=2)],payload)
        self.assertEqual(result.memories[0].key,'preferred_gpu')
        result,_=await self.run_extraction([self.candidate(replaces_memory_id=mid,expected_version=1)],payload)
        self.assertEqual(result.memories,[])
    async def test_batch_dedup_limits_and_invalid_model_categories(self):
        result,_=await self.run_extraction([self.candidate(),self.candidate()]);self.assertEqual(len(result.memories),1)
        with self.assertRaises(ValidationError): await self.run_extraction([self.candidate()]*6)
        with self.assertRaises(ValidationError): await self.run_extraction([self.candidate(source_kind='MODEL_INFERRED')])
        with self.assertRaises(ValueError): await self.run_extraction([],{'messages':[],'previous_summary':'x'*35000})
    async def test_experience_requires_explicit_user_confirmation(self):
        result,_=await self.run_extraction([self.candidate(category='CONFIRMED_EXPERIENCE',source_kind='USER_CONFIRMED')]);self.assertEqual(result.memories,[])
    async def test_unchanged_topic_keeps_latest_source_watermark_and_last_statement_wins(self):
        payload=self.payload();payload['messages'].append({'id':3,'role':'user','content':'我仍偏好 GPU 0'})
        payload['existing_memories']=[{'id':str(uuid.uuid4()),'category':'USER_PREFERENCE','version':1,'semantic_key':'gpu_preference','content':'用户偏好 GPU 0'}]
        result,_=await self.run_extraction([self.candidate(),self.candidate(source_message_id=3,evidence_quote='我仍偏好 GPU 0')],payload)
        self.assertEqual(len(result.memories),1);self.assertEqual(result.memories[0].source_message_id,3)


class VectorRecallTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.remote=os.getenv('AIOPS_TEST_QDRANT_URL')
        self.client=QdrantClient(url=self.remote,api_key=os.getenv('AIOPS_TEST_QDRANT_KEY'),check_compatibility=False) if self.remote else QdrantClient(':memory:')
        self.base=VectorStore('http://unused','', 'memory-test-'+uuid.uuid4().hex, TestEmbedding(),client=self.client)
        self.index=UserMemoryIndex(self.base)
        self.a={'id':str(uuid.uuid4()),'version':1,'category':'USER_PREFERENCE','content':'用户偏好 GPU 0','status':'ACTIVE'}
        self.b={'id':str(uuid.uuid4()),'version':1,'category':'USER_PREFERENCE','content':'另一用户偏好 GPU 0','status':'ACTIVE'}
        await self.index.upsert(11,self.a);await self.index.upsert(22,self.b)
        self.platform=NS(get_current_user=AsyncMock(return_value={'id':11}),verify_user_memories=AsyncMock(return_value=[self.a]))
        self.resources=NS(memory=lambda _:self.index)
        self.settings=Settings('http://platform',memory_recall_timeout=5)
    async def asyncTearDown(self):
        if self.remote and self.client.collection_exists(self.index.store.collection):
            self.client.delete_collection(self.index.store.collection)
        self.client.close()
    async def test_separate_collection_and_user_filtered_retrieval_in_new_conversation(self):
        self.assertNotEqual(self.index.store.collection,self.base.collection)
        hits=await self.index.search(11,'GPU 0');self.assertEqual([h['id'] for h in hits],[self.a['id']])
        memory=await UserMemoryRecall(self.platform,self.settings,self.resources)('记得我的 GPU 0 偏好吗？')
        self.assertEqual(memory[0]['memoryId'],self.a['id'])
        self.assertEqual(memory[0]['source'],'PRIVATE_USER_MEMORY_UNTRUSTED')
    async def test_mysql_version_validation_blocks_edited_and_deleted_stale_vectors_immediately(self):
        self.platform.verify_user_memories.return_value=[]
        recall=UserMemoryRecall(self.platform,self.settings,self.resources)
        self.assertEqual(await recall('我的 GPU 0 偏好'),[])
        newer={**self.a,'version':2,'content':'用户偏好 GPU 1'}
        await self.index.upsert(11,newer)
        old=await self.index.search(11,'GPU 0',threshold=0)
        self.assertTrue(all(h['version']==2 for h in old))
        await self.index.delete(11,self.a['id'],2)
        self.assertEqual(await self.index.search(11,'GPU 0'),[])
        self.assertEqual(len(await self.index.search(22,'GPU 0')),1)
    async def test_out_of_order_old_delete_never_removes_newer_index(self):
        await self.index.upsert(11,{**self.a,'version':3})
        await self.index.delete(11,self.a['id'],2)
        self.assertEqual((await self.index.search(11,'GPU 0'))[0]['version'],3)
    async def test_deletion_also_cleans_previous_embedding_collection_for_this_user(self):
        old=UserMemoryIndex(VectorStore('http://unused','', 'old-'+uuid.uuid4().hex,TestEmbedding(),client=self.client))
        try:
            await old.upsert(11,self.a)
            await self.index.delete(11,self.a['id'],1)
            self.assertEqual(await old.search(11,'GPU 0'),[])
            self.assertEqual(len(await self.index.search(22,'GPU 0')),1)
        finally:
            if self.remote and self.client.collection_exists(old.store.collection):
                self.client.delete_collection(old.store.collection)
    async def test_embeddings_or_qdrant_failure_safely_return_no_memory(self):
        self.resources.memory=lambda _:(_ for _ in ()).throw(RuntimeError('embedding offline'))
        self.assertEqual(await UserMemoryRecall(self.platform,self.settings,self.resources)('我的习惯'),[])
    async def test_live_only_question_skips_memory_and_unverified_identity_is_not_used(self):
        self.assertFalse(needs_memory('查询服务器当前 GPU 利用率'))
        recall=UserMemoryRecall(self.platform,self.settings,self.resources)
        self.assertEqual(await recall('查询服务器当前 GPU 利用率'),[])
        self.platform.get_current_user.assert_not_awaited()
        self.platform.get_current_user.return_value={'username':'unverified'}
        self.assertEqual(await recall('我的习惯'),[])


class GraphMemoryTests(unittest.IsolatedAsyncioTestCase):
    async def test_personalized_live_question_still_executes_monitoring_tool(self):
        import test_chat
        import httpx
        fixture=test_chat.ChatTests();await fixture.asyncSetUp()
        memory={'id':str(uuid.uuid4()),'version':1,'category':'USER_PREFERENCE','content':'用户偏好 GPU 0'}
        def backend(request):
            if request.url.path=='/api/ai/memories/verify': return httpx.Response(200,json=[memory])
            return fixture.backend(request)
        client=fixture.build([test_chat.completion(calls=[test_chat.call('get_server_resource_usage',{'server_id':41})]),test_chat.completion('已基于新监控回答')],backend=backend)
        index=NS(search=AsyncMock(return_value=[{**memory,'score':.99}]))
        client._transport.app.state.rag_resources.memory=lambda _:index
        async with client:
            response=await client.post('/api/chat',headers={'Authorization':'Bearer valid'},json={'question':'按我的偏好查询服务器41当前资源'})
        self.assertEqual(response.status_code,200)
        self.assertTrue(any(r.url.path=='/api/servers/41/metrics/latest' for r in fixture.backend_requests))
        body=fixture.model_requests[0]
        self.assertIn('UNTRUSTED_PRIVATE_MEMORY_DATA',str(body['messages']))
        self.assertIn('用户偏好 GPU 0',str(body['messages']))

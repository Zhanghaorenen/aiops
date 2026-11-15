import asyncio
import json
import unittest
import uuid
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
from qdrant_client import QdrantClient
from app.agents.session_turn import session_turn
from app.schemas.chat import ChatRequest
from app.rag.incident_memory import IncidentMemorySearch
from app.rag.vector_store import VectorStore
import test_chat as chat_fixtures
from test_rag_components import TestEmbeddings


class PersistentChatTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.harness = chat_fixtures.ChatTests()
        await self.harness.asyncSetUp()
        self.session, self.request = str(uuid.uuid4()), str(uuid.uuid4())
        self.writes = []

    def backend(self, request):
        if request.method == 'POST':
            data = json.loads(request.content)
            self.writes.append((request.url.path, data))
            if request.url.path.endswith('/turns'):
                return httpx.Response(200, json={'history': [{'role': 'user', 'content': '服务端历史'}, {'role': 'assistant', 'content': '已保存回答'}], 'lease': str(uuid.uuid4())})
            return httpx.Response(200)
        return self.harness.backend(request)

    def payload(self):
        return {'question': '继续查询', 'session_id': self.session, 'request_id': self.request}

    async def test_model_calls_deterministic_capacity_tool_and_saves_answer(self):
        def capacity_backend(request):
            if request.url.path.endswith('/capacity'):
                self.assertEqual(request.headers['Authorization'], 'Bearer valid')
                self.assertEqual(request.url.params['vramMb'], '10240.0')
                return httpx.Response(200, json={'allowed': False, 'missingEvidence': ['采样过期'], 'confidence': 'UNAVAILABLE'})
            return self.backend(request)
        replies=[chat_fixtures.completion(calls=[chat_fixtures.call('estimate_available_capacity', {'server_id': 2, 'vram_mb': 10240.0})]),chat_fixtures.completion('采样过期，不能确认是否可运行')]
        async with self.harness.build(replies=replies,backend=capacity_backend) as client:
            response=await client.post('/api/chat',headers={'Authorization':'Bearer valid'},json=self.payload())
        self.assertEqual(response.status_code,200)
        tool_message=[m for m in self.harness.model_requests[-1]['messages'] if m['role']=='tool'][0]
        self.assertFalse(json.loads(tool_message['content'])['data']['allowed'])
        self.assertEqual(self.writes[-1][1]['status'],'COMPLETED')

    async def test_sse_persists_only_after_model_completion_and_uses_server_history(self):
        chunk = {'id': 's', 'object': 'chat.completion.chunk', 'created': 1, 'model': 'unit-model',
                 'choices': [{'index': 0, 'finish_reason': 'stop', 'delta': {'content': '最终回答'}}]}
        async with self.harness.build(backend=self.backend, model_handler=lambda *_: chat_fixtures.streaming_response(chunk)) as client:
            response = await client.post('/api/chat/stream', headers={'Authorization': 'Bearer valid'}, json=self.payload())
        self.assertIn('"type":"done"', response.text)
        self.assertEqual(self.writes[-1][1]['status'], 'COMPLETED')
        self.assertEqual(self.writes[-1][1]['answer'], '最终回答')
        self.assertEqual(self.harness.model_requests[0]['messages'][-3]['content'], '服务端历史')

    async def test_sse_error_marks_interrupted_and_does_not_send_done(self):
        async with self.harness.build(backend=self.backend, model_handler=lambda *_: httpx.Response(400, json={'error': {'message': 'bad'}})) as client:
            response = await client.post('/api/chat/stream', headers={'Authorization': 'Bearer valid'}, json=self.payload())
        self.assertIn('"type":"error"', response.text)
        self.assertNotIn('"type":"done"', response.text)
        self.assertEqual(self.writes[-1][1]['status'], 'INTERRUPTED')
        self.assertNotIn('COMPLETED', [d['status'] for _, d in self.writes if 'status' in d])

    async def test_cross_user_session_denied_before_any_model_request(self):
        def denied(request):
            if request.url.path.endswith('/turns'):
                return httpx.Response(404, json={'detail': 'not found'})
            return self.harness.backend(request)
        async with self.harness.build(backend=denied) as client:
            response = await client.post('/api/chat', headers={'Authorization': 'Bearer valid'}, json=self.payload())
        self.assertEqual(response.status_code, 404)
        self.assertFalse(self.harness.model_requests)

    async def test_replay_does_not_generate_or_save_twice(self):
        def replay(request):
            if request.url.path.endswith('/turns'):
                return httpx.Response(200, json={'history': [], 'replay': '缓存回答'})
            return self.harness.backend(request)
        async with self.harness.build(backend=replay) as client:
            response = await client.post('/api/chat/stream', headers={'Authorization': 'Bearer valid'}, json=self.payload())
        self.assertIn('缓存回答', response.text)
        self.assertIn('"type":"done"', response.text)
        self.assertFalse(self.harness.model_requests)

    async def test_disconnect_records_interrupted_status(self):
        platform = SimpleNamespace(begin_chat_turn=AsyncMock(return_value={'history': [], 'lease': str(uuid.uuid4())}), finish_chat_turn=AsyncMock())
        entered = asyncio.Event()
        async def generate():
            async with session_turn(platform, ChatRequest(**self.payload())):
                entered.set()
                await asyncio.Event().wait()
        task = asyncio.create_task(generate())
        await entered.wait()
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(platform.finish_chat_turn.call_args.args[2], 'INTERRUPTED')

    def test_persistent_mode_rejects_client_history_and_missing_idempotency_id(self):
        from pydantic import ValidationError
        with self.assertRaises(ValidationError):
            ChatRequest(question='q', session_id=self.session)
        with self.assertRaises(ValidationError):
            ChatRequest(**self.payload(), history=[{'role': 'user', 'content': '伪造历史'}])


class IncidentMemoryTests(unittest.IsolatedAsyncioTestCase):
    async def test_qdrant_search_filters_authorized_versions_and_rechecks_mysql(self):
        store = VectorStore('http://unused', '', 'unit', TestEmbeddings(), client=QdrantClient(':memory:'))
        case_id, foreign_id = str(uuid.uuid4()), str(uuid.uuid4())
        def case(identity, owner):
            return {'id': identity, 'user_id': owner, 'server_id': 2, 'title': 'gpu OOM', 'symptoms': 'gpu memory pressure',
                    'resolution': 'reduced batch and verified', 'status': 'VERIFIED', 'scope': 'PRIVATE', 'version': 1, 'evidence_json': '["E1"]'}
        a, b = case(case_id, 1), case(foreign_id, 2)
        platform = SimpleNamespace(get_incident_cases=AsyncMock(return_value=[a, b]), get_incident_case=AsyncMock(side_effect=lambda s, i: a if i == case_id else b))
        search = IncidentMemorySearch(platform, None, SimpleNamespace(get=lambda _: (None, store)))
        try:
            await search.search(2, 'gpu')
            platform.get_incident_cases.return_value = [a]
            result = await search.search(2, 'gpu')
            self.assertEqual([r['case_id'] for r in result['results']], [case_id])
            platform.get_incident_case.return_value = None
            platform.get_incident_case.side_effect = lambda *_: {**a, 'status': 'REJECTED'}
            self.assertEqual((await search.search(2, 'gpu'))['results'], [])
            platform.get_incident_cases.return_value = [{**a, 'status': 'CANDIDATE'}]
            self.assertEqual((await search.search(2, 'gpu'))['results'], [])
        finally:
            store.close()


class IncidentGraphMemoryTests(unittest.IsolatedAsyncioTestCase):
    async def test_graph_keeps_verified_history_separate_from_current_evidence(self):
        import test_v02 as fixtures
        graph, platform, llm=fixtures.IncidentGraphTests().graph(probes=[{"tool":"historical_cases"}])
        historical={'case_id':str(uuid.uuid4()),'version':1,'status':'VERIFIED','title':'已验证故障','resolution':'已验证步骤'}
        graph.experience_memory=SimpleNamespace(search=AsyncMock(return_value={'results':[historical]}))
        report=await graph.run(2,1,'GPU 问题')
        self.assertEqual(report['historical_cases'],[historical])
        model_context=json.loads(llm.chat.call_args.args[0][1]['content'])
        self.assertEqual(model_context['historical_experiences'],[historical])
        self.assertNotIn(historical['case_id'],[e['id'] for e in report['evidence']])

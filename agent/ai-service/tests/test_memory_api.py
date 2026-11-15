import json
import unittest
import uuid
from dataclasses import replace
from unittest.mock import AsyncMock
from types import SimpleNamespace as NS
import httpx
from app.main import create_app
from app.client.platform_client import PlatformClient
from app.config import Settings


class MemoryApiTests(unittest.IsolatedAsyncioTestCase):
    async def test_internal_operations_require_both_service_key_and_authenticated_user(self):
        key='test-service-key-not-a-real-secret-123456'
        settings=Settings('http://platform','http://model','model','test-key',ai_service_key=key,redis_enabled=False)
        requests=[]
        def backend(request):
            requests.append(request)
            if request.headers.get('Authorization')=='Bearer invalid':return httpx.Response(401)
            if request.url.path=='/api/auth/me':return httpx.Response(200,json={'id':11})
            return httpx.Response(409)
        llm=AsyncMock()
        app=create_app(settings,lambda url,token,timeout:PlatformClient(url,token,timeout,transport=httpx.MockTransport(backend)),llm)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://ai') as client:
            payload={'task_id':str(uuid.uuid4()),'lease':str(uuid.uuid4())}
            self.assertEqual((await client.post('/api/internal/chat-memory/extract',json=payload)).status_code,403)
            self.assertEqual((await client.post('/api/internal/chat-memory/extract',json=payload,headers={'X-AI-Service-Key':key})).status_code,401)
            headers={'X-AI-Service-Key':key,'Authorization':'Bearer invalid'}
            self.assertEqual((await client.post('/api/internal/chat-memory/extract',json=payload,headers=headers)).status_code,401)
            headers['Authorization']='Bearer valid'
            response=await client.post('/api/internal/chat-memory/extract',json=payload,headers=headers)
            self.assertEqual(response.status_code,409)
            self.assertEqual((await client.post('/api/internal/chat-memory/extract',json={**payload,'user_id':99},headers=headers)).status_code,422)
            llm.assert_not_called()

    async def test_model_receives_only_java_owned_task_and_valid_output_is_returned(self):
        key='test-service-key-not-a-real-secret-123456';task=str(uuid.uuid4());lease=str(uuid.uuid4())
        settings=Settings('http://platform','http://model','model','test-key',ai_service_key=key,redis_enabled=False)
        payload={'messages':[{'id':1,'role':'user','content':'我习惯使用 PyTorch'}],'previous_summary':'','existing_memories':[]}
        def backend(request):
            if request.url.path=='/api/auth/me':return httpx.Response(200,json={'id':11})
            self.assertEqual(request.headers['X-Memory-Lease'],lease)
            self.assertEqual(request.headers['X-AI-Service-Key'],key)
            return httpx.Response(200,json={'task_id':task,'payload':payload})
        llm=NS(close=AsyncMock(),chat=AsyncMock(return_value=NS(content=json.dumps({'summary':'用户使用 PyTorch。','memories':[{
            'category':'ENVIRONMENT','key':'framework','content':'用户使用 PyTorch','source_kind':'USER_STATED','source_message_id':1,'evidence_quote':'我习惯使用 PyTorch','confidence':.95}]}))))
        app=create_app(settings,lambda url,token,timeout:PlatformClient(url,token,timeout,transport=httpx.MockTransport(backend)),lambda *_:llm)
        app.state.rag_resources=NS(memory=lambda _:(_ for _ in ()).throw(RuntimeError('index unavailable')))
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://ai') as client:
            response=await client.post('/api/internal/chat-memory/extract',json={'task_id':task,'lease':lease},headers={'X-AI-Service-Key':key,'Authorization':'Bearer valid'})
        self.assertEqual(response.status_code,200);self.assertEqual(response.json()['memories'][0]['category'],'ENVIRONMENT')
        self.assertEqual(llm.chat.await_count,1)

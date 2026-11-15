import json
import unittest
from dataclasses import replace
from unittest.mock import patch,AsyncMock
from app.api.chat import build_agent
from app.agents.multi_agent.supervisor import MultiAgentSupervisor
from app.agents.multi_agent.schemas import TaskPlan,AgentTask
from app.config import Settings
import test_chat as fixtures

class ApiTests(unittest.IsolatedAsyncioTestCase):
    async def client(self):
        self.fixture=fixtures.ChatTests()
        await self.fixture.asyncSetUp()
        def model(request,body):
            if body.get("response_format"):
                if '"TaskPlan"' in body["messages"][0]["content"]:
                    data=TaskPlan(intent="monitoring",tasks=[AgentTask(task_id="m",agent="monitoring",
                        objective="查询服务器41资源",server_id=41)]).model_dump()
                else:
                    data={"findings":[]}
                return fixtures.httpx.Response(200,json=fixtures.completion(json.dumps(data)))
            if body.get("tools") and body["messages"][-1]["role"]!="tool":
                return fixtures.httpx.Response(200,json=fixtures.completion(calls=[fixtures.call("get_server_resource_usage",{"server_id":41})]))
            return fixtures.httpx.Response(200,json=fixtures.completion("专业摘要"))
        self.handler=model
        return self.fixture.build(model_handler=model,settings=replace(fixtures.CONFIG,multi_agent_enabled=True))

    async def test_enabled_http_uses_supervisor_and_closes_clients(self):
        async with await self.client() as client:
            response=await client.post("/api/chat",json={"question":"服务器41资源"},headers={"Authorization":"Bearer user-jwt"})
            self.assertEqual(response.status_code,200)
            self.assertIn("answer",response.json())
        self.assertTrue(all(p.client.is_closed for p in self.fixture.platforms))
        self.assertTrue(any(r.url.path=="/api/servers/41/metrics/latest" for r in self.fixture.backend_requests))
        self.assertTrue(all(r.headers["Authorization"]=="Bearer user-jwt" for r in self.fixture.backend_requests))

    async def test_enabled_sse_preserves_legacy_events_and_adds_progress(self):
        async with await self.client() as client:
            response=await client.post("/api/chat/stream",json={"question":"服务器41资源"},headers={"Authorization":"Bearer user-jwt"})
            events=[json.loads(line[6:]) for line in response.text.splitlines() if line.startswith("data: ")]
            kinds=[e["type"] for e in events]
            for kind in ("plan","agent_start","tool_start","tool_end","agent_result","synthesis","token","done"):
                self.assertIn(kind,kinds)
            self.assertNotIn("error",kinds)

    async def test_enabled_identity_denied_before_any_planning(self):
        async with await self.client() as client:
            response=await client.post("/api/chat",json={"question":"GPU"},headers={"Authorization":"Bearer invalid"})
            self.assertEqual(response.status_code,401)
            self.assertEqual(self.fixture.model_requests,[])

    async def test_session_saves_final_answer_and_replay_skips_all_model_calls(self):
        import uuid
        client=await self.client()
        await client.aclose()
        writes=[]
        def backend(request):
            if request.method=="POST":
                body=json.loads(request.content)
                writes.append(body)
                if request.url.path.endswith("/turns"):
                    return fixtures.httpx.Response(200,json={"history":[{"role":"user","content":"服务器41"}],"lease":"test-lease"})
                return fixtures.httpx.Response(200)
            return self.fixture.backend(request)
        payload={"question":"资源状态","session_id":str(uuid.uuid4()),"request_id":str(uuid.uuid4())}
        async with self.fixture.build(backend=backend,model_handler=self.handler,
                settings=replace(fixtures.CONFIG,multi_agent_enabled=True)) as client:
            response=await client.post("/api/chat/stream",json=payload,headers={"Authorization":"Bearer user-jwt"})
        self.assertIn('"type":"done"',response.text)
        self.assertEqual(writes[-1]["status"],"COMPLETED")
        self.assertNotIn("内部分析草稿",writes[-1]["answer"])
        self.assertEqual(json.loads(writes[-1]["metadata"]).keys(),{"sources","resourceResults"})

        self.fixture.model_requests.clear()
        def replay(request):
            if request.url.path.endswith("/turns"):
                return fixtures.httpx.Response(200,json={"history":[],"replay":"已保存最终回答"})
            return self.fixture.backend(request)
        async with self.fixture.build(backend=replay,model_handler=self.handler,
                settings=replace(fixtures.CONFIG,multi_agent_enabled=True)) as client:
            response=await client.post("/api/chat/stream",json=payload,headers={"Authorization":"Bearer user-jwt"})
        self.assertIn("已保存最终回答",response.text)
        self.assertFalse(self.fixture.model_requests)

    async def test_flag_defaults_off_and_factory_keeps_original(self):
        self.assertFalse(Settings.from_env().multi_agent_enabled)
        with patch("app.api.chat.GraphAgent") as old:
            settings=replace(fixtures.CONFIG,multi_agent_enabled=False)
            build_agent(None,None,None,4,settings=settings,platform=None)
            old.assert_called_once()

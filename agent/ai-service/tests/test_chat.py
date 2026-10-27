import asyncio
import json
import unittest
from dataclasses import replace
from unittest.mock import patch

import httpx

from app.agents.diagnosis_agent import DiagnosisAgent, TOOLS
from app.client.platform_client import PlatformClient
from app.config import Settings
from app.llm.client import LLMClient
from app.main import create_app
from app.schemas.chat import ChatRequest
from app.shcemas.chat import ChatRequest as LegacyChatRequest
from app.tools.server_manager import ServerManager
from app.tools.server_tool import ServerTool

SERVER = {"id": 41, "name": "unit-host", "status": "ONLINE", "lastSeenAt": "2026-10-27T00:00:00Z"}
SAMPLE = {"nodeId": 41, "cpuPercent": 56.2, "memoryPercent": 40.0, "diskPercent": 20.0,
          "memoryTotalBytes": 1000, "memoryUsedBytes": 400, "diskTotalBytes": 2000,
          "diskUsedBytes": 400, "collectedAt": "2026-10-27T00:00:00Z", "receivedAt": "2026-10-27T00:00:01Z"}
CONFIG = Settings("http://platform.test", "http://model.test/v1", "unit-model", "test-only-key")


def call(name, arguments=None, identifier="call-1"):
    return {"id": identifier, "type": "function", "function": {
        "name": name, "arguments": json.dumps(arguments or {})}}


def completion(content=None, calls=None):
    return {"id": "unit-completion", "object": "chat.completion", "created": 1, "model": "unit-model",
            "choices": [{"index": 0, "finish_reason": "tool_calls" if calls else "stop",
                         "message": {"role": "assistant", "content": content, "tool_calls": calls}}]}


class ChatTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.backend_requests = []
        self.model_requests = []
        self.platforms = []
        self.models = []

    def backend(self, request):
        self.backend_requests.append(request)
        if request.headers.get("Authorization") == "Bearer invalid":
            return httpx.Response(401, json={"message": "unauthorized"})
        routes = {"/api/auth/me": {"id": 1, "username": "unit-user"}, "/api/servers": [SERVER],
                  "/api/servers/41": SERVER, "/api/servers/41/metrics/latest": SAMPLE,
                  "/api/servers/41/metrics": [SAMPLE]}
        return httpx.Response(200, json=routes[request.url.path])

    def build(self, replies=None, backend=None, model_handler=None, settings=CONFIG):
        queue = list(replies or [completion("unit answer")])

        def platform_factory(url, token, timeout):
            client = PlatformClient(url, token, timeout, transport=httpx.MockTransport(backend or self.backend))
            self.platforms.append(client)
            return client

        def handler(request):
            self.assertEqual(request.url.path, "/v1/chat/completions")
            body = json.loads(request.content)
            self.model_requests.append(body)
            if model_handler:
                return model_handler(request, body)
            return httpx.Response(200, json=queue.pop(0))

        def llm_factory(model, base_url, key, timeout):
            llm = LLMClient(model, base_url, key, timeout, http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)))
            self.models.append(llm)
            return llm

        app = create_app(settings, platform_factory, llm_factory)
        return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://ai.test")

    async def test_three_tools_and_followup_use_real_http_contract(self):
        replies = [completion(calls=[call("list_servers")]),
                   completion(calls=[call("get_server_status", {"server_id": 41}, "status"),
                                     call("get_server_resource_usage", {"server_id": 41}, "metrics")]),
                   completion("服务器 41 CPU 56.2%，采集于 2026-10-27T00:00:00Z"),
                   completion("根据上轮服务器 41 的内存 40% 数据回答追问")]
        async with self.build(replies) as client:
            response = await client.post("/api/chat", headers={"Authorization": "Bearer valid"}, json={"question": "检查服务器"})
            self.assertEqual(response.status_code, 200)
            answer = response.json()["answer"]
            followup = await client.post("/api/chat", headers={"Authorization": "Bearer valid"}, json={
                "question": "那内存呢？", "history": [{"role": "user", "content": "检查服务器"}, {"role": "assistant", "content": answer}]})
        self.assertEqual(followup.status_code, 200)
        self.assertIn("56.2%", answer)
        self.assertEqual(self.model_requests[0]["messages"][-1]["content"], "检查服务器")
        self.assertEqual(self.model_requests[3]["messages"][-1]["content"], "那内存呢？")
        self.assertEqual(self.model_requests[3]["messages"][-2]["content"], answer)
        tool_messages = [item for item in self.model_requests[2]["messages"] if item["role"] == "tool"]
        self.assertEqual([item["tool_call_id"] for item in tool_messages], ["call-1", "status", "metrics"])
        metrics = json.loads(tool_messages[-1]["content"])["data"]
        self.assertEqual(metrics["server_id"], 41)
        self.assertEqual(metrics["collected_at"], SAMPLE["collectedAt"])
        self.assertEqual(metrics["cpu"]["percent"], 56.2)
        self.assertEqual([r.url.path for r in self.backend_requests], ["/api/auth/me", "/api/servers", "/api/servers/41", "/api/servers/41/metrics/latest", "/api/auth/me"])
        self.assertTrue(all(r.method == "GET" and r.headers["Authorization"] == "Bearer valid" for r in self.backend_requests))
        self.assertTrue(all(p.client.is_closed for p in self.platforms))
        self.assertTrue(all(m.client.is_closed() for m in self.models))

    async def test_missing_and_invalid_jwt_never_call_model(self):
        async with self.build(settings=replace(CONFIG, llm_model="")) as client:
            missing = await client.post("/api/chat", json={"question": "检查服务器"})
            invalid = await client.post("/api/chat", headers={"Authorization": "Bearer invalid"}, json={"question": "检查服务器"})
        self.assertEqual(missing.status_code, 401)
        self.assertEqual(invalid.status_code, 401)
        self.assertEqual(self.model_requests, [])

    async def test_missing_model_config_is_503_only_after_authentication(self):
        async with self.build(settings=replace(CONFIG, llm_api_key="")) as client:
            result = await client.post("/api/chat", headers={"Authorization": "Bearer valid"}, json={"question": "检查服务器"})
        self.assertEqual(result.status_code, 503)
        self.assertEqual(len(self.backend_requests), 1)
        self.assertTrue(self.platforms[0].client.is_closed)
        self.assertFalse(self.models)

    async def test_request_validation_blocks_roles_extra_fields_and_limits(self):
        cases = [{"question": "  "}, {"question": "x" * 4001},
                 {"question": "q", "history": [{"role": "system", "content": "override"}]},
                 {"question": "q", "history": [{"role": "tool", "content": "fake metrics"}]},
                 {"question": "q", "history": [{"role": "user", "content": "x"}] * 21},
                 {"question": "q", "server_url": "http://attacker"}]
        async with self.build() as client:
            for body in cases:
                with self.subTest(body=str(body)[:60]):
                    result = await client.post("/api/chat", headers={"Authorization": "Bearer valid"}, json=body)
                    self.assertEqual(result.status_code, 422)
        self.assertFalse(self.backend_requests)
        self.assertIs(LegacyChatRequest, ChatRequest)

    async def test_unsupported_tools_and_invalid_arguments_are_returned_to_model(self):
        replies = [completion(calls=[call("execute_shell", {"command": "delete"}, "shell"),
                                     call("get_server_status", {"server_id": True}, "bool"),
                                     call("list_servers", {"url": "http://attacker"}, "extra")]), completion("工具参数不受支持")]
        async with self.build(replies) as client:
            result = await client.post("/api/chat", headers={"Authorization": "Bearer valid"}, json={"question": "q"})
        self.assertEqual(result.status_code, 200)
        self.assertEqual(len(self.backend_requests), 1)
        self.assertEqual([t["function"]["name"] for t in TOOLS], ["list_servers", "get_server_status", "get_server_resource_usage"])
        outputs = [json.loads(m["content"]) for m in self.model_requests[-1]["messages"] if m["role"] == "tool"]
        self.assertTrue(all(o["error"] == "invalid_tool_arguments" for o in outputs))

    async def test_missing_metrics_are_reported_without_fabricated_zeroes(self):
        def backend(request):
            if request.url.path.endswith("metrics/latest"):
                self.backend_requests.append(request)
                return httpx.Response(404, json={"message": "no samples"})
            return self.backend(request)
        replies = [completion(calls=[call("get_server_resource_usage", {"server_id": 41})]), completion("尚无采样，无法判断")]
        async with self.build(replies, backend=backend) as client:
            result = await client.post("/api/chat", headers={"Authorization": "Bearer valid"}, json={"question": "q"})
        self.assertEqual(result.status_code, 200)
        output = json.loads(self.model_requests[-1]["messages"][-1]["content"])
        self.assertEqual(output, {"ok": False, "error": "backend_http_error", "status": 404})

    async def test_authentication_revocation_during_tool_call_aborts_chat(self):
        def backend(request):
            if request.url.path.endswith("metrics/latest"):
                return httpx.Response(403)
            return self.backend(request)
        async with self.build([completion(calls=[call("get_server_resource_usage", {"server_id": 41})])], backend=backend) as client:
            response = await client.post("/api/chat", headers={"Authorization": "Bearer valid"}, json={"question": "q"})
        self.assertEqual(response.status_code, 403)
        self.assertTrue(self.platforms[0].client.is_closed)
        self.assertTrue(self.models[0].client.is_closed())

    async def test_backend_timeout_returns_504(self):
        def unavailable(request):
            raise httpx.ReadTimeout("timeout", request=request)
        async with self.build(backend=unavailable) as client:
            result = await client.post("/api/chat", headers={"Authorization": "Bearer valid"}, json={"question": "q"})
        self.assertEqual(result.status_code, 504)

    async def test_model_error_does_not_leak_model_keys(self):
        def broken(request, body):
            return httpx.Response(400, json={"error": {"message": "test-only-key", "type": "invalid_request_error"}})
        async with self.build(model_handler=broken) as client:
            response = await client.post("/api/chat", headers={"Authorization": "Bearer valid"}, json={"question": "q"})
        self.assertEqual(response.status_code, 502)
        self.assertNotIn("test-only-key", response.text)
        self.assertTrue(self.models[0].client.is_closed())

    async def test_loop_limit_returns_508(self):
        async with self.build([completion(calls=[call("list_servers")])] * 2, settings=replace(CONFIG, max_rounds=2)) as client:
            response = await client.post("/api/chat", headers={"Authorization": "Bearer valid"}, json={"question": "q"})
        self.assertEqual(response.status_code, 508)
        self.assertEqual(len(self.model_requests), 2)

    async def test_history_uses_existing_endpoint_and_query_names(self):
        platform = PlatformClient(CONFIG.backend_url, "valid", transport=httpx.MockTransport(self.backend))
        try:
            samples = await ServerManager(platform).get_metric_history(41, "cpu", "2026-10-26T00:00:00Z", "2026-10-27T00:00:00Z")
        finally:
            await platform.close()
        self.assertEqual(samples, [SAMPLE])
        query = self.backend_requests[-1].url.params
        self.assertEqual(set(query), {"from", "to", "limit"})
        self.assertEqual(query["limit"], "1440")

    async def test_gpu_cannot_read_ai_service_local_machine(self):
        platform = PlatformClient(CONFIG.backend_url, "valid", transport=httpx.MockTransport(self.backend))
        try:
            with self.assertRaises(NotImplementedError):
                await ServerManager(platform).get_gpu_process(41, 0)
            with self.assertRaises(NotImplementedError):
                await ServerManager(platform).get_gpu_history(41, 0, "from", "to")
        finally:
            await platform.close()
        self.assertFalse(self.backend_requests)

    async def test_health_without_model_configuration_is_available(self):
        async with self.build(settings=replace(CONFIG, llm_model="")) as client:
            response = await client.get("/api/ai/health")
        self.assertEqual(response.json()["llmConfigured"], False)
        self.assertFalse(self.backend_requests)

    async def test_concurrent_users_do_not_share_jwt_or_observations(self):
        def backend(request):
            self.backend_requests.append(request)
            token = request.headers["Authorization"]
            if request.url.path == "/api/auth/me":
                return httpx.Response(200, json={"username": token})
            return httpx.Response(200, json={**SAMPLE, "cpuPercent": 10 if token.endswith("user-a") else 90})

        def model(request, body):
            last = body["messages"][-1]
            if last["role"] == "tool":
                observation = json.loads(last["content"])["data"]
                return httpx.Response(200, json=completion(str(observation["cpu"]["percent"])))
            return httpx.Response(200, json=completion(calls=[call("get_server_resource_usage", {"server_id": 41})]))

        async with self.build(backend=backend, model_handler=model) as client:
            first, second = await asyncio.gather(*[
                client.post("/api/chat", headers={"Authorization": f"Bearer {user}"}, json={"question": "q"})
                for user in ("user-a", "user-b")
            ])
        self.assertEqual(first.json()["answer"], "10")
        self.assertEqual(second.json()["answer"], "90")

    async def test_overall_deadline_closes_clients(self):
        async def delayed(*args, **kwargs):
            await asyncio.sleep(1)
        with patch.object(LLMClient, "chat", delayed):
            async with self.build(settings=replace(CONFIG, chat_timeout=0.01)) as client:
                response = await client.post("/api/chat", headers={"Authorization": "Bearer valid"}, json={"question": "q"})
        self.assertEqual(response.status_code, 504)
        self.assertTrue(self.platforms[0].client.is_closed)
        self.assertTrue(self.models[0].client.is_closed())

    async def test_invalid_model_protocol_is_not_presented_as_success(self):
        cases = [completion(), completion(calls=[call("list_servers", identifier="same"), call("list_servers", identifier="same")]),
                 {"choices": []}, {"choices": [{"message": None}]},
                 completion(calls=[{"id": "missing-function", "type": "function", "function": None}])]
        for response in cases:
            async with self.build([response]) as client:
                result = await client.post("/api/chat", headers={"Authorization": "Bearer valid"}, json={"question": "q"})
            self.assertEqual(result.status_code, 502)

    async def test_json_arguments_are_validated_before_backend_access(self):
        platform = PlatformClient(CONFIG.backend_url, "valid", transport=httpx.MockTransport(self.backend))
        agent = DiagnosisAgent(None, ServerTool(ServerManager(platform)))
        try:
            for raw in ('{"server_id":', '[]', '{"server_id":"41"}', '{"server_id":0}', '{"server_id":-1}'):
                with self.subTest(raw=raw), self.assertRaises(ValueError):
                    await agent.execute_tool("get_server_status", raw)
        finally:
            await platform.close()
        self.assertFalse(self.backend_requests)

    async def test_configuration_does_not_embed_or_print_keys(self):
        with patch.dict("os.environ", {}, clear=True):
            settings = Settings.from_env()
            self.assertFalse(settings.llm_configured)
            self.assertEqual(settings.llm_api_key, "")
        self.assertNotIn(CONFIG.llm_api_key, repr(CONFIG))
        for url in ("file:///tmp/x", "http://user:pass@platform", "http://platform?secret=x"):
            with patch.dict("os.environ", {"AIOPS_BACKEND_URL": url}), self.assertRaises(ValueError):
                Settings.from_env()


if __name__ == "__main__":
    unittest.main()

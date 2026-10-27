"""Explicit integration smoke test. --enroll-local writes one REAL host sample to MySQL.

The model is an in-process HTTP fixture, not a real LLM. Never enable it in app.main.
"""

import argparse
import asyncio
import json
import os
import sys
from dataclasses import replace
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import Settings
from app.llm.client import LLMClient
from app.main import create_app


def completion(content=None, calls=None):
    return {"id": "integration-fixture", "object": "chat.completion", "created": 1, "model": "fixture",
            "choices": [{"index": 0, "finish_reason": "tool_calls" if calls else "stop",
                         "message": {"role": "assistant", "content": content, "tool_calls": calls}}]}


def tool(name, arguments=None):
    return {"id": name, "type": "function", "function": {"name": name, "arguments": json.dumps(arguments or {})}}


async def verify(args):
    token = os.getenv("AIOPS_USER_JWT")
    async with httpx.AsyncClient(base_url=args.backend_url, timeout=15) as platform:
        if not token:
            username, password = os.getenv("AIOPS_VERIFY_USERNAME"), os.getenv("AIOPS_VERIFY_PASSWORD")
            if not username or not password:
                raise RuntimeError("Set AIOPS_USER_JWT or AIOPS_VERIFY_USERNAME/AIOPS_VERIFY_PASSWORD")
            login = await platform.post("/api/auth/login", json={"username": username, "password": password})
            login.raise_for_status()
            token = login.json()["accessToken"]
        headers = {"Authorization": f"Bearer {token}"}
        valid = await platform.get("/api/auth/me", headers=headers)
        valid.raise_for_status()
        for invalid in (None, "invalid-token"):
            response = await platform.get("/api/servers", headers={"Authorization": f"Bearer {invalid}"} if invalid else {})
            assert response.status_code == 401

        if args.enroll_local:
            sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
            from aiops_agent.__main__ import Agent
            from aiops_agent.config import AgentConfig

            state = Path(__file__).resolve().parents[2] / ".aiops-agent" / "stage-a-state.json"
            config = replace(AgentConfig.from_env(), backend_url=args.backend_url, state_file=state)
            agent = Agent(config)
            if not agent.credential:
                issued = await platform.post("/api/agent/enrollment-tokens", headers=headers)
                issued.raise_for_status()
                agent.config = replace(config, enrollment_token=issued.json()["token"])
                await asyncio.to_thread(agent.enroll)
            sample = await asyncio.to_thread(agent.collect)
            response = await platform.post("/api/agent/metrics", json=sample, headers={"Authorization": f"Bearer {agent.credential}"})
            assert response.status_code == 202
            forbidden = await platform.get("/api/servers", headers={"Authorization": f"Bearer {agent.credential}"})
            assert forbidden.status_code == 401

        servers = await platform.get("/api/servers", headers=headers)
        servers.raise_for_status()
        nodes = servers.json()
        if not nodes:
            raise RuntimeError("No real nodes available. Enroll a real Agent first or use --enroll-local")
        node = next((s for s in nodes if args.enroll_local and s.get("hostname") == agent.hostname), nodes[0])
        node_id = node["id"]
        metric = await platform.get(f"/api/servers/{node_id}/metrics/latest", headers=headers)
        metric.raise_for_status()
        assert metric.json()["nodeId"] == node_id
        if args.enroll_local:
            assert metric.json()["cpuPercent"] == sample["cpuPercent"]
            assert metric.json()["memoryUsedBytes"] == sample["memoryUsedBytes"]

    async with httpx.AsyncClient(base_url=args.ai_url, timeout=15) as ai:
        health = await ai.get("/api/ai/health")
        assert health.json()["service"] == "aiops-ai-service"
        invalid = await ai.post("/api/chat", headers={"Authorization": "Bearer invalid-token"}, json={"question": "检查主机"})
        assert invalid.status_code == 401
        if not health.json()["llmConfigured"]:
            missing = await ai.post("/api/chat", headers=headers, json={"question": "检查主机"})
            assert missing.status_code == 503

    requests = []

    def model_fixture(request):
        body = json.loads(request.content)
        requests.append(body)
        last = body["messages"][-1]
        if last["role"] == "user":
            return httpx.Response(200, json=completion(calls=[tool("list_servers")]))
        if last.get("tool_call_id") == "list_servers":
            return httpx.Response(200, json=completion(calls=[tool("get_server_status", {"server_id": node_id}), tool("get_server_resource_usage", {"server_id": node_id})]))
        data = json.loads(last["content"])["data"]
        return httpx.Response(200, json=completion(f"MOCK MODEL: server_id={node_id}, cpu={data['cpu']['percent']}%, collected_at={data['collected_at']}"))

    def llm_factory(model, base_url, key, timeout):
        return LLMClient(model, base_url, key, timeout, http_client=httpx.AsyncClient(transport=httpx.MockTransport(model_fixture)))

    app = create_app(Settings(args.backend_url, "http://fixture.test/v1", "fixture", "fixture-not-a-secret"), llm_factory=llm_factory)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://ai-fixture.test") as ai:
        question = "检查目标服务器"
        first = await ai.post("/api/chat", headers=headers, json={"question": question})
        first.raise_for_status()
        answer = first.json()["answer"]
        second = await ai.post("/api/chat", headers=headers, json={"question": "刚才那台服务器现在呢？", "history": [
            {"role": "user", "content": question}, {"role": "assistant", "content": answer}]})
        second.raise_for_status()
    assert len(requests) == 6
    assert requests[3]["messages"][-1]["content"] == "刚才那台服务器现在呢？"
    print(json.dumps({"user_jwt": "verified", "invalid_jwt": "rejected", "real_server_id": node_id,
                      "real_mysql_sample": True, "model": "MOCK HTTP fixture, NOT a real LLM",
                      "three_tools": "passed", "followup": "passed", "answer": answer}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-url", default=os.getenv("AIOPS_BACKEND_URL", "http://localhost:8080"))
    parser.add_argument("--ai-url", default="http://localhost:8000")
    parser.add_argument("--enroll-local", action="store_true", help="Enroll this physical host and write one actual sample")
    asyncio.run(verify(parser.parse_args()))

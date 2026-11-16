"""Loopback-only endpoint for the Java restart integration test. No external model traffic."""

import os
import asyncio
import json
import sys
from types import SimpleNamespace
import uvicorn
from app.main import create_app
from app.config import Settings


class FixtureLLM:
    model = "fixture-crash-recovery"

    def __init__(self, *_):
        pass

    async def chat(self, *args, **kwargs):
        messages = args[0] if args else kwargs["messages"]
        tools = kwargs.get("tools") or (args[1] if len(args) > 1 else None)
        server = int(os.getenv("FIXTURE_SERVER", "1"))
        if tools:
            names = {t["function"]["name"] for t in tools}
            if messages[-1]["role"] == "tool":
                return SimpleNamespace(content="已取得实际工具结果。", tool_calls=[])
            choices = [
                ("get_gpu_status", {"server_id": server, "gpu_id": 0}),
                ("get_active_workloads", {"server_id": server}),
                ("search_knowledge", {"query": "训练进度"}),
            ]
            name, params = next((n, p) for n, p in choices if n in names)
            return SimpleNamespace(
                content="",
                tool_calls=[
                    {
                        "id": "fixture",
                        "type": "function",
                        "function": {"name": name, "arguments": json.dumps(params)},
                    }
                ],
            )
        if '"TaskPlan"' in messages[0]["content"]:
            plan = {
                "intent": "incident_diagnosis",
                "tasks": [
                    {
                        "task_id": a,
                        "agent": a,
                        "objective": "检查相关只读证据",
                        "server_id": server,
                    }
                    for a in ("monitoring", "workload", "knowledge")
                ],
            }
            return SimpleNamespace(content=json.dumps(plan))
        if '"Synthesis"' in messages[0]["content"]:
            data = json.loads(messages[-1]["content"])
            return SimpleNamespace(
                content=json.dumps(
                    {
                        "findings": [
                            {
                                "finding_type": "recommendation",
                                "statement": "核对同阶段吞吐数据",
                                "supporting_evidence_ids": [
                                    e["evidence_id"] for e in data["evidence"]
                                ],
                            }
                        ]
                    }
                )
            )
        if os.environ.get("ENGINEERING_CRASH") == "yes":
            os._exit(75)  # only this test subprocess, after the evidence superstep
        return SimpleNamespace(
            content='{"possible_causes":[],"missing_evidence":["无同阶段训练进度"],"suggested_steps":[],"probes":[],"sufficient":true}'
        )

    async def close(self):
        pass


if __name__ == "__main__":
    app = create_app(
        Settings(
            backend_url=os.environ["AIOPS_BACKEND_URL"],
            llm_model=FixtureLLM.model,
            llm_api_key="fixture",
            llm_base_url="http://127.0.0.1",
            ai_service_key=os.environ["AIOPS_AI_SERVICE_KEY"],
            redis_enabled=False,
            multi_agent_enabled=True,
        ),
        llm_factory=FixtureLLM,
    )
    if os.getenv("FIXTURE_DOCUMENT"):
        from evaluation.fixtures import FixtureEmbedding
        from app.rag.vector_store import VectorStore
        from qdrant_client import QdrantClient
        from langchain_core.documents import Document

        emb = FixtureEmbedding()
        store = VectorStore("", "", emb.version, emb, client=QdrantClient(":memory:"))
        asyncio.run(
            store.upsert_chunks(
                int(os.environ["FIXTURE_KB"]),
                int(os.environ["FIXTURE_DOCUMENT"]),
                1,
                [
                    Document(
                        page_content="确认训练变慢需要同阶段 Step Time 和吞吐采样。"
                    )
                ],
                "fixture.md",
            )
        )
        app.state.rag_resources.get = lambda settings: (emb, store)
    uvicorn.run(
        app,
        host="127.0.0.1",
        port=int(sys.argv[1]),
        access_log=False,
        log_level="warning",
    )

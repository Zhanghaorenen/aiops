"""Drive the SSE and trace-viewer APIs using the same authenticated test user as the worker."""

import asyncio
import json
import os
from pathlib import Path
import sys
import httpx


async def main():
    ai, server, traceparent = sys.argv[1:4]
    headers = {
        "Authorization": "Bearer " + os.environ["AIOPS_MCP_USER_JWT"],
        "traceparent": traceparent,
    }
    question = f"服务器 {server} 的 GPU 利用率从 90% 到 10%，训练是否变慢？请结合任务和知识证据。"
    async with httpx.AsyncClient(timeout=30) as client:
        events = []
        async with client.stream(
            "POST",
            ai + "/api/chat/stream",
            headers=headers,
            json={"question": question},
        ) as r:
            r.raise_for_status()
            trace = r.headers["x-trace-id"]
            async for line in r.aiter_lines():
                if line.startswith("data:"):
                    events.append(json.loads(line[5:]))
        assert trace == traceparent.split("-")[1]
        assert events[-1] == {"type": "done", "trace_id": trace}
        agents = {e["agent"] for e in events if e["type"] == "agent_start"}
        assert agents == {"monitoring", "workload", "knowledge"}, agents
        docs = [d for e in events if e["type"] == "sources" for d in e["results"]]
        assert any(d["retrieval_source"] == ["dense", "bm25"] for d in docs), docs
        for _ in range(30):
            r = await client.get(
                os.environ["AIOPS_BACKEND_URL"] + "/api/ai/traces/" + trace,
                headers=headers,
            )
            if r.status_code == 200:
                break
            await asyncio.sleep(0.1)
        r.raise_for_status()
        spans = r.json()[-1]["spans"]
        assert any(s["name"] == "supervisor.validate" for s in spans)
        record = {
            "provenance": "isolated Spring SQL fixture + FastAPI/SSE, Qdrant local, simulated LLM",
            "trace_id": trace,
            "agents": sorted(agents),
            "citations": [d["chunk_id"] for d in docs],
            "spans": len(spans),
        }
        Path("target/engineering-scenario.json").write_text(
            json.dumps(record, ensure_ascii=False), encoding="utf-8"
        )


if __name__ == "__main__":
    asyncio.run(main())

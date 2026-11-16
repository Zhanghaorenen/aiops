"""Protocol-level read-only calls against the integration test's isolated Java database."""

import asyncio
import json
import os
import sys
import time
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def main():
    env = {
        k: os.environ[k]
        for k in ["PYTHONPATH", "AIOPS_BACKEND_URL", "AIOPS_MCP_USER_JWT"]
    }
    env["AIOPS_MCP_ENABLED"] = "true"
    srv = int(sys.argv[1])
    incident = int(sys.argv[2])
    args = {
        "get_server_status": {"server_id": srv},
        "get_gpu_status": {"server_id": srv, "gpu_index": 0},
        "get_gpu_processes": {"server_id": srv, "gpu_index": 0},
        "get_active_tasks": {"server_id": srv},
        "search_incidents": {"server_id": srv, "query": "GPU"},
        "get_incident_report": {"incident_id": incident},
    }
    params = StdioServerParameters(
        command=sys.executable, args=["-m", "app.mcp_server.server"], env=env
    )
    timings = {}
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            assert {t.name for t in (await session.list_tools()).tools} == set(args)
            for name, arguments in args.items():
                start = time.perf_counter()
                result = await session.call_tool(name, arguments)
                assert not result.isError, (name, result.content)
                assert "data" in result.structuredContent
                timings[name] = round((time.perf_counter() - start) * 1000, 2)
            denied = await session.call_tool("get_server_status", {"server_id": 999999})
            assert denied.isError
    print(json.dumps({"mcp_ms": timings}))


if __name__ == "__main__":
    asyncio.run(main())

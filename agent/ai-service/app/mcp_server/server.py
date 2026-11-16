"""Run as python -m app.mcp_server.server [--transport streamable-http]."""

import argparse
import json
import logging
import os
import time
from typing import Annotated
from urllib.parse import urlsplit
from uuid import uuid4
import httpx
from mcp.server.auth.settings import AuthSettings
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import ToolAnnotations
from pydantic import BaseModel, Field
from .auth import SCOPE, Verifier, credentials, https_env
from ..client.platform_client import PlatformClient
from ..agents.checkpoint import clean
from ..telemetry import enabled, identify, request_trace, span

Positive = Annotated[int, Field(gt=0)]
Index = Annotated[int, Field(ge=0)]
log = logging.getLogger("aiops.mcp.audit")


class ToolResult(BaseModel):
    data: dict | list
    trace_id: str


def create(remote=False, platform_factory=PlatformClient):
    options = {}
    if remote:
        issuer, resource, jwks = map(
            https_env, ("AIOPS_MCP_ISSUER", "AIOPS_MCP_RESOURCE", "AIOPS_MCP_JWKS")
        )
        origin = urlsplit(resource)
        options.update(
            token_verifier=Verifier(issuer, resource, jwks),
            auth=AuthSettings(
                issuer_url=issuer,
                resource_server_url=resource,
                required_scopes=[SCOPE],
                validate_token_resource=True,
            ),
            transport_security=TransportSecuritySettings(
                enable_dns_rebinding_protection=True,
                allowed_hosts=[origin.netloc],
                allowed_origins=[f"{origin.scheme}://{origin.netloc}"],
            ),
        )
    mcp = FastMCP(
        "AIOps",
        host="127.0.0.1",
        port=int(os.getenv("AIOPS_MCP_PORT", "8010")),
        stateless_http=True,
        **options,
    )
    readonly = ToolAnnotations(
        readOnlyHint=True, destructiveHint=False, idempotentHint=True
    )

    async def invoke(name, params, call):
        start = time.monotonic()
        user_id = None
        status = "ERROR"
        client = None
        trace_id = uuid4().hex
        with request_trace() as trace:
            if trace:
                trace_id = trace.trace_id
            try:
                token, expected = credentials(remote)
                client = platform_factory(
                    os.getenv("AIOPS_BACKEND_URL", "http://127.0.0.1:8080"), token
                )
                me = await client.get_current_user()
                user_id = me["id"]
                if expected is not None and user_id != expected:
                    raise PermissionError("MCP_DELEGATION_MISMATCH")
                identify(userId=user_id)
                with span(name, "tool"):
                    data = await call(client)
                status = "OK"
                return ToolResult(data=clean(data), trace_id=trace_id)
            except httpx.HTTPStatusError as exc:
                status = str(exc.response.status_code)
                raise ValueError("平台拒绝请求或依赖异常 · " + status) from None
            except PermissionError:
                status = "DENIED"
                raise ValueError("调用者没有有效的用户授权") from None
            except Exception as exc:
                status = type(exc).__name__
                raise ValueError("工具无法完成请求") from None
            finally:
                try:
                    if client:
                        await client.close()
                finally:
                    log.info(
                        "%s",
                        json.dumps(
                            {
                                "user_id": user_id,
                                "tool": name,
                                **params,
                                "trace_id": trace_id,
                                "status": status,
                                "latency_ms": round((time.monotonic() - start) * 1000),
                            }
                        ),
                    )

    @mcp.tool(annotations=readonly)
    async def get_server_status(server_id: Positive) -> ToolResult:
        """查询已授权服务器的在线状态和心跳。"""
        return await invoke(
            "get_server_status",
            {"server_id": server_id},
            lambda p: p.get_server(server_id),
        )

    @mcp.tool(annotations=readonly)
    async def get_gpu_status(server_id: Positive, gpu_index: Index) -> ToolResult:
        """查询指定 GPU 的最近一次实际采样。"""
        return await invoke(
            "get_gpu_status",
            {"server_id": server_id, "gpu_index": gpu_index},
            lambda p: p.get_gpu_latest(server_id, gpu_index),
        )

    @mcp.tool(annotations=readonly)
    async def get_gpu_processes(server_id: Positive, gpu_index: Index) -> ToolResult:
        """查询指定 GPU 的可见计算进程。"""
        return await invoke(
            "get_gpu_processes",
            {"server_id": server_id, "gpu_index": gpu_index},
            lambda p: p.get_gpu_process(server_id, gpu_index),
        )

    @mcp.tool(annotations=readonly)
    async def get_active_tasks(server_id: Positive) -> ToolResult:
        """查询当前活跃训练任务。"""
        return await invoke(
            "get_active_tasks",
            {"server_id": server_id},
            lambda p: p.get_active_workloads(server_id),
        )

    @mcp.tool(annotations=readonly)
    async def search_incidents(
        server_id: Positive, query: Annotated[str, Field(min_length=1, max_length=200)]
    ) -> ToolResult:
        """按文本查找该服务器已验证的历史故障经验。"""

        async def search(p):
            rows = await p.get_incident_cases(server_id)
            return [
                r
                for r in rows
                if query.casefold() in json.dumps(r, ensure_ascii=False).casefold()
            ][:20]

        return await invoke(
            "search_incidents",
            {"server_id": server_id, "query_length": len(query)},
            search,
        )

    @mcp.tool(annotations=readonly)
    async def get_incident_report(incident_id: Positive) -> ToolResult:
        """读取有权查看的事件诊断报告。"""

        async def report(p):
            detail = await p._get(f"/api/incidents/{incident_id}", dict)
            reports = []
            for record in detail["reports"]:
                row = {k.lower(): v for k, v in record.items()}
                raw = row["report_json"]
                reports.append(
                    {
                        "id": row["id"],
                        "report": json.loads(raw) if isinstance(raw, str) else raw,
                    }
                )
            return {"incident_id": incident_id, "reports": reports}

        return await invoke("get_incident_report", {"incident_id": incident_id}, report)

    return mcp


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--transport", choices=["stdio", "streamable-http"], default="stdio"
    )
    args = parser.parse_args()
    if not enabled("AIOPS_MCP_ENABLED"):
        parser.error("set AIOPS_MCP_ENABLED=true to enable the MCP server")
    logging.basicConfig(level=logging.INFO)
    create(args.transport != "stdio").run(transport=args.transport)

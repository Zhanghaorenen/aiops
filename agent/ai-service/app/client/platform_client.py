"""Read-only adapter to existing Spring Boot user APIs, scoped to one user JWT."""

import httpx

from ..config import http_url


class PlatformProtocolError(RuntimeError):
    pass


class PlatformClient:
    def __init__(self, base_url: str, access_token: str, timeout: float = 10.0,
                 transport: httpx.AsyncBaseTransport | None = None):
        if not access_token or not access_token.strip():
            raise ValueError("access_token must not be empty")
        self.client = httpx.AsyncClient(
            base_url=http_url(base_url, "backend_url") + "/",
            headers={"Authorization": f"Bearer {access_token}"},
            timeout=timeout, transport=transport, follow_redirects=False,
        )

    async def _get(self, path: str, expected: type, params=None):
        response = await self.client.get(path.lstrip("/"), params=params)
        response.raise_for_status()
        try:
            data = response.json()
        except ValueError as exc:
            raise PlatformProtocolError("平台返回了无效 JSON") from exc
        if not isinstance(data, expected):
            raise PlatformProtocolError("平台返回的数据结构不正确")
        return data

    @staticmethod
    def _server_id(value: int) -> int:
        if type(value) is not int or value < 1:
            raise ValueError("server_id 必须为大于 0 的整数")
        return value
    ##验证是该用户
    async def get_current_user(self) -> dict:
        return await self._get("/api/auth/me", dict)

    async def get_server_list(self) -> list[dict]:
        data = await self._get("/api/servers", list)
        if any(not isinstance(item, dict) for item in data):
            raise PlatformProtocolError("平台服务器列表的数据结构不正确")
        return data

    async def get_server(self, server_id: int) -> dict:
        return await self._get(f"/api/servers/{self._server_id(server_id)}", dict)

    async def get_server_latest_metrics(self, server_id: int) -> dict:
        data = await self._get(f"/api/servers/{self._server_id(server_id)}/metrics/latest", dict)
        if data.get("nodeId") != server_id:
            raise PlatformProtocolError("平台返回的指标不属于指定服务器")
        return data

    async def get_server_metric_history(self, server_id: int, metric_name: str,
                                        start_time: str, end_time: str) -> list[dict]:
        if metric_name not in {"cpu", "memory", "disk"}:
            raise ValueError("当前历史接口只支持 cpu、memory、disk")
        return await self._get(f"/api/servers/{self._server_id(server_id)}/metrics", list,
                               {"from": start_time, "to": end_time, "limit": 1440})

    async def get_server_snapshot(self, server_id: int) -> dict:
        return await self.get_server(server_id)

    async def get_server_overview(self, server_id: int) -> dict:
        return await self.get_server(server_id)

    async def get_cluster_overview(self) -> dict:
        raise NotImplementedError("平台尚未实现集群查询 API")

    async def get_gpu_process(self, server_id: int, gpu_id: int):
        raise NotImplementedError("GPU 进程尚无平台查询 API，阶段 B 完成前不可用")

    async def close(self):
        await self.client.aclose()

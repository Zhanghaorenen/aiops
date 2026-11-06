"""Read-only adapter to existing Spring Boot user APIs, scoped to one user JWT."""

import httpx
from datetime import datetime

from ..config import http_url


class PlatformProtocolError(RuntimeError):
    pass


class PlatformClient:
    def __init__(
        self,
        base_url: str,
        access_token: str,
        timeout: float = 10.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ):
        if not access_token or not access_token.strip():
            raise ValueError("access_token must not be empty")
        self.client = httpx.AsyncClient(
            base_url=http_url(base_url, "backend_url") + "/",
            headers={"Authorization": f"Bearer {access_token}"},
            timeout=timeout,
            transport=transport,
            follow_redirects=False,
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
        data = await self._get(
            f"/api/servers/{self._server_id(server_id)}/metrics/latest", dict
        )
        if data.get("nodeId") != server_id:
            raise PlatformProtocolError("平台返回的指标不属于指定服务器")
        return data

    async def get_server_metric_history(
        self, server_id: int, metric_name: str, start_time: str, end_time: str
    ) -> list[dict]:
        if metric_name not in {"cpu", "memory", "disk"}:
            raise ValueError("当前历史接口只支持 cpu、memory、disk")
        return await self._get(
            f"/api/servers/{self._server_id(server_id)}/metrics",
            list,
            {"from": start_time, "to": end_time, "limit": 1440},
        )

    async def get_server_snapshot(self, server_id: int) -> dict:
        return await self.get_server(server_id)

    async def get_server_overview(self, server_id: int) -> dict:
        return await self.get_server(server_id)

    async def get_cluster_overview(self) -> dict:
        raise NotImplementedError("平台尚未实现集群查询 API")

    @staticmethod
    def _gpu_id(value: int) -> int:
        if type(value) is not int or value < 0:
            raise ValueError("gpu_id 必须为非负整数")
        return value

    @staticmethod
    def _gpu_identity(data: dict, server_id: int, gpu_id: int | None = None) -> None:
        """平台提供归属字段时校验，避免查询结果串入其他机器或显卡的数据。"""
        identities = {"serverId": server_id, "nodeId": server_id}
        if gpu_id is not None:
            identities["gpuIndex"] = gpu_id
        for key, expected in identities.items():
            if key in data and (type(data[key]) is not int or data[key] != expected):
                raise PlatformProtocolError("平台返回的 GPU 数据不属于指定服务器或 GPU")

    async def get_server_gpus(self, server_id: int) -> dict:
        """保留 GPUManager 使用的并行数组快照结构。"""
        server_id = self._server_id(server_id)
        data = await self._get(f"/api/servers/{server_id}/gpus", dict)
        self._gpu_identity(data, server_id)
        return data

    async def get_gpu_latest(self, server_id: int, gpu_id: int) -> dict:
        server_id, gpu_id = self._server_id(server_id), self._gpu_id(gpu_id)
        data = await self._get(f"/api/servers/{server_id}/gpus/{gpu_id}/latest", dict)
        self._gpu_identity(data, server_id, gpu_id)
        return data

    async def get_server_gpu_overview(self, server_id: int) -> dict:
        server_id = self._server_id(server_id)
        data = await self._get(f"/api/servers/{server_id}/gpus/overview", dict)
        self._gpu_identity(data, server_id)
        return data

    async def get_gpu_processes(self, server_id: int) -> list[dict]:
        """查询整机进程列表，每条记录由 gpuIndex 标识所属显卡。"""
        server_id = self._server_id(server_id)
        data = await self._get(f"/api/servers/{server_id}/gpus/processes", list)
        for process in data:
            if not isinstance(process, dict):
                raise PlatformProtocolError("平台 GPU 进程列表的数据结构不正确")
            self._gpu_identity(process, server_id)
        return data

    async def get_gpu_process(self, server_id: int, gpu_id: int) -> list[dict]:
        """兼容现有 GPUManager 的单卡调用，按 gpuIndex 筛选整机进程。"""
        server_id, gpu_id = self._server_id(server_id), self._gpu_id(gpu_id)
        processes = await self.get_gpu_processes(server_id)
        if any(
            type(process.get("gpuIndex")) is not int or process["gpuIndex"] < 0
            for process in processes
        ):
            raise PlatformProtocolError("平台 GPU 进程记录缺少有效的 gpuIndex")
        return [process for process in processes if process["gpuIndex"] == gpu_id]

    async def get_gpu_history(
        self, server_id: int, gpu_id: int, start_time: str, end_time: str
    ) -> list[dict]:
        """时间参数沿用平台现有的 from/to 查询约定。"""
        server_id, gpu_id = self._server_id(server_id), self._gpu_id(gpu_id)
        timestamps = []
        for value in (start_time, end_time):
            if not isinstance(value, str):
                raise ValueError("历史查询时间必须为带时区的 ISO-8601 字符串")
            try:
                parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as error:
                raise ValueError(
                    "历史查询时间必须为带时区的 ISO-8601 字符串"
                ) from error
            if parsed.tzinfo is None:
                raise ValueError("历史查询时间必须包含时区")
            timestamps.append(parsed)
        if timestamps[0] > timestamps[1]:
            raise ValueError("开始时间不能晚于结束时间")
        data = await self._get(
            f"/api/servers/{server_id}/gpus/{gpu_id}/history",
            list,
            {"from": start_time, "to": end_time},
        )
        for sample in data:
            if not isinstance(sample, dict):
                raise PlatformProtocolError("平台 GPU 历史列表的数据结构不正确")
            self._gpu_identity(sample, server_id, gpu_id)
        return data

    async def list_knowledge_bases(self):
        return await self._get("/api/knowledge-bases", list)

    async def get_knowledge_base(self, kb):
        return await self._get(f"/api/knowledge-bases/{self._server_id(kb)}", dict)

    async def list_knowledge_documents(self, kb):
        return await self._get(
            f"/api/knowledge-bases/{self._server_id(kb)}/documents", list
        )

    async def get_knowledge_document(self, kb, doc):
        return await self._get(
            f"/api/knowledge-bases/{self._server_id(kb)}/documents/{self._server_id(doc)}",
            dict,
        )

    async def _knowledge_write(self, method, path, body=None, key=None):
        response = await self.client.request(
            method, path, json=body, headers={"X-RAG-Service-Key": key} if key else {}
        )
        response.raise_for_status()
        return response.json() if response.content else None

    async def register_knowledge_document(self, kb, body, key):
        return await self._knowledge_write(
            "POST", f"/api/knowledge-bases/{kb}/documents", body, key
        )

    async def set_knowledge_status(self, doc, body, key):
        return await self._knowledge_write(
            "PATCH", f"/api/internal/knowledge/documents/{doc}/index-status", body, key
        )

    async def prepare_knowledge_reindex(self, kb, doc, key):
        return await self._knowledge_write(
            "POST", f"/api/knowledge-bases/{kb}/documents/{doc}/reindex", key=key
        )

    async def mark_knowledge_document_deleting(self, kb, doc):
        return await self._knowledge_write(
            "DELETE", f"/api/knowledge-bases/{kb}/documents/{doc}"
        )

    async def mark_knowledge_base_deleting(self, kb):
        return await self._knowledge_write("DELETE", f"/api/knowledge-bases/{kb}")

    async def purge_knowledge_document(self, doc, key):
        return await self._knowledge_write(
            "DELETE", f"/api/internal/knowledge/documents/{doc}", key=key
        )

    async def purge_knowledge_base(self, kb, key):
        return await self._knowledge_write(
            "DELETE", f"/api/internal/knowledge/bases/{kb}", key=key
        )

    async def close(self):
        await self.client.aclose()

    async def get_training_jobs(self, server_id: int) -> list[dict]:
        return await self._get(f"/api/servers/{self._server_id(server_id)}/training/jobs", list)

    async def get_training_analysis(self, server_id: int, job_id: int) -> dict:
        if isinstance(job_id, bool) or not isinstance(job_id, int) or job_id <= 0:
            raise ValueError("job_id must be positive")
        return await self._get(f"/api/servers/{self._server_id(server_id)}/training/jobs/{job_id}/analysis", dict)

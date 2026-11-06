"""Read-only server tools shared by the chat and diagnosis agents."""

import json
from .server_manager import ServerManager
from langchain.tools import tool


class ServerTool:
    def __init__(self, server_manager: ServerManager):
        self.server_manager = server_manager

    # Keep ordinary Python methods for existing DiagnosisAgent callers.
    async def list_servers(self) -> list[dict]:
        return await self.server_manager.list_servers()

    async def get_server_status(self, server_id: int) -> dict:
        return await self.server_manager.get_server_snapshot(server_id)

    async def get_server_resource_usage(self, server_id: int) -> dict:
        return await self.server_manager.get_resource_usage(server_id)

    def get_tools(self) -> list:
        """Return LangChain tools that can be passed to a model or ToolNode."""

        @tool
        async def list_servers() -> list[dict]:
            """列出当前用户可访问的服务器，帮助确定服务器 ID。"""
            return await self.list_servers()

        @tool
        async def get_server_status(server_id: int) -> dict:
            """根据服务器 ID 查询资产信息、在线状态和最近心跳。"""
            return await self.get_server_status(server_id)

        @tool
        async def get_server_resource_usage(server_id: int) -> dict:
            """查询服务器最新 CPU、内存和磁盘指标及采集时间。"""
            return await self.get_server_resource_usage(server_id)

        @tool
        async def list_training_jobs(server_id: int) -> list[dict]:
            """查询授权服务器的共享训练任务、所有者及状态；不会启动训练。"""
            return await self.server_manager.client.get_training_jobs(server_id)

        @tool
        async def analyze_training_contention(server_id: int, job_id: int) -> dict:
            """获取任务的资源竞争规则报告、实际采样时间和证据缺口。相关性不代表因果。"""
            report = await self.server_manager.client.get_training_analysis(server_id, job_id)
            return {key: report.get(key) for key in ('observations', 'missing', 'generatedAt', 'conclusion', 'progressSource', 'gpuUtilizationAttribution')} | {
                'hostEvidence': [{'collectedAt': row.get('collectedAt'), 'cpuPercent': row.get('cpuPercent'), 'details': {key:value for key,value in row.get('details',{}).items() if key in {'memoryAvailableBytes','memoryCachedBytes','swapUsedBytes','cpuPsiAvg10','memoryPsiAvg10','ioPsiAvg10','oomKillCount','shmPercent','load1'}}} for row in report.get('hostEvidence', [])[:3]],
                'processHistory': [{'pid':row.get('pid'),'collectedAt':row.get('collected_at'),'gpuMemoryBytes':row.get('gpu_memory_usage_bytes'),'details':{key:value for key,value in row.get('details',{}).items() if key in {'cpuPercent','memoryRssBytes','readBytesPerSecond','writeBytesPerSecond','startedAt','status'}}} for row in report.get('processHistory', [])[:3]],
                'gpuEvidence': report.get('gpuEvidence',[])[:3],
                'trainingProgress': [{'collectedAt': row.get('collected_at'), 'userReported': json.loads(row.get('payload_json','{}'))} for row in report.get('progress',[])[:3]],
                'sharedDatasets': report.get('sharedDatasets',[])[:10],
                'otherJobs': report.get('otherJobs', [])[:10],
            }

        return [list_servers, get_server_status, get_server_resource_usage, list_training_jobs, analyze_training_contention]


# Preserve the lowercase public alias used by existing imports.
server_tool = ServerTool

"""Read-only server tools shared by the chat and diagnosis agents."""

import json
from .server_manager import ServerManager
from langchain.tools import tool


class ServerTool:
    def __init__(self, server_manager: ServerManager, experience_search=None):
        self.server_manager = server_manager
        self.experience_search = experience_search

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
            """查询已登记的资源计划；服务器实际运行任务优先使用 get_active_workloads，无需先登记。"""
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

        @tool
        async def get_active_workloads(server_id: int) -> list[dict]:
            """查询服务器活跃运行任务和新鲜度；他人任务仅返回授权资源摘要。"""
            rows = await self.server_manager.client.get_active_workloads(server_id)
            # 模型只需要资源和身份标识，不传私人路径、Linux 用户或配置。
            return [{k: v for k, v in row.items() if k != 'run'} | {
                'confirmed': row.get('run', {}).get('confirmed'),
                'job_id': row.get('run', {}).get('job_id'),
            } for row in rows]

        @tool
        async def analyze_active_workload(server_id: int, run_id: str | None = None, snapshot_id: int | None = None, pid: int | None = None) -> dict:
            """直接分析已运行 GPU 任务，不要求登记训练计划。提供 run_id；缺少稳定身份时提供 snapshot_id 和 pid。返回真实指标、瓶颈信号及缺失证据。"""
            report = await self.server_manager.client.analyze_active_workload(server_id, run_id, snapshot_id, pid)
            result = {key: report.get(key) for key in ('serverId', 'runId', 'displayName', 'sampledAt', 'fresh', 'resources', 'observations', 'missingEvidence', 'conclusion', 'metricScope', 'prediction')}
            profile = report.get('profile', {})
            result['profile'] = {key: profile.get(key) for key in ('confidence', 'runCount', 'sampleCount', 'gpuMemoryBytes', 'rssEstimateBytes', 'cpuPercent')}
            return result

        @tool
        async def get_resource_presets(server_id: int) -> dict:
            """查询当前用户的常用资源画像和单次观测参考，用于免手填估算。数值由后端统计计算；同名 python 进程不代表同配置，多卡记录不能当成单卡计划。"""
            presets = await self.server_manager.client.get_resource_presets(server_id)
            keys = ('id', 'sourceRunId', 'label', 'source', 'confidence', 'runCount', 'sampleCount', 'sampledAt', 'suggested', 'multipleGpus', 'basis', 'missingEvidence')
            return {'presets': [{key: item.get(key) for key in keys} for item in presets[:5]], 'totalCount': len(presets), 'truncated': len(presets) > 5}

        @tool
        async def get_workload_profile(server_id: int, run_id: str) -> dict:
            """读取有权访问的同配置任务历史画像，含 P50/P95/峰值、样本量和可信度。"""
            return await self.server_manager.client.get_workload_profile(server_id, run_id)

        @tool
        async def estimate_available_capacity(server_id: int, gpu_index: int = 0, vram_mb: float = 0, ram_mb: float = 0, run_id: str | None = None) -> dict:
            """按实时采样、未启动承诺和安全预留计算资源预算。容量单位 MiB，10GiB=10240MiB。run_id 可辅助匹配历史画像；不预约或启动任务。"""
            return await self.server_manager.client.estimate_available_capacity(server_id, gpu_index, vram_mb, ram_mb, run_id)

        @tool
        async def predict_workload_completion(server_id: int, run_id: str) -> dict:
            """按近期进度或同配置成功历史估算结束范围；样本不足不返回精确时间。预计结束不等于已释放资源。"""
            return await self.server_manager.client.predict_workload_completion(server_id, run_id)

        @tool
        async def search_similar_incidents(server_id: int, query: str) -> dict:
            """检索当前用户获准读取且已验证的历史故障案例；历史经验不是当前根因。"""
            if not self.experience_search:
                return {'results': [], 'missingEvidence': ['故障经验向量检索尚未配置']}
            return await self.experience_search(server_id, query)

        @tool
        async def get_private_memories() -> list[dict]:
            """仅在用户要求回顾偏好或已确认经验时查询本人的长期记忆；记忆不代表实时资源。"""
            return await self.server_manager.client.get_private_memories()

        @tool
        async def get_resource_guard_status(server_id: int) -> dict:
            """读取服务器资源保护策略、采集端本地授权状态和实际处置审计。没有回执不能声称进程已结束。"""
            status=await self.server_manager.client.get_resource_guard_status(server_id)
            return {**status,'events':status.get('events',[])[:5]}

        @tool
        async def request_resource_relief(server_id: int) -> dict:
            """仅管理员可请求一次资源保护检查，不直接结束进程。无PID/命令参数；本地已启用策略仍须满足持续内存压力和保护规则。返回requested不代表执行成功。"""
            return await self.server_manager.client.request_resource_relief(server_id)

        return [get_resource_guard_status, request_resource_relief, list_servers, get_server_status, get_server_resource_usage, list_training_jobs, analyze_training_contention,
                get_active_workloads, analyze_active_workload, get_resource_presets, get_workload_profile, estimate_available_capacity, predict_workload_completion,
                search_similar_incidents, get_private_memories]


# Preserve the lowercase public alias used by existing imports.
server_tool = ServerTool

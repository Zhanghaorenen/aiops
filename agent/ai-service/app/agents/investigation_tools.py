"""Bounded read-only probe registry. The model cannot supply a server, URL, SQL or shell."""
import json
from datetime import datetime, timedelta, timezone
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator
from ..tools.server_manager import ServerManager
from ..tools.server_tool import ServerTool
from ..tools.gpu_manager import GPUManager
from ..tools.gpu_tool import GpuTool

ToolName = Literal["host", "status", "gpu", "gpu_processes", "host_history",
                   "gpu_history", "workloads", "runtime_contention", "training_analysis",
                   "workload_analysis", "knowledge", "historical_cases"]

class Probe(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    tool: ToolName
    gpu_index: int | None = Field(default=None, ge=0)
    job_id: int | None = Field(default=None, gt=0)
    run_id: str | None = Field(default=None, max_length=36)
    purpose: str = Field(default="补充证据", max_length=300)

    @model_validator(mode="after")
    def scope_arguments(self):
        required={"gpu_history":"gpu_index","training_analysis":"job_id","workload_analysis":"run_id"}
        for field in ("gpu_index","job_id","run_id"):
            value=getattr(self,field)
            if required.get(self.tool)==field:
                if value is None: raise ValueError("MISSING_PROBE_SCOPE")
                if field=="run_id": UUID(value)
            elif value is not None:
                raise ValueError("UNEXPECTED_PROBE_ARGUMENT")
        return self

    def key(self):
        return (self.tool, self.gpu_index, self.job_id, self.run_id)

def bounded(value, depth=0):
    if depth>9:
        return {"limitation": "结构深度超过展示上限"}
    if isinstance(value, dict):
        return {str(k): bounded(v, depth+1) for k,v in list(value.items())[:60]}
    if isinstance(value, list):
        return [bounded(v, depth+1) for v in value[-60:]]
    if isinstance(value, str):
        return value[:2000]
    return value

def compact(value):
    value=bounded(value)
    if len(json.dumps(value,ensure_ascii=False,default=str).encode("utf-8"))<=24000:
        return value
    # Retain a valid JSON structure; do not insert a cut-off JSON string as monitoring evidence.
    def shrink(item):
        if isinstance(item,list): return [shrink(v) for v in item[-3:]]
        if isinstance(item,dict): return {k:shrink(v) for k,v in item.items()}
        return item
    value=shrink(value)
    if len(json.dumps(value,ensure_ascii=False,default=str).encode("utf-8"))>24000:
        return {"dataOmitted":True,"limitation":"单项证据超过24000字节，保留来源及数据缺口"}
    return value

class InvestigationTools:
    def __init__(self, platform, server, anchor, run_id=None):
        self.platform, self.server, self.anchor = platform, server, anchor
        self.host = ServerTool(ServerManager(platform))
        self.gpu = GpuTool(GPUManager(platform))
        self.gpus, self.jobs, self.runs = set(), set(), set()
        # Explicit run targets have been authorized by Java and are verified again by their own API.
        if run_id:
            self.runs.add(str(UUID(run_id)))

    async def execute(self, probe, knowledge, cases):
        tool, server = probe.tool, self.server
        if probe.gpu_index is not None and (tool!="gpu_history" or probe.gpu_index not in self.gpus):
            raise ValueError("GPU_OUTSIDE_DISCOVERED_SCOPE")
        if probe.job_id is not None and (tool!="training_analysis" or probe.job_id not in self.jobs):
            raise ValueError("JOB_OUTSIDE_DISCOVERED_SCOPE")
        if probe.run_id is not None and (tool!="workload_analysis" or str(UUID(probe.run_id)) not in self.runs):
            raise ValueError("RUN_OUTSIDE_DISCOVERED_SCOPE")
        start, end = (self.anchor-timedelta(minutes=30)).isoformat(), self.anchor.isoformat()
        if tool=="host": return await self.host.get_server_resource_usage(server)
        if tool=="status": return await self.platform.get_server(server)
        if tool=="gpu":
            data=await self.platform.get_server_gpus(server)
            self.gpus.update(i for i in data.get("gpuIndices",[]) if type(i) is int and i>=0)
            return data
        if tool=="gpu_processes": return await self.gpu.get_gpu_processes(server)
        if tool=="host_history": return await self.platform.get_server_metric_history(server,"cpu",start,end)
        if tool=="gpu_history":
            if probe.gpu_index is None: raise ValueError("GPU_REQUIRED")
            return await self.platform.get_gpu_history(server,probe.gpu_index,start,end)
        if tool=="workloads":
            data=await self.platform.get_active_workloads(server)
            self.runs.update(str(UUID(r["runId"])) for r in data if r.get("runId"))
            jobs=await self.platform.get_training_jobs(server)
            self.jobs.update(r["id"] for r in jobs if type(r.get("id")) is int)
            return {"activeTasks": data, "registeredTasks": jobs}
        if tool=="runtime_contention":
            return await self.platform._get(f"/api/servers/{server}/training/runtime-contention",dict)
        if tool=="training_analysis":
            if probe.job_id is None: raise ValueError("JOB_REQUIRED")
            return await self.platform.get_training_analysis(server,probe.job_id)
        if tool=="workload_analysis":
            if probe.run_id is None: raise ValueError("RUN_REQUIRED")
            return await self.platform.analyze_active_workload(server,probe.run_id)
        if tool=="knowledge": return await knowledge()
        if tool=="historical_cases": return await cases()
        raise ValueError("UNREGISTERED_TOOL")

def data_status(data, tool, now):
    if tool in {"knowledge","historical_cases"}: return "REFERENCE", None
    times=[]
    def visit(value):
        if isinstance(value,dict):
            for key,item in value.items():
                if key in {"collectedAt","sampledAt","collected_at","sampled_at","latestSampleAt","lastHeartbeatAt"} and isinstance(item,str):
                    try:
                        at=datetime.fromisoformat(item.replace("Z","+00:00"))
                        if at.tzinfo: times.append(at)
                    except ValueError: pass
                elif isinstance(item,(list,dict)): visit(item)
        elif isinstance(value,list):
            for item in value: visit(item)
    visit(data)
    if isinstance(data,dict) and data.get("freshness") in {"STALE","UNKNOWN"}:
        return data["freshness"], max(times).isoformat() if times else None
    if not times: return "UNKNOWN",None
    latest=max(times)
    age=(now-latest).total_seconds()
    return ("FRESH" if -30<=age<=90 else "STALE"),latest.isoformat()

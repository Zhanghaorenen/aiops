from typing import Literal
from pydantic import BaseModel, ConfigDict, Field

class Model(BaseModel):
    model_config=ConfigDict(extra="forbid")

class AgentTask(Model):
    task_id: str=Field(min_length=1,max_length=80)
    agent: Literal["monitoring","workload","knowledge"]
    objective: str=Field(min_length=1,max_length=2000)
    server_id: int | None=Field(default=None,gt=0)
    gpu_index: int | None=Field(default=None,ge=0)
    run_id: str | None=None
    tool_budget: int=Field(default=4,ge=1,le=4)

class TaskPlan(Model):
    intent: Literal["monitoring","workload","knowledge","incident_diagnosis","resource_planning","general"]
    execution_mode: Literal["parallel","sequential"]="parallel"
    tasks: list[AgentTask]=Field(default_factory=list,max_length=3)
    clarification: str | None=Field(default=None,max_length=1000)

class EvidenceItem(Model):
    evidence_id: str
    source_agent: Literal["monitoring","workload","knowledge"]
    source_tool: str
    category: Literal["metric","workload","document","derived"]
    status: Literal["FRESH","STALE","UNKNOWN","UNAVAILABLE","FORBIDDEN","REFERENCE"]
    observed_at: str | None=None
    data: dict=Field(default_factory=dict)
    limitations: list[str]=Field(default_factory=list)

class Finding(Model):
    finding_type: Literal["observation","hypothesis","recommendation"]
    statement: str=Field(max_length=2000)
    supporting_evidence_ids: list[str]=Field(default_factory=list,max_length=12)
    refuting_evidence_ids: list[str]=Field(default_factory=list,max_length=12)
    uncertainty: str | None=None

class AgentResult(Model):
    agent: str
    status: Literal["success","partial","failed","skipped"]
    summary: str
    findings: list[Finding]=Field(default_factory=list)
    evidence: list[EvidenceItem]=Field(default_factory=list)
    limitations: list[str]=Field(default_factory=list)
    tool_calls: int=0
    elapsed_ms: int=0
    error_code: str | None=None

class Synthesis(Model):
    findings: list[Finding]=Field(default_factory=list,max_length=15)

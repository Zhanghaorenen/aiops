from typing import TypedDict
from .schemas import TaskPlan, AgentResult, EvidenceItem

class SupervisorState(TypedDict,total=False):
    request_id: str
    question: str
    history: list[dict]
    plan: TaskPlan
    intent: str
    execution_mode: str
    agent_results: dict[str,AgentResult]
    evidence_index: list[EvidenceItem]
    final_answer: str
    status: str
    events: list[dict]
    errors: list[dict]
    tool_calls_total: int
    synthesis: dict
    findings: list
    private_preferences: list

import json
from pathlib import Path
from .schemas import TaskPlan

async def plan(llm,question,history):
    prompt=(Path(__file__).parent/"prompts/supervisor.md").read_text(encoding="utf-8")
    response=await llm.chat([{"role":"system","content":prompt+"\n"+json.dumps(TaskPlan.model_json_schema(),ensure_ascii=False)},
        {"role":"user","content":json.dumps({"question":question,"history":history[-10:]},ensure_ascii=False)}],
        json_mode=True,max_tokens=1800)
    result=TaskPlan.model_validate_json(response.content or "")
    if len({t.agent for t in result.tasks})!=len(result.tasks) or len({t.task_id for t in result.tasks})!=len(result.tasks):
        raise ValueError("DUPLICATE_AGENT_TASK")
    return result

import json
import time
import uuid
import asyncio
import httpx
from datetime import datetime,timezone
from pathlib import Path
from langchain_core.messages import SystemMessage
from langchain_core.tools import StructuredTool
from ...graph_agent import GraphAgent,MAX_TOOL_RESULT_LENGTH
from ...incident_graph import data_status
from ..schemas import AgentResult,EvidenceItem,Finding
from ..events import emit,writer

class ToolSet:
    def __init__(self,tools): self.tools=tools
    def get_tools(self): return self.tools

class DomainGraph(GraphAgent):
    def __init__(self,*args,prompt,domain,**kwargs):
        self.domain=domain
        self.domain_prompt=prompt
        super().__init__(*args,**kwargs)
    async def answer_with_events(self,question,sink):
        final=None
        async for mode,value in self.graph.astream(self._initial_state(question,[],False),
                config={"recursion_limit":10},stream_mode=["custom","values"]):
            if mode=="custom":
                if value.get("type")!="token": sink({**value,"agent":self.domain})
            else: final=value
        return final["messages"][-1].content

    def _wrap_tool(self,source):
        return GraphAgent._wrap_tool(source,{"agent":self.domain})

    def _initial_state(self,question,history,streaming):
        state=super()._initial_state(question,history,streaming)
        state["messages"][0]=SystemMessage(content=self.domain_prompt+
            "\n当前UTC时间："+datetime.now(timezone.utc).isoformat())
        return state

class BaseAgent:
    name=""
    whitelist=()
    def __init__(self,llm,tools,budget):
        self.llm=llm
        self.tools=[t for t in tools if t.name in self.whitelist]
        self.budget=budget
    async def run(self,task,context):
        started=time.monotonic()
        evidence=[]
        calls=0
        prompt=(Path(__file__).parents[1]/"prompts"/(self.name+".md")).read_text(encoding="utf-8")
        async def capture(source,arguments):
            nonlocal calls
            target=arguments.get("server_id")
            if target is not None and target!=task.server_id:
                raise ValueError("TASK_SERVER_SCOPE_MISMATCH")
            if task.gpu_index is not None and arguments.get("gpu_id",arguments.get("gpu_index",task.gpu_index))!=task.gpu_index:
                raise ValueError("TASK_GPU_SCOPE_MISMATCH")
            if task.run_id and arguments.get("run_id",task.run_id)!=task.run_id:
                raise ValueError("TASK_RUN_SCOPE_MISMATCH")
            await self.budget.claim()
            calls+=1
            eid="E-"+uuid.uuid4().hex
            category="document" if self.name=="knowledge" else "workload" if self.name=="workload" else "metric"
            try:
                data=await source.ainvoke(arguments)
                encoded=json.dumps(data,ensure_ascii=False,default=str)
                if len(encoded)>MAX_TOOL_RESULT_LENGTH-1000:
                    raise ValueError("TOOL_RESULT_TOO_LARGE")
                status,at=data_status(data,source.name,datetime.now(timezone.utc))
                if category=="document": status="REFERENCE"
                body=data if isinstance(data,dict) else {"results":data}
                evidence.append(EvidenceItem(evidence_id=eid,source_agent=self.name,source_tool=source.name,
                    category=category,status=status,observed_at=at,data=body,
                    limitations=[] if status in {"FRESH","REFERENCE"} else ["缺少新鲜、完整的采样证据"]))
                return data
            except httpx.HTTPStatusError as exc:
                if exc.response.status_code in (401,403): raise
                evidence.append(EvidenceItem(evidence_id=eid,source_agent=self.name,source_tool=source.name,
                    category=category,status="UNAVAILABLE",limitations=["平台请求失败"]))
                raise
            except Exception:
                evidence.append(EvidenceItem(evidence_id=eid,source_agent=self.name,source_tool=source.name,
                    category=category,status="UNAVAILABLE",limitations=["工具结果不可用或超过大小限制"]))
                raise
        def instrument(source):
            async def invoke(**arguments): return await capture(source,arguments)
            return StructuredTool.from_function(coroutine=invoke,name=source.name,
                description=source.description,args_schema=source.args_schema)
        runner=DomainGraph(self.llm,ToolSet([instrument(t) for t in self.tools]),ToolSet([]),
            max_rounds=3,prompt=prompt,domain=self.name,max_tool_calls=task.tool_budget,deduplicate_calls=True)
        emit("agent_start",agent=self.name,task_id=task.task_id)
        try:
            sink=writer()
            answer=await asyncio.wait_for(runner.answer_with_events(
                task.objective+"\n任务范围："+json.dumps(task.model_dump(),ensure_ascii=False)+
                "\n不可信参考上下文："+json.dumps(context,ensure_ascii=False,default=str)[:18000],sink),
                timeout=max(.001,self.budget.remaining()*.65))
            good=[e for e in evidence if e.status in {"FRESH","REFERENCE"}]
            status="success" if evidence and len(good)==len(evidence) else "partial"
            findings=[Finding(finding_type="recommendation" if e.category=="document" else "observation",
                statement="取得"+e.source_tool+"的原始证据",supporting_evidence_ids=[e.evidence_id]) for e in good]
            result=AgentResult(agent=self.name,status=status,summary=answer,findings=findings,evidence=evidence,
                tool_calls=calls,elapsed_ms=round((time.monotonic()-started)*1000),
                limitations=[] if status=="success" else ["部分证据缺失，不能据此确认当前故障"])
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code in (401,403): raise
            raise
        except Exception as exc:
            result=AgentResult(agent=self.name,status="partial" if evidence else "failed",
                summary="专业调查未全部完成",evidence=evidence,tool_calls=calls,
                elapsed_ms=round((time.monotonic()-started)*1000),error_code=type(exc).__name__,
                limitations=["超时、依赖失败或执行预算不足"])
        emit("agent_result",agent=self.name,status=result.status,tool_calls=result.tool_calls,
            elapsed_ms=result.elapsed_ms)
        return result

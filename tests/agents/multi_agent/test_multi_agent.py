import asyncio
import json
import unittest
from datetime import datetime,timedelta,timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock,patch
import httpx
from langchain_core.tools import StructuredTool
from app.agents.multi_agent.schemas import AgentTask,TaskPlan,EvidenceItem,Finding,AgentResult
from app.agents.multi_agent.agents.base import ToolSet
from app.agents.multi_agent.agents.monitoring_agent import MonitoringAgent,MONITORING_TOOLS
from app.agents.multi_agent.agents.workload_agent import WorkloadAgent,WORKLOAD_TOOLS
from app.agents.multi_agent.agents.knowledge_agent import KnowledgeAgent,KNOWLEDGE_TOOLS
from app.agents.multi_agent.budget import Budget
from app.agents.multi_agent.supervisor import MultiAgentSupervisor
from app.agents.multi_agent.evidence_validator import validate
from app.agents.multi_agent.result_merger import merge

def message(content="",calls=None):
    return SimpleNamespace(content=content,tool_calls=calls or [])
def call(name,args):
    return {"id":"call-1","type":"function","function":{"name":name,"arguments":json.dumps(args)}}
def task(agent,server=41):
    return AgentTask(task_id=agent,agent=agent,objective="检查相关证据",server_id=server)
def evidence(status="FRESH",category="metric",identifier="E1",tool="get_gpu_status",agent="monitoring"):
    return EvidenceItem(evidence_id=identifier,source_agent=agent,source_tool=tool,category=category,
        status=status,observed_at=datetime.now(timezone.utc).isoformat(),data={"serverId":41,"temperature":65})

class Model:
    def __init__(self,agents,mode="parallel",bad_tool=None,repeat=False):
        self.agents=agents;self.mode=mode;self.bad_tool=bad_tool;self.repeat=repeat
    async def chat(self,messages,tools=None,**kwargs):
        if tools:
            if messages[-1]["role"]=="tool" and not self.repeat: return message("内部分析草稿")
            names=[t["function"]["name"] for t in tools]
            name=self.bad_tool or names[0]
            args={"query":"GPU排查"} if name=="search_knowledge" else {"server_id":41}
            return message(calls=[call(name,args)])
        if kwargs.get("json_mode"):
            if '"TaskPlan"' in messages[0]["content"]:
                return message(json.dumps(TaskPlan(intent="incident_diagnosis" if len(self.agents)>1 else self.agents[0] if self.agents else "general",
                    execution_mode=self.mode,tasks=[task(a) for a in self.agents]).model_dump()))
            payload=json.loads(messages[-1]["content"])
            ev=payload["evidence"]
            return message(json.dumps({"findings":[{"finding_type":"observation","statement":"工具采样显示当前指标",
                "supporting_evidence_ids":[e["evidence_id"] for e in ev],"refuting_evidence_ids":[]}]}))
        return message("普通回答")

class MultiTests(unittest.IsolatedAsyncioTestCase):
    def setup(self,agents=("monitoring",),**kwargs):
        self.invoked=[]
        async def gpu(server_id:int):
            """查询显卡监控。"""
            self.invoked.append("monitoring")
            return {"serverId":server_id,"collectedAt":datetime.now(timezone.utc).isoformat(),"temperature":65}
        async def jobs(server_id:int):
            """查询任务。"""
            self.invoked.append("workload")
            return {"serverId":server_id,"collectedAt":datetime.now(timezone.utc).isoformat(),"jobs":[]}
        async def knowledge(query:str):
            """检索知识。"""
            self.invoked.append("knowledge")
            return {"results":[{"document_title":"运维手册","content":"训练前核对资源","chunk_id":"doc-1"}]}
        self.tools=[StructuredTool.from_function(coroutine=f,name=name) for f,name in
            [(gpu,"get_gpu_status"),(jobs,"list_training_jobs"),(knowledge,"search_knowledge")]]
        self.platform=SimpleNamespace(get_server=AsyncMock(return_value={"id":41}),
            get_server_list=AsyncMock(return_value=[{"id":41}]),close=AsyncMock())
        return MultiAgentSupervisor(Model(list(agents),**kwargs),ToolSet(self.tools),ToolSet([]),
            platform=self.platform,chat_timeout=2)

    async def test_temperature_only_monitoring(self):
        sup=self.setup()
        answer=await sup.ainvoke("服务器41当前GPU温度")
        self.assertEqual(self.invoked,["monitoring"]);self.assertIn("证据来源",answer)
        self.platform.close.assert_not_awaited()

    async def test_same_supervisor_concurrent_requests_have_independent_state(self):
        sup=self.setup()
        answers=await asyncio.gather(sup.ainvoke("GPU温度"),sup.ainvoke("GPU温度"))
        self.assertEqual(len(self.invoked),2)
        self.assertNotEqual(answers[0],answers[1])
        self.assertTrue(all("monitoring/get_gpu_status" in a for a in answers))

    async def test_training_only_workload(self):
        await self.setup(("workload",)).ainvoke("服务器41训练任务")
        self.assertEqual(self.invoked,["workload"])

    async def test_cuda_document_only_knowledge(self):
        answer=await self.setup(("knowledge",)).ainvoke("CUDA OOM文档")
        self.assertEqual(self.invoked,["knowledge"])
        self.assertIn("REFERENCE",answer)
        self.assertIn("未验证",answer)

    async def test_full_gpu_training_demo_has_three_agents_and_unique_evidence(self):
        sup=self.setup(("monitoring","workload","knowledge"))
        events=[e async for e in sup.astream("我的GPU利用率从90%降到10%，训练变慢，请分析")]
        self.assertEqual(set(self.invoked),{"monitoring","workload","knowledge"})
        kinds=[e["type"] for e in events]
        for kind in ("plan","agent_start","tool_start","tool_end","agent_result","synthesis","token","sources"):
            self.assertIn(kind,kinds)
        self.assertFalse(any("内部分析草稿" in e.get("text","") for e in events))
        self.assertEqual(len([e for e in events if e["type"]=="agent_result"]),3)

    async def test_two_agents_run_concurrently_without_state_overwrite(self):
        sup=self.setup(("monitoring","workload"))
        arrived=0;ready=asyncio.Event()
        originals=sup.tools[:]
        def wrap(source):
            async def invoke(**kw):
                nonlocal arrived
                arrived+=1
                if arrived==2: ready.set()
                await asyncio.wait_for(ready.wait(),.5)
                return await source.ainvoke(kw)
            return StructuredTool.from_function(coroutine=invoke,name=source.name,
                description=source.description,args_schema=source.args_schema)
        sup.tools=[wrap(t) for t in originals]
        answer=await sup.ainvoke("资源竞争")
        self.assertEqual(arrived,2);self.assertIn("monitoring/get_gpu_status",answer)
        self.assertIn("workload/list_training_jobs",answer)

    async def test_sequential_passes_structured_previous_results(self):
        sup=self.setup(("monitoring","workload"),mode="sequential")
        await sup.ainvoke("依赖分析")
        self.assertEqual(self.invoked,["monitoring","workload"])

    async def test_forbidden_propagates_and_never_becomes_success(self):
        sup=self.setup()
        async def denied(server_id:int):
            """权限受限。"""
            response=httpx.Response(403,request=httpx.Request("GET","http://platform"))
            raise httpx.HTTPStatusError("forbidden",request=response.request,response=response)
        sup.tools=[StructuredTool.from_function(coroutine=denied,name="get_gpu_status")]
        with self.assertRaises(httpx.HTTPStatusError): await sup.ainvoke("GPU")

    async def test_domain_cannot_call_other_domain_tool(self):
        sup=self.setup(bad_tool="list_training_jobs")
        answer=await sup.ainvoke("GPU")
        self.assertEqual(self.invoked,[]);self.assertIn("尚未确认",answer)

    async def test_duplicate_success_calls_execute_once(self):
        await self.setup(repeat=True).ainvoke("GPU")
        self.assertEqual(self.invoked,["monitoring"])

    async def test_failure_keeps_other_agents_evidence(self):
        sup=self.setup(("monitoring","workload"))
        async def broken(server_id:int):
            """故障工具。"""
            raise RuntimeError("dependency")
        sup.tools[0]=StructuredTool.from_function(coroutine=broken,name="get_gpu_status")
        answer=await sup.ainvoke("资源竞争")
        self.assertIn("workload/list_training_jobs",answer);self.assertIn("UNAVAILABLE",answer)

    async def test_timeout_keeps_other_agents_results(self):
        sup=self.setup(("monitoring","workload"));sup.timeout=.8
        async def slow(server_id:int):
            """慢工具。"""
            await asyncio.sleep(5)
        sup.tools[0]=StructuredTool.from_function(coroutine=slow,name="get_gpu_status")
        answer=await sup.ainvoke("资源竞争")
        self.assertIn("workload/list_training_jobs",answer);self.assertIn("尚未确认",answer)

    async def test_cross_server_arguments_rejected(self):
        sup=self.setup()
        original=sup.llm.chat
        async def different(messages,tools=None,**kwargs):
            if tools: return message(calls=[call("get_gpu_status",{"server_id":99})])
            return await original(messages,tools,**kwargs)
        sup.llm.chat=different
        answer=await sup.ainvoke("GPU")
        self.assertFalse(self.invoked);self.assertIn("尚未确认",answer)

    async def test_multiple_servers_requires_clarification(self):
        sup=self.setup()
        original=sup.llm.chat
        async def missing(messages,tools=None,**kw):
            if '"TaskPlan"' in messages[0]["content"]:
                return message(json.dumps(TaskPlan(intent="monitoring",tasks=[task("monitoring",None)]).model_dump()))
            return await original(messages,tools,**kw)
        sup.llm.chat=missing
        sup.platform.get_server_list.return_value=[{"id":41},{"id":42}]
        answer=await sup.ainvoke("GPU温度")
        self.assertIn("请指定",answer);self.assertFalse(self.invoked)

    async def test_general_uses_no_domain_tools(self):
        answer=await self.setup(()).ainvoke("你好")
        self.assertEqual(answer,"普通回答");self.assertFalse(self.invoked)

    async def test_whitelists_exclude_restricted_tools(self):
        names=set(MONITORING_TOOLS+WORKLOAD_TOOLS+KNOWLEDGE_TOOLS)
        self.assertNotIn("request_resource_relief",names);self.assertNotIn("get_private_memories",names)

class EvidenceTests(unittest.TestCase):
    def finding(self,ids=("E1",),opposed=()):
        return Finding(finding_type="observation",statement="采样指标",supporting_evidence_ids=list(ids),
            refuting_evidence_ids=list(opposed))
    def test_real_reference(self):
        self.assertEqual(validate([self.finding()],[evidence()])[0].finding_type,"observation")
    def test_missing_reference_is_unverified(self):
        self.assertIn("不存在",validate([self.finding(("fake",))],[evidence()])[0].uncertainty)
    def test_stale_timestamp_cannot_be_claimed_fresh(self):
        item=evidence();item.observed_at=(datetime.now(timezone.utc)-timedelta(minutes=5)).isoformat()
        result=validate([self.finding()],[item])[0]
        self.assertEqual(item.status,"STALE");self.assertEqual(result.finding_type,"hypothesis")
    def test_invalid_document_source_cannot_hide_as_reference(self):
        item=evidence(category="document",tool="request_resource_relief",agent="knowledge")
        validate([self.finding()],[item])
        self.assertEqual(item.status,"UNAVAILABLE")

    def test_reference_not_realtime(self):
        item=evidence(category="document",tool="search_knowledge",agent="knowledge")
        self.assertEqual(validate([self.finding()],[item])[0].finding_type,"hypothesis")
    def test_unknown_forbidden_and_unavailable_are_not_facts(self):
        for status in ("UNKNOWN","FORBIDDEN","UNAVAILABLE"):
            self.assertEqual(validate([self.finding()],[evidence(status)])[0].finding_type,"hypothesis")
    def test_support_and_refutation_retained(self):
        result=validate([self.finding(opposed=("E2",))],[evidence(),evidence(identifier="E2")])[0]
        self.assertEqual(result.refuting_evidence_ids,["E2"])
    def test_duplicate_evidence_does_not_count_twice(self):
        a=evidence();b=a.model_copy(update={"evidence_id":"E2"})
        results={"monitoring":AgentResult(agent="monitoring",status="success",summary="",evidence=[a,b])}
        self.assertEqual(len(merge(results)),1)
    def test_conflicting_same_sample_evidence_is_flagged(self):
        a=evidence();b=a.model_copy(update={"evidence_id":"E2","data":{"serverId":41,"temperature":99}})
        self.assertIn("差异",validate([self.finding()],[a,b])[0].uncertainty)

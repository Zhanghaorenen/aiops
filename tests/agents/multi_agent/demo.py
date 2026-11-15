"""A deterministic GPU-training scenario using explicitly simulated tools and model."""
import asyncio
import json
from datetime import datetime,timedelta,timezone
from langchain_core.tools import StructuredTool
from test_multi_agent import MultiTests,Model,message,call

class DemoModel(Model):
    async def chat(self,messages,tools=None,**kwargs):
        names=[t["function"]["name"] for t in tools or []]
        if "get_recent_gpu_history" in names and messages[-1]["role"]!="tool":
            first=call("get_gpu_status",{"server_id":41})
            second=call("get_recent_gpu_history",{"server_id":41})
            second["id"]="history-2"
            return message(calls=[first,second])
        if kwargs.get("json_mode") and '"TaskPlan"' not in messages[0]["content"]:
            evidence=json.loads(messages[-1]["content"])["evidence"]
            findings=[]
            for e in evidence:
                data=e["data"]
                if "utilization" in data:
                    findings.append({"finding_type":"observation",
                        "statement":"模拟工具返回 GPU 利用率 "+str(data["utilization"])+"%，温度 "+str(data["temperature"])+"°C。",
                        "supporting_evidence_ids":[e["evidence_id"]]})
                if "samples" in data:
                    findings.append({"finding_type":"observation",
                        "statement":"模拟历史采样返回的利用率序列为 "+str([s["utilization"] for s in data["samples"]])+"%。",
                        "supporting_evidence_ids":[e["evidence_id"]]})
                if "jobs" in data:
                    findings.append({"finding_type":"hypothesis",
                        "statement":"任务仍在运行，缺少同阶段 Step Time 和吞吐数据，性能下降尚未验证。",
                        "supporting_evidence_ids":[e["evidence_id"]],"uncertainty":"需要补充训练进度证据"})
                if e["category"]=="document":
                    findings.append({"finding_type":"recommendation",
                        "statement":"依据检索片段核对数据供应与存储等待，再比较同阶段训练吞吐。",
                        "supporting_evidence_ids":[e["evidence_id"]],"uncertainty":"静态文档只提供检查方法"})
            return message(json.dumps({"findings":findings},ensure_ascii=False))
        return await super().chat(messages,tools,**kwargs)

async def main():
    fixture=MultiTests()
    supervisor=fixture.setup(("monitoring","workload","knowledge"))
    supervisor.llm=DemoModel(["monitoring","workload","knowledge"])
    now=datetime.now(timezone.utc)
    async def gpu(server_id:int):
        """模拟最新 GPU 监控。"""
        return {"serverId":server_id,"collectedAt":now.isoformat(),"utilization":10,"temperature":65}
    async def history(server_id:int):
        """模拟最近 GPU 历史。"""
        return {"serverId":server_id,"collectedAt":now.isoformat(),"samples":[
            {"collectedAt":(now-timedelta(minutes=10)).isoformat(),"utilization":90},
            {"collectedAt":now.isoformat(),"utilization":10}]}
    async def jobs(server_id:int):
        """模拟运行任务。"""
        return {"serverId":server_id,"collectedAt":now.isoformat(),
            "jobs":[{"id":7,"state":"RUNNING","stepTime":None,"samplesPerSecond":None}]}
    async def knowledge(query:str):
        """模拟知识库检索。"""
        return {"results":[{"document_title":"模拟训练排查手册","chunk_id":"demo-doc",
            "content":"GPU利用率降低时核对数据供应、存储等待和同阶段训练吞吐。"}]}
    supervisor.tools=[StructuredTool.from_function(coroutine=f,name=name) for f,name in
        [(gpu,"get_gpu_status"),(history,"get_recent_gpu_history"),(jobs,"list_training_jobs"),(knowledge,"search_knowledge")]]
    print("模拟演示：Mock LLM 与 Mock 工具；执行真实 Supervisor 图、工具节点和证据校验。")
    async for event in supervisor.astream("我的 GPU 利用率突然从 90% 降到了 10%，训练速度也明显变慢，帮我分析一下原因。"):
        print(json.dumps(event,ensure_ascii=False))

if __name__=="__main__":
    asyncio.run(main())

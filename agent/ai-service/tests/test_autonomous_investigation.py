"""Deterministic incident simulation. No server processes or real training jobs are changed."""
import json
import unittest
from datetime import datetime, timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock
import httpx
from pydantic import ValidationError
from app.agents.incident_graph import IncidentGraph
from app.agents.investigation_tools import Probe

def decision(probes=(),missing=(),support=("E1",)):
    return {"possible_causes":[{"hypothesis":"数据供应等待，仍需验证","supporting_evidence_ids":list(support),
        "refuting_evidence_ids":[],"required_evidence":["主机IO压力与同阶段训练吞吐"],
        "uncertainty":"相关指标同时变化，原因待核实"}],"missing_evidence":list(missing),
        "suggested_steps":["核对DataLoader并发与存储等待"],"verification_steps":["比较同阶段训练吞吐"],
        "probes":list(probes),"sufficient":False}

class AutonomousInvestigationTests(unittest.IsolatedAsyncioTestCase):
    def setup_graph(self,answers,**budgets):
        now=datetime.now(timezone.utc).isoformat()
        platform=SimpleNamespace(
            get_server_latest_metrics=AsyncMock(return_value={"nodeId":1,"collectedAt":now,"cpuPercent":45,"details":{"ioPsiAvg10":35,"memoryPsiAvg10":0}}),
            get_server_gpus=AsyncMock(return_value={"serverId":1,"collectedAt":now,"gpuIndices":[0],"utilizations":[5]}),
            get_gpu_history=AsyncMock(return_value=[{"collectedAt":now,"gpuIndex":0,"temperatureC":90,"clockSpeedMhz":600,"powerUsageW":150}]),
            get_server_metric_history=AsyncMock(return_value=[{"collectedAt":now,"details":{"ioPsiAvg10":35,"oomKillCount":1}}]),
            get_gpu_processes=AsyncMock(return_value=[]),
            get_active_workloads=AsyncMock(return_value=[]),get_training_jobs=AsyncMock(return_value=[]),
            _get=AsyncMock(return_value={"status":"RESOURCE_PRESSURE","missingEvidence":["Step Time 未上报"]}))
        llm=SimpleNamespace(model="simulated",chat=AsyncMock(side_effect=[SimpleNamespace(content=json.dumps(a,ensure_ascii=False)) for a in answers]))
        return IncidentGraph(platform,llm,None,None,**budgets),platform,llm

    async def test_low_gpu_io_pressure_changes_plan(self):
        graph,p,llm=self.setup_graph([
            decision([{"tool":"runtime_contention","purpose":"低GPU且IO等待，确认并发压力作用域"}]),
            decision([{"tool":"host_history","purpose":"验证IO压力是否持续"}],["Step Time缺失"]),
            decision([],["缺少Step Time，无法确认训练变慢"])])
        report=await graph.run(1,1,"GPU训练速度异常")
        self.assertEqual([t["tool"]["tool"] for t in report["trace"]],["host","gpu","runtime_contention","host_history"])
        self.assertEqual(llm.chat.await_count,3)
        self.assertEqual(report["stopReason"],"NO_NEW_PROBE")
        self.assertIn("无法确认训练变慢",report["missing_evidence"][0])
        self.assertFalse(report["execution_performed"])
        self.simulated_report=report

    async def test_ram_exit_probes_oom_and_processes(self):
        graph,p,_=self.setup_graph([decision([{"tool":"host_history"},{"tool":"gpu_processes"}]),decision([])])
        report=await graph.run(1,1,"RAM压力高任务异常退出")
        p.get_server_metric_history.assert_awaited_once()
        p.get_gpu_processes.assert_awaited_once()
        self.assertFalse(any(t["tool"]["tool"]=="runtime_contention" for t in report["trace"]))

    async def test_temperature_probes_full_clock_power_history(self):
        graph,p,_=self.setup_graph([decision([{"tool":"gpu_history","gpu_index":0}]),decision([])])
        report=await graph.run(1,1,"GPU高温")
        p.get_gpu_history.assert_awaited_once()
        self.assertIn("clockSpeedMhz",report["evidence"][-1]["data"][0])
        self.assertEqual(report["evidence"][-1]["metricScope"],"WHOLE_GPU")

    async def test_forbidden_probe_and_stale_data_retain_gaps(self):
        graph,p,_=self.setup_graph([decision([{"tool":"runtime_contention"}]),decision([])])
        p.get_server_latest_metrics.return_value={"nodeId":1,"collectedAt":"2000-01-01T00:00:00Z","cpuPercent":5}
        response=httpx.Response(403,request=httpx.Request("GET","http://platform"))
        p._get.side_effect=httpx.HTTPStatusError("forbidden",request=response.request,response=response)
        report=await graph.run(1,1,"资源检查")
        self.assertEqual(report["evidence"][0]["dataStatus"],"STALE")
        self.assertEqual(report["evidence"][-1]["dataStatus"],"FORBIDDEN")
        self.assertEqual(report["confirmed_facts"],[])

    async def test_repeat_and_call_budget_stop(self):
        graph,p,llm=self.setup_graph([decision([{"tool":"host"},{"tool":"host_history"}]),decision([{"tool":"host_history"}])],max_calls=2)
        report=await graph.run(1,1,"检查")
        self.assertEqual(report["toolCalls"],2)
        self.assertEqual(report["stopReason"],"BUDGET_EXHAUSTED")
        p.get_server_metric_history.assert_awaited_once()
        self.assertEqual(llm.chat.await_count,2)

    async def test_timeout_preserves_partial_evidence(self):
        graph,p,llm=self.setup_graph([],seconds=.02)
        async def slow(*a,**k):
            import asyncio
            await asyncio.sleep(.1)
        llm.chat.side_effect=slow
        report=await graph.run(1,1,"检查")
        self.assertEqual(report["stopReason"],"TIME_BUDGET")
        self.assertTrue(report["evidence"])

    async def test_cross_gpu_and_arbitrary_commands_rejected(self):
        with self.assertRaises(ValidationError): Probe(tool="shell",purpose="ls")
        with self.assertRaises(ValidationError): Probe(tool="host",server_id=2)
        graph,p,_=self.setup_graph([decision([{"tool":"gpu_history","gpu_index":9}]),decision([])])
        report=await graph.run(1,1,"GPU")
        p.get_gpu_history.assert_not_awaited()
        self.assertEqual(report["evidence"][-1]["dataStatus"],"UNAVAILABLE")

    async def test_simultaneous_readers_only_overlap_no_claim_of_slowdown(self):
        graph,p,_=self.setup_graph([decision([{"tool":"runtime_contention"}]),
            {"possible_causes":[],"missing_evidence":["未上报Step Time"],"suggested_steps":[],"probes":[],"sufficient":False}])
        p._get.return_value={"status":"NO_PRESSURE_OBSERVED","overlappingRuns":[{"status":"RESOURCE_OVERLAP_ONLY"}]}
        report=await graph.run(1,1,"GPU并发任务是否竞争")
        self.assertEqual(report["possible_causes"],[])
        self.assertIn("未上报Step Time",report["missing_evidence"])
        self.assertTrue(any("UNKNOWN" in gap for gap in report["missing_evidence"]))

if __name__=="__main__":
    unittest.main()

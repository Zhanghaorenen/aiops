import unittest
from datetime import datetime,timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock
from app.agents.investigation_tools import InvestigationTools,Probe,compact,data_status

class LiveRegressionTests(unittest.IsolatedAsyncioTestCase):
    async def test_large_contention_keeps_summary_and_missing_evidence(self):
        now=datetime.now(timezone.utc)
        report={"serverId":2,"window":{"latestSampleAt":now.isoformat()},"summary":{"level":"RESOURCE_PRESSURE"},
            "activeTasks":[],"pressureSignals":[{"type":"HOST_CPU_PRESSURE"}],
            "missingEvidence":["ioPsiAvg10_UNKNOWN"],"evidence":[{"privateHugeBody":"x"*50000}]*120,
            "overlappingPairs":[{"runIds":["a","b"],"evidence":[{"large":"x"*50000}]*12}]}
        tools=InvestigationTools(SimpleNamespace(_get=AsyncMock(return_value=report)),2,now)
        data=compact(await tools.execute(Probe(tool="runtime_contention"),None,None))
        self.assertEqual(data["summary"]["level"],"RESOURCE_PRESSURE")
        self.assertEqual(data["missingEvidence"],["ioPsiAvg10_UNKNOWN"])
        self.assertNotIn("evidence",data)
        self.assertNotIn("evidence",data["overlappingPairs"][0])
        self.assertEqual(data_status(data,"runtime_contention",now)[0],"FRESH")

    async def test_old_missing_workloads_are_not_current_candidates(self):
        import uuid
        active=str(uuid.uuid4());old=str(uuid.uuid4())
        platform=SimpleNamespace(get_active_workloads=AsyncMock(return_value=[
            {"runId":active,"fresh":True,"state":"RUNNING"},
            {"runId":old,"fresh":False,"state":"MISSING"}]),get_training_jobs=AsyncMock(return_value=[]))
        tools=InvestigationTools(platform,2,datetime.now(timezone.utc))
        result=await tools.execute(Probe(tool="workloads"),None,None)
        self.assertEqual(tools.runs,{active})
        self.assertEqual(len(result["activeTasks"]),1)

    async def test_host_load_is_normalized_by_actual_core_count(self):
        now=datetime.now(timezone.utc)
        platform=SimpleNamespace(get_server_latest_metrics=AsyncMock(return_value={
            "nodeId":2,"collectedAt":now.isoformat(),"cpuPercent":10,
            "details":{"perCorePercent":[10]*128,"load1":128,"swapPercent":100}}))
        tools=InvestigationTools(platform,2,now)
        data=await tools.execute(Probe(tool="host"),None,None)
        self.assertEqual(data["cpuLogicalCount"],128)
        self.assertEqual(data["load1PerLogicalCpu"],1)
        self.assertTrue(data["interpretationNotes"])

    async def test_invalid_scope_feedback_repairs_plan_within_budget(self):
        from test_autonomous_investigation import AutonomousInvestigationTests,decision
        graph,platform,llm=AutonomousInvestigationTests().setup_graph([
            decision([{"tool":"gpu_history","gpu_index":0}]),
            decision([{"tool":"gpu"}]),
            decision([{"tool":"gpu_history","gpu_index":0}]),
            decision([])])
        report=await graph.run(1,1,"检查资源")
        self.assertEqual([t["tool"]["tool"] for t in report["trace"]],["host","gpu","gpu_history"])
        self.assertEqual(llm.chat.await_count,4)
        platform.get_gpu_history.assert_awaited_once()
        self.assertIn("plan:INVALID_PROBE_ARGUMENTS",report["collection_errors"])

    async def test_report_validation_repairs_only_model_call_without_repeating_tools(self):
        from test_autonomous_investigation import AutonomousInvestigationTests,decision
        graph,platform,llm=AutonomousInvestigationTests().setup_graph([
            decision([],support=("E999",)),decision([])])
        report=await graph.run(1,1,"检查资源")
        self.assertEqual(report["modelRepairCount"],1)
        self.assertEqual(report["toolCalls"],1)
        self.assertEqual(llm.chat.await_count,2)
        platform.get_server_latest_metrics.assert_awaited_once()

    async def test_legacy_database_utc_timestamp_retains_freshness(self):
        now=datetime.now(timezone.utc)
        self.assertEqual(data_status({"sampled_at":now.replace(tzinfo=None).isoformat()},"workload_analysis",now)[0],"FRESH")

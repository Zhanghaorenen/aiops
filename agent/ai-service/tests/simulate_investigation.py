"""Run from repository root with PYTHONPATH=agent/ai-service: python agent/ai-service/tests/simulate_investigation.py"""
import asyncio
import json
from test_autonomous_investigation import AutonomousInvestigationTests

async def main():
    test=AutonomousInvestigationTests("test_low_gpu_io_pressure_changes_plan")
    await test.test_low_gpu_io_pressure_changes_plan()
    report=test.simulated_report
    print(json.dumps({"simulation":True,"scenario":"低GPU利用率与IO压力，缺少Step Time",
        "trace":report["trace"],"possible_causes":report["possible_causes"],
        "missing_evidence":report["missing_evidence"],"stopReason":report["stopReason"]},ensure_ascii=False,indent=2))

if __name__=="__main__":
    asyncio.run(main())

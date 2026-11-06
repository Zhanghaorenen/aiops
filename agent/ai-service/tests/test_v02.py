import json
import unittest
from datetime import datetime, timezone, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock
import httpx
from app.tools.gpu_manager import GPUManager
from app.tools.gpu_tool import GpuTool
from app.agents.incident_graph import IncidentGraph
from app.main import create_app
from app.config import Settings


class GpuIndexTests(unittest.IsolatedAsyncioTestCase):
    def platform(self, at=None):
        snapshot = {
            "serverId": 1,
            "collectionStatus": "AVAILABLE",
            "gpuIndices": [0, 3],
            "gpuUuids": ["GPU-0", "GPU-3"],
            "gpuNames": ["GPU A", "GPU B"],
            "gpuMemoryTotal": [1000, 1000],
            "gpuMemoryUsed": [800, 100],
            "gpuMemoryFree": [200, 900],
            "utilization": [90, 10],
            "temperature": [75, None],
            "collectedAt": at or datetime.now(timezone.utc).isoformat(),
        }
        return SimpleNamespace(
            get_server_gpus=AsyncMock(return_value=snapshot),
            get_gpu_processes=AsyncMock(
                return_value=[
                    {"gpuIndex": 0, "pid": 10, "processName": "train-A"},
                    {"gpuIndex": 3, "pid": 30, "processName": "train-B"},
                ]
            ),
        )

    async def test_non_contiguous_indices_and_processes_are_matched(self):
        platform = self.platform()
        manager = GPUManager(platform)
        tool = GpuTool(manager)
        self.assertEqual((await manager.get_gpu_status(1, 3))["gpu_uuid"], "GPU-3")
        rows = await tool.get_gpu_processes(1)
        self.assertEqual([r["gpu_index"] for r in rows], [0, 3])
        platform.get_gpu_processes.assert_awaited_once()
        self.assertEqual((await tool.get_gpu_processes(1, 3))[0]["pid"], 30)
        self.assertIsNone((await manager.get_gpu_status(1, 3))["temperature"])

    async def test_expired_unknown_and_future_samples_do_not_recommend(self):
        for timestamp in [
            (datetime.now(timezone.utc) - timedelta(minutes=5)).isoformat(),
            "invalid",
            (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat(),
        ]:
            result = await GPUManager(self.platform(timestamp)).recommend_gpu_placement(
                1, 0, 1
            )
            self.assertEqual(result["status"], "stale")
            self.assertEqual(result["recommendations"], [])

    async def test_real_index_required_no_array_position_guess(self):
        platform = self.platform()
        del platform.get_server_gpus.return_value["gpuIndices"]
        with self.assertRaises(ValueError):
            await GPUManager(platform).list_gpus(1)

    async def test_placement_attaches_matching_processes_and_real_history(self):
        platform = self.platform()
        at = datetime.fromisoformat(
            platform.get_server_gpus.return_value["collectedAt"]
        )
        platform.get_gpu_history = AsyncMock(
            return_value=[
                {
                    "gpuIndex": 3,
                    "collectedAt": (at - timedelta(seconds=15)).isoformat(),
                    "utilization": 40,
                },
                {"gpuIndex": 3, "collectedAt": at.isoformat(), "utilization": 10},
            ]
        )
        result = await GPUManager(platform).recommend_gpu_placement(1, 500)
        selected = result["recommendations"][0]
        self.assertEqual(selected["gpu_index"], 3)
        self.assertEqual([p["pid"] for p in selected["processes"]], [30])
        self.assertEqual(selected["historical_load"]["average_utilization_percent"], 25)
        self.assertEqual(selected["historical_load"]["valid_utilization_samples"], 2)
        self.assertEqual(
            selected["historical_load"]["last_collected_at"], at.isoformat()
        )
        platform.get_gpu_processes.assert_awaited_once()
        self.assertEqual(platform.get_gpu_history.await_args.args[:2], (1, 3))
        self.assertFalse(result["execution_performed"])

    async def test_placement_marks_unavailable_evidence_and_propagates_revocation(self):
        platform = self.platform()
        platform.get_gpu_processes.side_effect = httpx.ConnectError("offline")
        platform.get_gpu_history = AsyncMock(side_effect=TimeoutError())
        result = await GPUManager(platform).recommend_gpu_placement(1, 500)
        selected = result["recommendations"][0]
        self.assertIsNone(selected["historical_load"])
        self.assertEqual(selected["process_evidence_status"], "UNAVAILABLE")
        self.assertEqual(
            result["evidence_errors"],
            ["processes:UNAVAILABLE", "history:3:UNAVAILABLE"],
        )
        response = httpx.Response(403, request=httpx.Request("GET", "http://platform"))
        platform.get_gpu_processes.side_effect = httpx.HTTPStatusError(
            "revoked", request=response.request, response=response
        )
        with self.assertRaises(httpx.HTTPStatusError):
            await GPUManager(platform).recommend_gpu_placement(1, 500)


class IncidentGraphTests(unittest.IsolatedAsyncioTestCase):
    def graph(self, missing=None, invalid=False):
        now = datetime.now(timezone.utc).isoformat()
        platform = SimpleNamespace(
            get_server_latest_metrics=AsyncMock(
                return_value={"nodeId": 1, "cpuPercent": 90, "collectedAt": now}
            ),
            get_server_gpus=AsyncMock(
                return_value={
                    "serverId": 1,
                    "collectionStatus": "NO_GPU",
                    "gpuIndices": [],
                    "gpuUuids": [],
                    "collectedAt": now,
                }
            ),
            get_gpu_processes=AsyncMock(return_value=[]),
            get_server_metric_history=AsyncMock(return_value=[]),
        )
        answer = {
            "possible_causes": [
                {
                    "hypothesis": "待核实的资源压力",
                    "supporting_evidence_ids": ["FAKE" if invalid else "E1"],
                    "refuting_evidence_ids": [],
                }
            ],
            "missing_evidence": missing or [],
            "suggested_steps": ["人工核对训练任务配置，不自动操作服务器"],
        }
        llm = SimpleNamespace(
            model="test-model",
            chat=AsyncMock(
                return_value=SimpleNamespace(
                    content=json.dumps(answer, ensure_ascii=False)
                )
            ),
        )
        graph = IncidentGraph(platform, llm, None, None)
        graph.retrieval = SimpleNamespace(
            retrieve=AsyncMock(
                return_value={
                    "results": [
                        {
                            "document_title": "gpu.pdf",
                            "document_id": 9,
                            "page": 3,
                            "chunk_id": "doc9-v2-00000",
                            "content": "排查 CUDA OOM",
                        }
                    ]
                }
            )
        )
        return graph, platform, llm

    async def test_structured_report_has_observations_and_actual_sources(self):
        graph, platform, llm = self.graph()
        report = await graph.run(2, 1, "GPU 问题")
        self.assertEqual(report["server_id"], 1)
        self.assertEqual(report["references"][0]["page"], 3)
        self.assertFalse(report["execution_performed"])
        self.assertEqual(report["evidence"][0]["data"]["freshness"], "FRESH")
        self.assertEqual(llm.chat.await_count, 1)

    async def test_missing_evidence_has_only_one_supplemental_round(self):
        graph, platform, llm = self.graph(["缺少历史指标"])
        result = await graph.run(2, 1, "排查")
        self.assertEqual(llm.chat.await_count, 2)
        platform.get_server_metric_history.assert_awaited_once()
        self.assertEqual(result["missing_evidence"], ["缺少历史指标"])

    async def test_fabricated_evidence_id_is_rejected(self):
        graph, platform, llm = self.graph(invalid=True)
        with self.assertRaisesRegex(ValueError, "UNVERIFIABLE"):
            await graph.run(2, 1, "排查")

    async def test_model_timeout_and_platform_revocation_propagate(self):
        graph, platform, llm = self.graph()
        llm.chat.side_effect = TimeoutError()
        with self.assertRaises(TimeoutError):
            await graph.run(2, 1, "排查")
        graph, platform, llm = self.graph()
        response = httpx.Response(403, request=httpx.Request("GET", "http://platform"))
        platform.get_server_latest_metrics.side_effect = httpx.HTTPStatusError(
            "revoked", request=response.request, response=response
        )
        with self.assertRaises(httpx.HTTPStatusError):
            await graph.run(2, 1, "排查")
        llm.chat.assert_not_awaited()

    async def test_linked_alert_time_drives_gpu_history_window(self):
        graph, platform, llm = self.graph()
        platform.get_gpu_history = AsyncMock(
            return_value=[
                {
                    "gpuIndex": 3,
                    "temperature": 92,
                    "collectedAt": "2026-11-04T01:00:00Z",
                }
            ]
        )
        anchor = "2026-11-04T01:00:00Z"
        report = await graph.run(
            2,
            1,
            "GPU 高温",
            [
                {
                    "id": 7,
                    "gpu_index": 3,
                    "last_observed_at": anchor,
                    "evidence": "真实告警的存储证据（测试夹具）",
                }
            ],
        )
        args = platform.get_gpu_history.await_args.args
        self.assertEqual(args[:2], (1, 3))
        self.assertEqual(
            datetime.fromisoformat(args[3]).replace(tzinfo=None),
            datetime.fromisoformat(anchor.replace("Z", "+00:00")).replace(tzinfo=None),
        )
        self.assertEqual(report["evidence"][0]["kind"], "linked_alerts")
        self.assertTrue(
            any(
                item["kind"] == "gpu_temperature_history" for item in report["evidence"]
            )
        )

    async def test_internal_route_needs_service_key_and_user_identity(self):
        app = create_app(Settings("http://platform", ai_service_key="x" * 32))
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://ai"
        ) as client:
            body = {"incident_id": 2, "server_id": 1, "question": "排查"}
            self.assertEqual(
                (
                    await client.post(
                        "/api/internal/investigate",
                        json=body,
                        headers={"Authorization": "Bearer user"},
                    )
                ).status_code,
                403,
            )
            self.assertEqual(
                (
                    await client.post(
                        "/api/internal/investigate",
                        json=body,
                        headers={"X-AI-Service-Key": "x" * 32},
                    )
                ).status_code,
                401,
            )


if __name__ == "__main__":
    unittest.main()

import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from aiops_agent.__main__ import Agent, AgentStopped
from aiops_agent.client import ApiError, HttpClient, TransientApiError
from aiops_agent.config import AgentConfig, ConfigurationError


def make_config(state_file: Path, **changes) -> AgentConfig:
    values = {
        "backend_url": "http://localhost:8080",
        "interval_seconds": 15,
        "state_file": state_file,
        "enrollment_token": "enroll-token",
        "node_key": "node-1",
        "agent_key": "agent-1",
        "agent_version": "0.1.0",
    }
    values.update(changes)
    return AgentConfig(**values)


class ConfigTests(unittest.TestCase):
    def test_rejects_non_http_url(self):
        with patch.dict("os.environ", {"AIOPS_BACKEND_URL": "ftp://localhost"}):
            with self.assertRaises(ConfigurationError):
                AgentConfig.from_env()

    def test_rejects_too_frequent_sampling(self):
        with patch.dict("os.environ", {"AIOPS_AGENT_INTERVAL_SECONDS": "2"}):
            with self.assertRaises(ConfigurationError):
                AgentConfig.from_env()


class HttpClientTests(unittest.TestCase):
    def test_posts_json_with_bearer_token_and_accepts_empty_response(self):
        response = io.BytesIO(b"")
        with patch("aiops_agent.client.urlopen", return_value=response) as urlopen:
            result = HttpClient("http://localhost:8080/").post(
                "api/agent/heartbeat", {"agentVersion": "0.1.0"}, "secret"
            )
        request = urlopen.call_args.args[0]
        self.assertEqual({}, result)
        self.assertEqual("Bearer secret", request.get_header("Authorization"))
        self.assertEqual("POST", request.get_method())
        self.assertEqual({"agentVersion": "0.1.0"}, json.loads(request.data))

    def test_retries_server_errors_but_rejects_bad_credentials(self):
        from urllib.error import HTTPError

        for status, expected in ((503, TransientApiError), (401, ApiError)):
            response = io.BytesIO(b"unauthorized")
            failure = HTTPError("http://localhost", status, "error", {}, response)
            with self.subTest(status=status), patch(
                "aiops_agent.client.urlopen", side_effect=failure
            ), self.assertRaises(expected):
                HttpClient("http://localhost:8080").post("/api/agent/metrics", {})


class AgentStateTests(unittest.TestCase):
    def test_collect_includes_gpu_status_metrics_and_redacted_process_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            agent = Agent(make_config(Path(directory) / "state.json"))

            class SnapshotStub:
                def get_snapshot(self):
                    return {
                        "collected_at": "2026-10-30T00:00:00+00:00",
                        "system": {
                            "cpu": {"percent": 10.0},
                            "memory": {"total": 1000, "used": 200},
                            "disk": {"total": 2000, "used": 300},
                        },
                        "gpu_status": "AVAILABLE",
                        "gpus": [{
                            "index": 1, "uuid": "GPU-test", "name": "Test GPU",
                            "memory_total_bytes": 8000, "memory_used_bytes": 2000,
                            "memory_free_bytes": 6000, "utilization_percent": 25,
                            "memory_utilization_percent": 30, "temperature_c": 50,
                            "power_w": 75, "processes": [{
                                "pid": 123, "name": "worker", "username": "ops",
                                "gpu_memory_bytes": 1024, "task_type": "compute",
                                "cmdline": "must-not-upload",
                            }],
                        }],
                        "errors": [],
                    }

            agent.snapshot_collector = SnapshotStub()
            payload = agent.collect()

        self.assertEqual(payload["gpuStatus"], "AVAILABLE")
        self.assertEqual(payload["gpus"][0]["gpuIndex"], 1)
        self.assertEqual(payload["gpus"][0]["memoryTotalBytes"], 8000)
        process = payload["gpus"][0]["processes"][0]
        self.assertEqual(process["gpuMemoryUsageBytes"], 1024)
        self.assertNotIn("cmdline", process)
        self.assertNotIn("cmdline", payload["gpus"][0])

    def test_state_is_saved_and_reloaded_with_stable_identifiers(self):
        with tempfile.TemporaryDirectory() as directory:
            state_file = Path(directory) / "nested" / "state.json"
            config = make_config(state_file)
            first = Agent(config)
            first._save_state("agent-credential")

            second = Agent(config)

            self.assertEqual("agent-credential", second.credential)
            self.assertEqual("node-1", second.node_key)
            self.assertEqual("agent-1", second.agent_key)

    def test_invalid_state_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            state_file = Path(directory) / "state.json"
            state_file.write_text("[]", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "Invalid agent state"):
                Agent(make_config(state_file))

    def test_stop_interrupts_retry_without_waiting(self):
        with tempfile.TemporaryDirectory() as directory:
            agent = Agent(make_config(Path(directory) / "state.json"))
            agent.stop()
            with self.assertRaises(AgentStopped):
                agent._retry(lambda: self.fail("stopped agent must not retry"))


if __name__ == "__main__":
    unittest.main()

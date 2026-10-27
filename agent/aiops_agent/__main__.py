from __future__ import annotations

import json
import os
import platform
import random
import signal
import socket
import sys
import threading
import time
import uuid
from datetime import datetime, timezone

import psutil

from .snapshot import HostSnapshot

from .client import ApiError, HttpClient, TransientApiError
from .config import AgentConfig, ConfigurationError


class AgentError(RuntimeError):
    pass


class AgentStopped(AgentError):
    pass


class Agent:
    def __init__(self, config: AgentConfig | None = None, client: HttpClient | None = None):
        self.config = config or AgentConfig.from_env()
        self.client = client or HttpClient(
            self.config.backend_url, self.config.request_timeout_seconds
        )
        self.snapshot_collector = HostSnapshot()
        self.hostname = socket.gethostname()
        self.root = os.environ.get("SystemDrive", "C:") + "\\" if os.name == "nt" else "/"
        self.node_key = self.config.node_key
        self.agent_key = self.config.agent_key
        self.version = self.config.agent_version
        self.credential: str | None = None
        self._stop_event = threading.Event()
        self._load_state()

    def _load_state(self) -> None:
        try:
            state = json.loads(self.config.state_file.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return
        except (OSError, json.JSONDecodeError) as exc:
            raise AgentError(f"Cannot read agent state file {self.config.state_file}: {exc}") from exc
        if not isinstance(state, dict):
            raise AgentError(f"Invalid agent state file: {self.config.state_file}")
        credential = state.get("credential")
        if credential is not None and (not isinstance(credential, str) or not credential.strip()):
            raise AgentError(f"Invalid credential in agent state file: {self.config.state_file}")
        self.credential = credential
        self.agent_key = os.getenv("AIOPS_AGENT_KEY", state.get("agentKey", self.agent_key))
        self.node_key = os.getenv("AIOPS_NODE_KEY", state.get("nodeKey", self.node_key))

    def _save_state(self, credential: str) -> None:
        path = self.config.state_file
        path.parent.mkdir(parents=True, exist_ok=True)
        temp_path = path.with_suffix(path.suffix + ".tmp")
        content = json.dumps({
            "credential": credential,
            "agentKey": self.agent_key,
            "nodeKey": self.node_key,
        }, ensure_ascii=True)
        try:
            temp_path.write_text(content, encoding="utf-8")
            if os.name != "nt":
                temp_path.chmod(0o600)
            temp_path.replace(path)
        except OSError as exc:
            raise AgentError(f"Cannot save agent state file {path}: {exc}") from exc

    def _retry(self, action):
        delay = 1.0
        while not self._stop_event.is_set():
            try:
                return action()
            except (TransientApiError, TimeoutError, ConnectionError, OSError) as exc:
                wait = min(delay, 60.0) * random.uniform(0.8, 1.2)
                print(f"Backend unavailable ({exc}); retrying in {wait:.1f}s", file=sys.stderr)
                if self._stop_event.wait(wait):
                    break
                delay = min(delay * 2, 60.0)
        raise AgentStopped("Agent stopped")

    def enroll(self) -> None:
        enrollment_token = self.config.enrollment_token
        if not enrollment_token:
            raise AgentError("Set AIOPS_ENROLLMENT_TOKEN to enroll this host")
        system = platform.uname()
        memory = psutil.virtual_memory()
        payload = {
            "enrollmentToken": enrollment_token,
            "nodeKey": self.node_key,
            "displayName": self.hostname,
            "hostname": self.hostname,
            "ip": self._local_ip(),
            "osName": system.system,
            "osVersion": system.release,
            "architecture": system.machine,
            "cpuCores": psutil.cpu_count(logical=True) or 1,
            "memoryBytes": memory.total,
            "agentKey": self.agent_key,
            "agentVersion": self.version,
        }
        result = self._retry(lambda: self.client.post("/api/agent/enroll", payload))
        credential = result.get("credential")
        node_id = result.get("nodeId")
        if not isinstance(credential, str) or not credential or node_id is None:
            raise ApiError("Backend enrollment response is missing nodeId or credential")
        self._save_state(credential)
        self.credential = credential
        print(f"Enrolled node {self.node_key} (id={node_id})")

    @staticmethod
    def _local_ip() -> str | None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.connect(("192.0.2.1", 80))
            return sock.getsockname()[0]
        except OSError:
            return None
        finally:
            sock.close()

    def collect(self) -> dict:
        snapshot = self.snapshot_collector.get_snapshot()
        system = snapshot["system"]

        if not system:
            raise AgentError("系统指标采集失败")

        if (
            system["memory"]["total"] <= 0
            or system["disk"]["total"] <= 0
        ):
            raise AgentError("系统指标无效，停止本次上报")

        self.last_snapshot = snapshot

        return {
            "cpuPercent": system["cpu"]["percent"],
            "memoryTotalBytes": system["memory"]["total"],
            "memoryUsedBytes": system["memory"]["used"],
            "diskTotalBytes": system["disk"]["total"],
            "diskUsedBytes": system["disk"]["used"],
            "collectedAt": snapshot["collected_at"],
        }

    def run(self) -> None:
        if not self.credential:
            self.enroll()
        print(f"Monitoring {self.hostname} every {self.config.interval_seconds}s")
        next_sample = time.monotonic()
        while not self._stop_event.is_set():
            self._retry(lambda: self.client.post(
                "/api/agent/metrics", self.collect(), self.credential
            ))
            self._retry(lambda: self.client.post(
                "/api/agent/heartbeat", {"agentVersion": self.version}, self.credential
            ))
            next_sample += self.config.interval_seconds
            delay = max(0.0, next_sample - time.monotonic())
            if delay == 0:
                next_sample = time.monotonic()
            self._stop_event.wait(delay)

    def stop(self, *_args) -> None:
        self._stop_event.set()


def main() -> int:
    try:
        agent = Agent()
        signal.signal(signal.SIGINT, agent.stop)
        if hasattr(signal, "SIGTERM"):
            signal.signal(signal.SIGTERM, agent.stop)
        agent.run()
        return 0
    except AgentStopped:
        return 0
    except (AgentError, ApiError, ConfigurationError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    raise SystemExit(main())

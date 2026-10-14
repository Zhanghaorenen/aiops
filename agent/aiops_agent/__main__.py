from __future__ import annotations

import json
import os
import platform
import random
import signal
import socket
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

import psutil


class AgentError(Exception):
    pass


class ApiError(AgentError):
    pass


class Agent:
    def __init__(self) -> None:
        self.base_url = os.getenv("AIOPS_BACKEND_URL", "http://localhost:8080").rstrip("/")
        self.interval = max(5, int(os.getenv("AIOPS_AGENT_INTERVAL_SECONDS", "15")))
        self.state_path = Path(os.getenv(
            "AIOPS_AGENT_STATE_FILE", str(Path.home() / ".aiops-agent" / "state.json")
        )).expanduser()
        self.hostname = socket.gethostname()
        self.root = os.environ.get("SystemDrive", "C:") + "\\" if os.name == "nt" else "/"
        self.node_key = os.getenv("AIOPS_NODE_KEY", f"{self.hostname}-{uuid.getnode():012x}")
        self.agent_key = os.getenv("AIOPS_AGENT_KEY", str(uuid.uuid4()))
        self.version = os.getenv("AIOPS_AGENT_VERSION", "0.1.0")
        self.credential: str | None = None
        self._running = True
        self._load_state()

    def _load_state(self) -> None:
        try:
            state = json.loads(self.state_path.read_text(encoding="utf-8"))
            self.credential = state.get("credential")
            self.agent_key = os.getenv("AIOPS_AGENT_KEY", state.get("agentKey", self.agent_key))
            self.node_key = os.getenv("AIOPS_NODE_KEY", state.get("nodeKey", self.node_key))
        except FileNotFoundError:
            return
        except (OSError, ValueError) as exc:
            raise AgentError(f"Cannot read agent state file {self.state_path}: {exc}") from exc

    def _save_state(self, credential: str) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        temp_path = self.state_path.with_suffix(self.state_path.suffix + ".tmp")
        content = json.dumps({"credential": credential, "agentKey": self.agent_key,
                              "nodeKey": self.node_key}, ensure_ascii=True)
        temp_path.write_text(content, encoding="utf-8")
        try:
            temp_path.chmod(0o600)
        except OSError:
            pass
        temp_path.replace(self.state_path)

    def _request(self, path: str, body: dict, token: str | None = None) -> dict:
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = Request(f"{self.base_url}{path}", data=json.dumps(body).encode("utf-8"),
                          headers=headers, method="POST")
        try:
            with urlopen(request, timeout=10) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except HTTPError as exc:
            message = exc.read().decode("utf-8", errors="replace")[:300]
            if exc.code >= 500 or exc.code == 429:
                raise URLError(f"Backend returned HTTP {exc.code}") from exc
            raise ApiError(f"Backend rejected {path}: HTTP {exc.code} {message}") from exc

    def _retry(self, action):
        delay = 1.0
        while self._running:
            try:
                return action()
            except (URLError, TimeoutError, ConnectionError, OSError) as exc:
                wait = min(delay, 60.0) * random.uniform(0.8, 1.2)
                print(f"Backend unavailable ({exc}); retrying in {wait:.1f}s", file=sys.stderr)
                time.sleep(wait)
                delay = min(delay * 2, 60.0)
        raise AgentError("Agent stopped")

    def enroll(self) -> None:
        enrollment_token = os.getenv("AIOPS_ENROLLMENT_TOKEN")
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
        result = self._retry(lambda: self._request("/api/agent/enroll", payload))
        self.credential = result["credential"]
        self._save_state(self.credential)
        print(f"Enrolled node {self.node_key} (id={result['nodeId']})")

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
        memory = psutil.virtual_memory()
        disk = psutil.disk_usage(getattr(self, "root", "/"))
        return {
            "cpuPercent": psutil.cpu_percent(interval=1),
            "memoryTotalBytes": memory.total,
            "memoryUsedBytes": memory.used,
            "diskTotalBytes": disk.total,
            "diskUsedBytes": disk.used,
            "collectedAt": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        }

    def run(self) -> None:
        signal.signal(signal.SIGINT, self._stop)
        if hasattr(signal, "SIGTERM"):
            signal.signal(signal.SIGTERM, self._stop)
        if not self.credential:
            self.enroll()
        print(f"Monitoring {self.hostname} every {self.interval}s")
        while self._running:
            self._retry(lambda: self._request("/api/agent/metrics", self.collect(), self.credential))
            self._retry(lambda: self._request("/api/agent/heartbeat", {"agentVersion": self.version}, self.credential))
            for _ in range(self.interval):
                if not self._running:
                    break
                time.sleep(1)

    def _stop(self, *_args) -> None:
        self._running = False


def main() -> int:
    try:
        Agent().run()
        return 0
    except (AgentError, ApiError, KeyError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    raise SystemExit(main())

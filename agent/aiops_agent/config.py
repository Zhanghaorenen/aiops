from __future__ import annotations

import os
import socket
import uuid
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit


class ConfigurationError(ValueError):
    """Raised when an Agent setting is missing or invalid."""


@dataclass(frozen=True)
class AgentConfig:
    backend_url: str
    interval_seconds: int
    state_file: Path
    enrollment_token: str | None
    node_key: str
    agent_key: str
    agent_version: str
    request_timeout_seconds: float = 10.0

    @classmethod
    def from_env(cls) -> AgentConfig:
        backend_url = os.getenv("AIOPS_BACKEND_URL", "http://localhost:8080").strip().rstrip("/")
        parsed_url = urlsplit(backend_url)
        if parsed_url.scheme not in {"http", "https"} or not parsed_url.netloc:
            raise ConfigurationError("AIOPS_BACKEND_URL must be an HTTP or HTTPS URL")

        try:
            interval = int(os.getenv("AIOPS_AGENT_INTERVAL_SECONDS", "15"))
        except ValueError as exc:
            raise ConfigurationError("AIOPS_AGENT_INTERVAL_SECONDS must be an integer") from exc
        if interval < 5:
            raise ConfigurationError("AIOPS_AGENT_INTERVAL_SECONDS must be at least 5")

        hostname = socket.gethostname()
        state_file = Path(os.getenv(
            "AIOPS_AGENT_STATE_FILE", str(Path.home() / ".aiops-agent" / "state.json")
        )).expanduser()
        return cls(
            backend_url=backend_url,
            interval_seconds=interval,
            state_file=state_file,
            enrollment_token=os.getenv("AIOPS_ENROLLMENT_TOKEN"),
            node_key=os.getenv("AIOPS_NODE_KEY", f"{hostname}-{uuid.getnode():012x}"),
            agent_key=os.getenv("AIOPS_AGENT_KEY", str(uuid.uuid4())),
            agent_version=os.getenv("AIOPS_AGENT_VERSION", "0.1.0"),
        )

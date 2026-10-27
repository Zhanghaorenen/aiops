"""Central-service configuration. Secrets are read only from the environment."""

import os
from dataclasses import dataclass, field
from urllib.parse import urlsplit


def http_url(value: str, name: str) -> str:
    try:
        parsed = urlsplit(value)
        valid = parsed.scheme in {"http", "https"} and parsed.hostname and parsed.port != 0
    except ValueError:
        valid = False
    if not valid or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError(f"{name} must be an HTTP(S) URL without credentials, query or fragment")
    return value.rstrip("/")


@dataclass(frozen=True)
class Settings:
    backend_url: str
    llm_base_url: str = ""
    llm_model: str = ""
    llm_api_key: str = field(default="", repr=False)
    backend_timeout: float = 10.0
    llm_timeout: float = 30.0
    chat_timeout: float = 90.0
    max_rounds: int = 4

    @classmethod
    def from_env(cls):
        llm_url = os.getenv("AIOPS_LLM_BASE_URL", "").strip()
        return cls(
            backend_url=http_url(os.getenv("AIOPS_BACKEND_URL", "http://127.0.0.1:8080").strip(), "AIOPS_BACKEND_URL"),
            llm_base_url=http_url(llm_url, "AIOPS_LLM_BASE_URL") if llm_url else "",
            llm_model=os.getenv("AIOPS_LLM_MODEL", "").strip(),
            llm_api_key=os.getenv("AIOPS_LLM_API_KEY", "").strip(),
        )

    @property
    def llm_configured(self) -> bool:
        return bool(self.llm_base_url and self.llm_model and self.llm_api_key)

    def require_llm(self) -> None:
        if not self.llm_configured:
            raise ValueError("请配置 AIOPS_LLM_BASE_URL、AIOPS_LLM_MODEL、AIOPS_LLM_API_KEY")

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
    rag_service_key: str = field(default="", repr=False)
    ai_service_key: str = field(default="", repr=False)
    rag_storage_dir: str = "data/knowledge"
    embedding_path: str = ""
    embedding_version: str = ""
    qdrant_url: str = "http://127.0.0.1:6333"
    qdrant_key: str = field(default="", repr=False)
    rag_chunk_size: int = 1000
    rag_chunk_overlap: int = 150

    embedding_device: str = 'cpu'
    embedding_batch_size: int = 16
    rag_vector_schema_version: str = '2'

    def __post_init__(self):
        import re
        if not re.fullmatch(r'cpu|cuda(?::[0-9]+)?|mps', self.embedding_device):
            raise ValueError('INVALID_EMBEDDING_DEVICE')
        if not 1 <= self.embedding_batch_size <= 256: raise ValueError('INVALID_EMBEDDING_BATCH_SIZE')
        if not re.fullmatch(r'[1-9][0-9]{0,3}', self.rag_vector_schema_version) or int(self.rag_vector_schema_version)<2: raise ValueError('INVALID_VECTOR_SCHEMA_VERSION')
        if not 100 <= self.rag_chunk_size <= 1600 or not 0 <= self.rag_chunk_overlap < self.rag_chunk_size:
            raise ValueError('INVALID_CHUNK_CONFIGURATION')
        if not self.rag_storage_dir.strip(): raise ValueError('INVALID_RAG_STORAGE_DIR')
        if len(self.embedding_version)>100: raise ValueError('INVALID_EMBEDDING_VERSION')

    @classmethod
    def from_env(cls):
        llm_url = os.getenv("AIOPS_LLM_BASE_URL", "").strip()
        return cls(
            backend_url=http_url(os.getenv("AIOPS_BACKEND_URL", "http://127.0.0.1:8080").strip(), "AIOPS_BACKEND_URL"),
            llm_base_url=http_url(llm_url, "AIOPS_LLM_BASE_URL") if llm_url else "",
            llm_model=os.getenv("AIOPS_LLM_MODEL", "").strip(),
            ai_service_key=os.getenv("AIOPS_AI_SERVICE_KEY", ""),
            rag_service_key=os.getenv("AIOPS_RAG_SERVICE_KEY", ""),
            rag_storage_dir=os.getenv("AIOPS_RAG_STORAGE_DIR", "data/knowledge"),
            embedding_path=os.getenv("AIOPS_EMBEDDING_PATH", ""),
            embedding_version=os.getenv("AIOPS_EMBEDDING_VERSION", ""),
            qdrant_url=http_url(os.getenv("AIOPS_QDRANT_URL", "http://127.0.0.1:6333"), "AIOPS_QDRANT_URL"),
            qdrant_key=os.getenv("AIOPS_QDRANT_KEY", ""),
            embedding_device=os.getenv('AIOPS_EMBEDDING_DEVICE', 'cpu'),
            embedding_batch_size=int(os.getenv('AIOPS_EMBEDDING_BATCH_SIZE', '16')),
            rag_vector_schema_version=os.getenv('AIOPS_RAG_VECTOR_SCHEMA_VERSION', '2'),
            rag_chunk_size=int(os.getenv("AIOPS_RAG_CHUNK_SIZE", "1000")),
            rag_chunk_overlap=int(os.getenv("AIOPS_RAG_CHUNK_OVERLAP", "150")),
            llm_api_key=os.getenv("AIOPS_LLM_API_KEY", "").strip(),
        )

    @property
    def llm_configured(self) -> bool:
        return bool(self.llm_base_url and self.llm_model and self.llm_api_key)

    def require_llm(self) -> None:
        if not self.llm_configured:
            raise ValueError("请配置 AIOPS_LLM_BASE_URL、AIOPS_LLM_MODEL、AIOPS_LLM_API_KEY")

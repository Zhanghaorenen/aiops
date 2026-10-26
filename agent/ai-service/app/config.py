import os
from dataclasses import dataclass

@dataclass(frozen=True)
class Settings:
    backend_url: str
    llm_base_url: str
    llm_model: str  
    llm_api_key: str

    @classmethod
    def from_env(cls):
        return cls(
            backend_url=os.getenv(
                "AIOPS_BACKEND_URL",
                "http://localhost:8080"
            )
            model=os.getenv(
                "AIOPS_LLM_MODEL").strip(),
            if not base_url or not model:
                raise RuntimeError(
                    "缺少环境变量: AIOPS_LLM_BASE_URL 或 AIOPS_LLM_MODEL"
                )
            
            llm_base_url=os.environ["AIOPS_LLM_BASE_URL"],
            llm_model=os.environ["AIOPS_LLM_MODEL"],
            llm_api_key=os.getenv(
                "AIOPS_LLM_API_KEY",
                "EMPTY"
            )
            return cls(
                "backend_url": backend_url,
                "model":model,
                "llm_base_url": base_url,
                "llm_model": model,
                "llm_api_key": api_key
            )
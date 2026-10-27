from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api.chat import router as chat_router
from .client.platform_client import PlatformClient
from .config import Settings
from .llm.client import LLMClient


def create_app(settings: Settings | None = None, platform_factory=PlatformClient,
               llm_factory=LLMClient) -> FastAPI:
    application = FastAPI(title="AIOps AI Service", version="0.1.0", description="Central read-only diagnostics")
    application.state.settings = settings
    application.state.platform_factory = platform_factory
    application.state.llm_factory = llm_factory
    import os
    origin = os.getenv("AIOPS_WEB_ALLOWED_ORIGIN", "http://localhost:5173")
    application.add_middleware(CORSMiddleware, allow_origins=[origin], allow_methods=["GET", "POST"],
                               allow_headers=["Authorization", "Content-Type"])
    application.include_router(chat_router)

    @application.get("/api/ai/health")
    def health():
        try:
            configuration = settings or Settings.from_env()
            return {"status": "UP", "service": "aiops-ai-service", "llmConfigured": configuration.llm_configured}
        except ValueError:
            return {"status": "DEGRADED", "service": "aiops-ai-service", "llmConfigured": False}

    return application


app = create_app()

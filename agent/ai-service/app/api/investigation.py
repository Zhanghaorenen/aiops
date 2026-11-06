import asyncio
import secrets
from contextlib import AsyncExitStack
from fastapi import APIRouter, Depends, Header, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials
from pydantic import BaseModel, Field
import httpx
from .chat import auth_scheme, get_settings
from .knowledge import resources
from ..agents.incident_graph import IncidentGraph

router = APIRouter(prefix="/api/internal", tags=["受控事件调查"])


class InvestigationRequest(BaseModel):
    incident_id: int = Field(gt=0)
    server_id: int = Field(gt=0)
    question: str = Field(min_length=1, max_length=2400)
    alert_evidence: list[dict] = Field(default_factory=list, max_length=20)


@router.post("/investigate")
async def investigate(
    payload: InvestigationRequest,
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(auth_scheme),
    service_key: str | None = Header(default=None, alias="X-AI-Service-Key"),
):
    settings = get_settings(request)
    if len(settings.ai_service_key) < 32 or not secrets.compare_digest(
        service_key or "", settings.ai_service_key
    ):
        raise HTTPException(403, "需要独立的调查服务凭据")
    if credentials is None:
        raise HTTPException(401, "需要发起调查的用户身份")
    async with AsyncExitStack() as stack:
        platform = request.app.state.platform_factory(
            settings.backend_url, credentials.credentials, settings.backend_timeout
        )
        stack.push_async_callback(platform.close)
        try:
            await platform.get_current_user()
            # 对固定目标先核权；服务密钥不能代替用户的服务器访问权限。
            await platform.get_server(payload.server_id)
            settings.require_llm()
            llm = request.app.state.llm_factory(
                settings.llm_model,
                settings.llm_base_url,
                settings.llm_api_key,
                settings.llm_timeout,
            )
            stack.push_async_callback(llm.close)
            embedding, store = resources(request, settings)
            return await asyncio.wait_for(
                IncidentGraph(platform, llm, embedding, store).run(
                    payload.incident_id,
                    payload.server_id,
                    payload.question,
                    payload.alert_evidence,
                ),
                timeout=150,
            )
        except httpx.HTTPStatusError as exc:
            raise HTTPException(
                exc.response.status_code
                if exc.response.status_code in (401, 403, 404)
                else 502,
                "平台拒绝调查请求或依赖异常",
            ) from exc
        except TimeoutError as exc:
            raise HTTPException(504, "调查超时") from exc
        except Exception as exc:
            raise HTTPException(
                502, "调查未完成，请检查模型配置、监控证据和检索服务"
            ) from exc

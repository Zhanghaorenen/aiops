import asyncio
import json
import logging
from contextlib import AsyncExitStack
from typing import Annotated

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from openai import APIError, APITimeoutError

from ..agents.graph_agent import GraphAgent
from ..agents.diagnosis_agent import ToolRoundLimitError
from ..client.platform_client import PlatformProtocolError
from ..config import Settings
from ..llm.client import ModelResponseError
from ..schemas.chat import ChatRequest, ChatResponse
from ..tools.server_manager import ServerManager
from ..tools.server_tool import ServerTool
from ..tools.gpu_manager import GPUManager
from ..tools.gpu_tool import GpuTool

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api", tags=["AI 对话"])
auth_scheme = HTTPBearer(auto_error=False)


def get_settings(request: Request) -> Settings:
    try:
        return request.app.state.settings or Settings.from_env()
    except ValueError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@router.post("/chat", response_model=ChatResponse)
async def chat(payload: ChatRequest, request: Request,
               credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(auth_scheme)]):
    if credentials is None:
        raise HTTPException(status_code=401, detail="需要用户登录凭证", headers={"WWW-Authenticate": "Bearer"})
    settings = get_settings(request)
    async with AsyncExitStack() as stack:
        platform = request.app.state.platform_factory(settings.backend_url, credentials.credentials, settings.backend_timeout)
        stack.push_async_callback(platform.close)
        try:
            await platform.get_current_user()
            try:
                settings.require_llm()
            except ValueError as exc:
                raise HTTPException(status_code=503, detail=str(exc)) from exc
            llm = request.app.state.llm_factory(settings.llm_model, settings.llm_base_url,
                                               settings.llm_api_key, settings.llm_timeout)
            stack.push_async_callback(llm.close)
            agent = GraphAgent(
                llm,
                ServerTool(ServerManager(platform)),
                GpuTool(GPUManager(platform)),
                settings.max_rounds,
            )
            answer = await asyncio.wait_for(agent.answer(payload.question, [item.model_dump() for item in payload.history]), timeout=settings.chat_timeout)
            return ChatResponse(answer=answer)
        except httpx.HTTPStatusError as exc:
            status = exc.response.status_code
            if status in (401, 403):
                raise HTTPException(status_code=status, detail="用户凭证无效、已过期或无权访问") from exc
            raise HTTPException(status_code=502, detail="平台服务响应异常") from exc
        except (httpx.TimeoutException, APITimeoutError, TimeoutError) as exc:
            raise HTTPException(status_code=504, detail="查询或模型响应超时，请稍后重试") from exc
        except httpx.RequestError as exc:
            raise HTTPException(status_code=502, detail="无法连接平台服务") from exc
        except ToolRoundLimitError as exc:
            raise HTTPException(status_code=508, detail=str(exc)) from exc
        except (APIError, ModelResponseError, PlatformProtocolError) as exc:
            logger.warning("Chat dependency failed (%s)", type(exc).__name__)
            raise HTTPException(status_code=502, detail="模型或平台响应异常，请稍后重试") from exc


def _sse(event: dict) -> str:
    return f"data: {json.dumps(event, ensure_ascii=False, separators=(',', ':'))}\n\n"


@router.post("/chat/stream")
async def chat_stream(payload: ChatRequest, request: Request,
                      credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(auth_scheme)]):
    if credentials is None:
        raise HTTPException(status_code=401, detail="需要用户登录凭证",
                            headers={"WWW-Authenticate": "Bearer"})
    settings = get_settings(request)
    try:
        settings.require_llm()
    except ValueError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc

    async def event_stream():
        async with AsyncExitStack() as stack:
            platform = request.app.state.platform_factory(
                settings.backend_url, credentials.credentials, settings.backend_timeout
            )
            stack.push_async_callback(platform.close)
            try:
                await platform.get_current_user()
                llm = request.app.state.llm_factory(
                    settings.llm_model, settings.llm_base_url,
                    settings.llm_api_key, settings.llm_timeout,
                )
                stack.push_async_callback(llm.close)
                agent = GraphAgent(
                    llm, ServerTool(ServerManager(platform)),
                    GpuTool(GPUManager(platform)), settings.max_rounds,
                )
                import anyio
                with anyio.fail_after(settings.chat_timeout):
                    async for event in agent.astream(
                            payload.question,
                            [item.model_dump() for item in payload.history]):
                        yield _sse(event)
                yield _sse({"type": "done"})
            except httpx.HTTPStatusError as exc:
                status = exc.response.status_code
                message = ("用户登录凭证无效或已过期" if status == 401 else
                           "没有权限访问该服务器" if status == 403 else "平台服务响应异常")
                yield _sse({"type": "error", "status": status, "message": message})
            except (httpx.TimeoutException, APITimeoutError, TimeoutError):
                yield _sse({"type": "error", "status": 504, "message": "查询或模型响应超时"})
            except httpx.RequestError:
                yield _sse({"type": "error", "status": 502, "message": "无法连接平台服务"})
            except ToolRoundLimitError as exc:
                yield _sse({"type": "error", "status": 508, "message": str(exc)})
            except (APIError, ModelResponseError, PlatformProtocolError) as exc:
                logger.warning("Streaming chat failed (%s)", type(exc).__name__)
                yield _sse({"type": "error", "status": 502, "message": "模型或平台响应异常"})
            except Exception as exc:
                logger.warning("Streaming chat failed (%s)", type(exc).__name__)
                yield _sse({"type": "error", "status": 500, "message": "智能体执行失败"})

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )

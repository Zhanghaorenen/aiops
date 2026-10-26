import logging
from typing import Annotated

import httpx

from fastapi import (
    APIRouter,
    Depends,
    HTTPException
)
from fastapi.security import (
    HTTPAuthorizationCredentials,
    HTTPBearer
)
from openai import OpenAIError

from ..config import Settings
from ..shcemas.chat import ChatRequest, ChatResponse
from ..client.platform_client import PlatformClient
from ..tools.server_manager import ServerManager
from ..tools.server_tool import ServerTool
from ..llm.client import LLMClient
from ..agents.diagnosis_agent import DiagnosisAgent


logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api",
    tags=["AI 对话"]
)

auth_scheme = HTTPBearer(auto_error=True)


@router.post(
    "/chat",
    response_model=ChatResponse
)
async def chat(
    payload: ChatRequest,
    credentials: Annotated[
        HTTPAuthorizationCredentials,
        Depends(auth_scheme)
    ]
):

    # ① 加载配置
    try:
        config = Settings.from_env()
    except ValueError as exc:
        raise HTTPException(
            status_code=503,
            detail=str(exc)
        ) from exc

    # ② 使用当前请求的 JWT 创建中心平台客户端
    platform = PlatformClient(
        config.backend_url,
        credentials.credentials
    )

    llm = None

    try:
        # ③ 验证 JWT 是否有效
        # HTTPBearer 只负责提取令牌，不验证令牌真伪
        try:
            await platform.get_current_user()

        except httpx.HTTPStatusError as exc:
            status = exc.response.status_code

            if status in (401, 403):
                raise HTTPException(
                    status_code=status,
                    detail="未授权访问"
                ) from exc

            raise HTTPException(
                status_code=502,
                detail="认证服务响应异常"
            ) from exc

        except httpx.RequestError as exc:
            raise HTTPException(
                status_code=502,
                detail="无法连接认证服务"
            ) from exc

        # ④ 构造 AI 调用链
        llm = LLMClient(
            model=config.llm_model,
            base_url=config.llm_base_url,
            api_key=config.llm_api_key
        )

        manager = ServerManager(platform)

        tools = ServerTool(manager)

        agent = DiagnosisAgent(
            llm=llm,
            server_tools=tools
        )

        # ⑤ 调用你的智能体
        answer = await agent.answer(
            payload.question,
            history=[
                item.model_dump()
                for item in payload.history
            ]
        )

        return ChatResponse(
            answer=answer
        )

    except OpenAIError as exc:
        logger.warning(
            "模型服务调用失败：%s",
            type(exc).__name__
        )

        raise HTTPException(
            status_code=502,
            detail="大模型服务调用失败"
        ) from exc

    except RuntimeError as exc:
        logger.warning(
            "智能体处理失败：%s", exc
        )

        raise HTTPException(
            status_code=502,
            detail="智能体处理失败"
        ) from exc

    finally:
        # ⑥ 释放资源
        if llm is not None:
            await llm.close()

        await platform.close()
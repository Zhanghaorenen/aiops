"""一次生成对应一份数据库租约，失败和断线不得留下成功回答。"""
import json
import logging
from contextlib import asynccontextmanager

import anyio

logger = logging.getLogger(__name__)


class SessionTurn:
    def __init__(self, platform, payload):
        self.platform, self.payload = platform, payload
        self.history = [message.model_dump() for message in payload.history]
        self.replay = None
        self.completed = False
        self.started = False
        self.lease = None

    async def begin(self):
        if self.payload.session_id:
            data = await self.platform.begin_chat_turn(self.payload.session_id, self.payload.request_id, self.payload.question)
            self.history = data['history']
            self.replay = data.get('replay')
            self.lease = data.get('lease')
            self.started = self.replay is None

    async def complete(self, answer, sources=None, resource_results=None):
        if not isinstance(answer, str) or not answer.strip() or len(answer) > 8000:
            raise ValueError("无效或过长的回答不能作为成功消息保存")
        if self.started:
            await self.platform.finish_chat_turn(self.payload.session_id, self.payload.request_id, 'COMPLETED', answer,
                                                 json.dumps({'sources': sources or [], 'resourceResults': resource_results or []}, ensure_ascii=False), lease=self.lease)
        self.completed = True


@asynccontextmanager
async def session_turn(platform, payload):
    turn = SessionTurn(platform, payload)
    await turn.begin()
    try:
        yield turn
    finally:
        if turn.started and not turn.completed:
            # ASGI 断线会取消整个任务；单独保护这次有限时的状态回写。
            with anyio.CancelScope(shield=True):
                with anyio.move_on_after(5):
                    try:
                        await platform.finish_chat_turn(payload.session_id, payload.request_id, 'INTERRUPTED', lease=turn.lease)
                    except Exception:
                        logger.warning('Unable to mark interrupted chat; lease expiry will recover it')

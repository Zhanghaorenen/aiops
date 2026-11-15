"""Per-process pooled connection; global sliding-window limits use Redis server time."""
import asyncio
import hashlib
import math
import uuid

from fastapi import HTTPException
from redis.asyncio import Redis
from redis.backoff import NoBackoff
from redis.asyncio.retry import Retry

RATE_SCRIPT = """
local time = redis.call('TIME')
local now = tonumber(time[1])*1000 + math.floor(tonumber(time[2])/1000)
local window = 60000
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now-window)
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[1]) then
  local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
  return math.max(1, math.ceil((tonumber(oldest[2])+window-now)/1000))
end
redis.call('ZADD', KEYS[1], now, ARGV[2])
redis.call('PEXPIRE', KEYS[1], window)
return 0
"""


class ChatRateLimiter:
    def __init__(self, settings):
        self.enabled = settings.redis_enabled
        self.prefix = settings.redis_key_prefix
        self.limit = settings.chat_rate_per_minute
        self.client = Redis(
            host=settings.redis_host, port=settings.redis_port, db=settings.redis_db,
            password=settings.redis_password or None, decode_responses=True,
            socket_connect_timeout=settings.redis_timeout, socket_timeout=settings.redis_timeout,
            max_connections=32, retry=Retry(NoBackoff(), 0), health_check_interval=30,
        ) if self.enabled else None
        self.allowed = self.limited = self.unavailable = 0

    async def check(self, user):
        # The identity must come from /api/auth/me, never request JSON or an unverified JWT.
        identity = user.get('id')
        if type(identity) is not int or identity <= 0:
            raise HTTPException(502, '平台未返回有效用户身份')
        if not self.enabled:
            return
        key = self.prefix + 'chat:rate:' + hashlib.sha256(str(identity).encode()).hexdigest()
        try:
            async with asyncio.timeout(2):
                retry = int(await self.client.eval(RATE_SCRIPT, 1, key, self.limit, uuid.uuid4().hex))
        except Exception as exc:
            # Fail closed across ALL workers; no unbounded per-process fallback on outages.
            self.unavailable += 1
            raise HTTPException(503, 'Redis 限流服务不可用，暂缓生成，请稍后重试',
                                headers={'Retry-After': '5'}) from exc
        if retry:
            self.limited += 1
            raise HTTPException(429, '每用户对话请求过于频繁，请稍后重试',
                                headers={'Retry-After': str(max(1, math.ceil(retry)))})
        self.allowed += 1

    async def stats(self):
        status = 'DISABLED'
        if self.enabled:
            try:
                async with asyncio.timeout(2):
                    status = 'UP' if await self.client.ping() else 'DOWN'
            except Exception:
                status = 'DOWN'
        return {'status': status, 'allowed': self.allowed, 'limited': self.limited,
                'unavailable': self.unavailable, 'perUserPerMinute': self.limit,
                'outagePolicy': 'FAIL_CLOSED_503', 'counterScope': 'process'}

    async def close(self):
        if self.client:
            await self.client.aclose()

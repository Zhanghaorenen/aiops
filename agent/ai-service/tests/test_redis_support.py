import asyncio
import os
import unittest
import uuid
import hashlib
from dataclasses import replace
from unittest.mock import AsyncMock

import httpx
from fastapi import HTTPException

from app.config import Settings
from app.main import create_app
from app.redis_support import ChatRateLimiter
from app.client.platform_client import PlatformClient


class RateLimitTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_connection_failure_returns_503(self):
        limiter=ChatRateLimiter(Settings('http://platform', redis_port=1, redis_timeout=.1))
        try:
            with self.assertRaises(HTTPException) as blocked:
                await limiter.check({'id': 71})
            self.assertEqual(blocked.exception.status_code,503)
            self.assertEqual((await limiter.stats())['status'],'DOWN')
        finally:
            await limiter.close()

    async def test_redis_failure_closes_gate_and_invalid_identity_never_hits_redis(self):
        limiter = ChatRateLimiter(Settings('http://platform'))
        await limiter.client.aclose()
        limiter.client = AsyncMock()
        limiter.client.eval.side_effect = ConnectionError('offline')
        with self.assertRaises(HTTPException) as blocked:
            await limiter.check({'id': 71})
        self.assertEqual(blocked.exception.status_code, 503)
        self.assertEqual(blocked.exception.headers['Retry-After'], '5')
        with self.assertRaises(HTTPException) as invalid:
            await limiter.check({'user_id': 71})
        self.assertEqual(invalid.exception.status_code, 502)
        self.assertEqual(limiter.client.eval.await_count, 1)
        self.assertEqual((await limiter.stats())['unavailable'], 1)

    async def test_both_routes_return_http_429_before_stream_headers_after_authentication(self):
        settings = Settings('http://platform', 'http://llm', 'model', 'not-a-real-key')
        seen = []
        def backend(request):
            seen.append(request.headers['Authorization'])
            if request.headers['Authorization'] == 'Bearer invalid':
                return httpx.Response(401)
            return httpx.Response(200, json={'id': 71})
        app = create_app(settings, lambda url, token, timeout: PlatformClient(
            url, token, timeout, transport=httpx.MockTransport(backend)),
            lambda *_: self.fail('Rate-limited request must not invoke LLM'))
        limiter = ChatRateLimiter(settings)
        await limiter.client.aclose()
        limiter.client = AsyncMock()
        limiter.client.eval.return_value = 17
        app.state.chat_limiter = limiter
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://ai') as client:
            for route in ('/api/chat', '/api/chat/stream'):
                response = await client.post(route, json={'question': 'q'}, headers={'Authorization': 'Bearer valid'})
                self.assertEqual(response.status_code, 429)
                self.assertEqual(response.headers['Retry-After'], '17')
                self.assertNotIn('text/event-stream', response.headers.get('content-type', ''))
                invalid = await client.post(route, json={'question': 'q'}, headers={'Authorization': 'Bearer invalid'})
                self.assertEqual(invalid.status_code, 401)
            self.assertEqual(limiter.client.eval.await_count, 2)
            missing = await client.post('/api/chat/stream', json={'question': 'q'})
            self.assertEqual(missing.status_code, 401)
        self.assertEqual(limiter.limited, 2)


@unittest.skipUnless(os.getenv('AIOPS_TEST_REDIS_PORT'), 'Set AIOPS_TEST_REDIS_PORT for real Redis integration')
class RealRedisRateTests(unittest.IsolatedAsyncioTestCase):
    async def test_two_pools_share_atomic_window_and_users_are_isolated(self):
        prefix='aiops:test:'+uuid.uuid4().hex+':'
        settings=Settings('http://platform', redis_port=int(os.environ['AIOPS_TEST_REDIS_PORT']),
                          redis_password=os.environ.get('AIOPS_TEST_REDIS_PASSWORD',''),
                          redis_key_prefix=prefix, chat_rate_per_minute=3)
        first, second = ChatRateLimiter(settings), ChatRateLimiter(settings)
        try:
            async def request(index):
                try:
                    await (first if index % 2 else second).check({'id': 71})
                    return 200
                except HTTPException as exc:
                    return exc.status_code
            results=await asyncio.gather(*(request(i) for i in range(24)))
            self.assertEqual(results.count(200), 3)
            self.assertEqual(results.count(429), 21)
            await second.check({'id': 72})
            keys=[key async for key in first.client.scan_iter(match=prefix+'*')]
            self.assertEqual(len(keys),2)
            for key in keys:
                self.assertGreater(await first.client.ttl(key), 0)
            # Expiration recovers quota without any local-process reset.
            user_key=prefix+'chat:rate:'+hashlib.sha256(b'71').hexdigest()
            await first.client.pexpire(user_key, 1)
            await asyncio.sleep(.02)
            await second.check({'id': 71})
            self.assertEqual((await first.stats())['status'],'UP')
        finally:
            keys=[key async for key in first.client.scan_iter(match=prefix+'*')]
            if keys: await first.client.delete(*keys)
            await first.close()
            await second.close()

    async def test_http_requests_share_real_quota_including_stream_endpoint(self):
        import test_chat
        fixture=test_chat.ChatTests()
        await fixture.asyncSetUp()
        settings=replace(test_chat.CONFIG, redis_enabled=True,
                         redis_port=int(os.environ['AIOPS_TEST_REDIS_PORT']),
                         redis_password=os.environ.get('AIOPS_TEST_REDIS_PASSWORD',''),
                         redis_key_prefix='aiops:test:'+uuid.uuid4().hex+':', chat_rate_per_minute=2)
        def backend(request):
            return httpx.Response(200,json={'id': 72 if request.headers['Authorization']=='Bearer second' else 71})
        client=fixture.build(replies=[test_chat.completion('test answer')]*8,backend=backend,settings=settings)
        limiter=None
        try:
            async with client:
                responses=await asyncio.gather(*(client.post('/api/chat',json={'question':'q'},
                    headers={'Authorization':'Bearer first'}) for _ in range(6)))
                self.assertEqual([r.status_code for r in responses].count(200),2)
                self.assertEqual([r.status_code for r in responses].count(429),4)
                response=await client.post('/api/chat/stream',json={'question':'q'},headers={'Authorization':'Bearer first'})
                self.assertEqual(response.status_code,429)
                self.assertGreater(int(response.headers['Retry-After']),0)
                other=await client.post('/api/chat',json={'question':'q'},headers={'Authorization':'Bearer second'})
                self.assertEqual(other.status_code,200)
                self.assertEqual(len(fixture.model_requests),3)
                limiter=client._transport.app.state.chat_limiter
        finally:
            if limiter:
                keys=[key async for key in limiter.client.scan_iter(match=settings.redis_key_prefix+'*')]
                if keys: await limiter.client.delete(*keys)
                await limiter.close()

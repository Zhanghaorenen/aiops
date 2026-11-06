import json
import unittest
import httpx
from app.llm.client import LLMClient


class DeepSeekProtocolTests(unittest.IsolatedAsyncioTestCase):
    async def test_deepseek_disables_thinking_for_chat_and_stream(self):
        requests = []

        def reply(request):
            body = json.loads(request.content)
            requests.append(body)
            if body.get("stream"):
                return httpx.Response(
                    200,
                    headers={"content-type": "text/event-stream"},
                    text=(
                        'data: {"id":"1","object":"chat.completion.chunk","created":0,"model":"test","choices":[{"index":0,"delta":{"content":"正常"}}]}\n\n'
                        "data: [DONE]\n\n"
                    ),
                )
            return httpx.Response(
                200,
                json={
                    "id": "1",
                    "object": "chat.completion",
                    "created": 0,
                    "model": "test",
                    "choices": [
                        {
                            "index": 0,
                            "finish_reason": "stop",
                            "message": {"role": "assistant", "content": "正常"},
                        }
                    ],
                },
            )

        http = httpx.AsyncClient(transport=httpx.MockTransport(reply))
        model = LLMClient(
            "test", "https://api.deepseek.com/v1", "test-key", http_client=http
        )
        try:
            self.assertEqual(
                (await model.chat([{"role": "user", "content": "检查"}])).content,
                "正常",
            )
            chunks = [
                item
                async for item in model.stream_chat(
                    [{"role": "user", "content": "检查"}]
                )
            ]
            self.assertEqual(chunks, [{"type": "token", "text": "正常"}])
            self.assertTrue(
                all(r["thinking"] == {"type": "disabled"} for r in requests)
            )
        finally:
            await model.close()

    async def test_other_providers_do_not_receive_deepseek_options(self):
        captured = []

        def reply(request):
            captured.append(json.loads(request.content))
            return httpx.Response(
                200,
                json={
                    "id": "1",
                    "object": "chat.completion",
                    "created": 0,
                    "model": "test",
                    "choices": [
                        {
                            "index": 0,
                            "finish_reason": "stop",
                            "message": {"role": "assistant", "content": "ok"},
                        }
                    ],
                },
            )

        http = httpx.AsyncClient(transport=httpx.MockTransport(reply))
        model = LLMClient(
            "test", "https://example.com/v1", "test-key", http_client=http
        )
        try:
            await model.chat([{"role": "user", "content": "检查"}])
            self.assertNotIn("thinking", captured[0])
        finally:
            await model.close()

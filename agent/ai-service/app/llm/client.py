"""OpenAI-compatible Chat Completions adapter; no model name or key is embedded."""

from openai import AsyncOpenAI, BadRequestError
from urllib.parse import urlsplit
from ..telemetry import span, current, fixture_content
import hashlib
import os


class ModelResponseError(RuntimeError):
    pass


class LLMClient:
    def __init__(
        self,
        model: str,
        base_url: str,
        api_key: str,
        timeout: float = 30.0,
        http_client=None,
    ):
        self.model = model
        # DeepSeek 默认思考模式要求多轮回传 reasoning_content。当前图只保存工具和正文，
        # 因此使用官方非思考工具协议，避免第二轮工具结果提交时收到 400。
        self.request_options = (
            {"extra_body": {"thinking": {"type": "disabled"}}}
            if urlsplit(base_url).hostname == "api.deepseek.com"
            else {}
        )
        self.client = AsyncOpenAI(
            base_url=base_url,
            api_key=api_key,
            timeout=timeout,
            max_retries=1,
            http_client=http_client,
        )

    async def chat(self, messages: list[dict], tools=None, *, json_mode=False, idempotency_key=None, max_tokens=None):
        kwargs = {"model": self.model, "messages": messages, **self.request_options}
        if json_mode:
            kwargs['response_format'] = {'type': 'json_object'}
        if idempotency_key:
            kwargs['extra_headers'] = {'Idempotency-Key': idempotency_key}
        if max_tokens:
            kwargs['max_tokens'] = max_tokens
        if tools:
            kwargs.update(tools=tools, tool_choice="auto")
        with span("llm.chat", "generation") as obs:
            self.describe(obs, messages)
            completion = await self.client.chat.completions.create(**kwargs)
            self.usage(obs, getattr(completion, "usage", None))
            if completion.choices and completion.choices[0].message:
                fixture_content('output',completion.choices[0].message.content)
        if not completion.choices:
            raise ModelResponseError("模型没有返回有效结果")
        message = completion.choices[0].message
        if message is None:
            raise ModelResponseError("模型没有返回有效消息")
        return message

    def describe(self, obs, messages):
        if not current(): return
        fixture_content("input",messages)
        obs["gen_ai.request.model"] = self.model
        system = next((str(m["content"]) for m in messages if m.get("role")=="system"), "")
        obs["promptHash"] = hashlib.sha256(system.encode()).hexdigest()[:16]
        obs["langfuse.version"] = os.getenv('AIOPS_PROMPT_VERSION','aiops-engineering-v1')

    @staticmethod
    def usage(obs, usage):
        if usage is not None:
            for key, attr in (("input","prompt_tokens"),("output","completion_tokens")):
                count = getattr(usage,attr,None)
                if isinstance(count,int):
                    obs["gen_ai.usage."+key+"_tokens"] = count

    async def stream_chat(self, messages: list[dict], tools=None):
        with span("llm.stream", "generation") as obs:
            self.describe(obs, messages)
            async for event in self._stream(messages, tools, obs):
                yield event

    async def _stream(self, messages, tools, obs):
        """Yield model text deltas and complete OpenAI-format tool calls."""
        kwargs = {
            "model": self.model,
            "messages": messages,
            "stream": True,
            **self.request_options,
        }
        if tools:
            kwargs.update(tools=tools, tool_choice="auto")

        if current():
            kwargs['stream_options'] = {'include_usage':True}
        tool_calls: dict[int, dict] = {}
        try:
            stream = await self.client.chat.completions.create(**kwargs)
        except BadRequestError as exc:
            if "stream_options" not in kwargs or "stream_options" not in str(exc):
                raise
            kwargs.pop("stream_options")
            stream = await self.client.chat.completions.create(**kwargs)
        async with stream:
            async for chunk in stream:
                self.usage(obs, getattr(chunk,'usage',None))
                if not chunk.choices:
                    continue
                delta = chunk.choices[0].delta
                if isinstance(delta.content, str) and delta.content:
                    yield {"type": "token", "text": delta.content}
                for fragment in delta.tool_calls or []:
                    call = tool_calls.setdefault(
                        fragment.index,
                        {
                            "id": "",
                            "type": "function",
                            "function": {"name": "", "arguments": ""},
                        },
                    )
                    if fragment.id:
                        call["id"] = fragment.id
                    if fragment.type:
                        call["type"] = fragment.type
                    if fragment.function:
                        if fragment.function.name:
                            call["function"]["name"] += fragment.function.name
                        if fragment.function.arguments:
                            call["function"]["arguments"] += fragment.function.arguments

        if tool_calls:
            yield {
                "type": "tool_calls",
                "calls": [tool_calls[index] for index in sorted(tool_calls)],
            }

    async def close(self):
        await self.client.close()

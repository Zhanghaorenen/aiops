"""OpenAI-compatible Chat Completions adapter; no model name or key is embedded."""

from openai import AsyncOpenAI


class ModelResponseError(RuntimeError):
    pass


class LLMClient:
    def __init__(self, model: str, base_url: str, api_key: str, timeout: float = 30.0,
                 http_client=None):
        self.model = model
        self.client = AsyncOpenAI(base_url=base_url, api_key=api_key, timeout=timeout,
                                  max_retries=1, http_client=http_client)

    async def chat(self, messages: list[dict], tools=None):
        kwargs = {"model": self.model, "messages": messages}
        if tools:
            kwargs.update(tools=tools, tool_choice="auto")
        completion = await self.client.chat.completions.create(**kwargs)
        if not completion.choices:
            raise ModelResponseError("模型没有返回有效结果")
        message = completion.choices[0].message
        if message is None:
            raise ModelResponseError("模型没有返回有效消息")
        return message

    async def stream_chat(self, messages: list[dict], tools=None):
        """Yield model text deltas and complete OpenAI-format tool calls."""
        kwargs = {"model": self.model, "messages": messages, "stream": True}
        if tools:
            kwargs.update(tools=tools, tool_choice="auto")

        tool_calls: dict[int, dict] = {}
        stream = await self.client.chat.completions.create(**kwargs)
        async with stream:
            async for chunk in stream:
                if not chunk.choices:
                    continue
                delta = chunk.choices[0].delta
                if isinstance(delta.content, str) and delta.content:
                    yield {"type": "token", "text": delta.content}
                for fragment in delta.tool_calls or []:
                    call = tool_calls.setdefault(fragment.index, {
                        "id": "", "type": "function",
                        "function": {"name": "", "arguments": ""},
                    })
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
            yield {"type": "tool_calls", "calls": [tool_calls[index] for index in sorted(tool_calls)]}

    async def close(self):
        await self.client.close()

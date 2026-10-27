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

    async def close(self):
        await self.client.close()

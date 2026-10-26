"""完整模型调用
"""

from openai import AsyncOenai
class LLMClient:
    def __init__(self, model: str,base_url:str,api_key:str):
        self.model=model
        self.client=AsyncOenai(
            base_url=base_url,
            api_key=api_key,
            timeout=30
        )

    async def chat(self,message:list[dict],tools=None):
        kwargs={
            "model":self.model,
            "messages":message
        }
        if tools:
            kwargs["tools"]=tools
            kwargs['tool_choice']='auto'
        ## 发生异常时，openai库会自动重试，直到达到最大重试次数
        completion=await self.client.chat.completions.create(**kwargs)
        if not completion.choices:
            raise ValueError(  "模型没有返回有效结果")
        return completion.choices[0].message
    async def close(self):
        await self.client.close()
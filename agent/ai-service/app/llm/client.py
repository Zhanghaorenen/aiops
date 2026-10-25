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
            kwargs['choice']='auto'

        response=await self.client.chat(**kwargs)
        return response
    async def close(self):
        await self.client.aclose()
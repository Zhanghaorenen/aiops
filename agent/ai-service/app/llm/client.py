"""完整模型调用
"""


class LLMClient:
    def __init__(self, model_name: str):
        self.model_name = model_name

    def generate_response(self, prompt: str) -> str:
        # 这里可以根据模型名称选择不同的模型调用方式
        if self.model_name == "gpt-3.5":
            return self._call_gpt_3_5(prompt)
        elif self.model_name == "gpt-4":
            return self._call_gpt_4(prompt)
        else:
            raise ValueError(f"Unsupported model: {self.model_name}")

    def _call_gpt_3_5(self, prompt: str) -> str:
        # 调用 GPT-3.5 的接口逻辑
        return f"GPT-3.5 response to: {prompt}"

    def _call_gpt_4(self, prompt: str) -> str:
        # 调用 GPT-4 的接口逻辑
        return f"GPT-4 response to: {prompt}"
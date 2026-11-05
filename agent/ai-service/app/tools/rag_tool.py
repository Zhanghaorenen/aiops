from langchain.tools import tool

class RagTool:
    def __init__(self,retrieval): self.retrieval=retrieval
    def get_tools(self):
        @tool
        async def search_knowledge(query: str, knowledge_base_ids: list[int] | None = None, top_k: int = 5) -> dict:
            """检索当前用户私人运维知识库，返回文件名、页码、章节和证据原文；静态运维知识使用此工具。"""
            return await self.retrieval.retrieve(query,knowledge_base_ids,top_k)
        return [search_knowledge]

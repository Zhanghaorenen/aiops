"""Read-only server tools shared by the chat and diagnosis agents."""

from .server_manager import ServerManager
from langchain.tools import tool


class ServerTool:
    def __init__(self, server_manager: ServerManager):
        self.server_manager = server_manager

    # Keep ordinary Python methods for existing DiagnosisAgent callers.
    async def list_servers(self) -> list[dict]:
        return await self.server_manager.list_servers()

    async def get_server_status(self, server_id: int) -> dict:
        return await self.server_manager.get_server_snapshot(server_id)

    async def get_server_resource_usage(self, server_id: int) -> dict:
        return await self.server_manager.get_resource_usage(server_id)

    def get_tools(self) -> list:
        """Return LangChain tools that can be passed to a model or ToolNode."""

        @tool
        async def list_servers() -> list[dict]:
            """列出当前用户可访问的服务器，帮助确定服务器 ID。"""
            return await self.list_servers()

        @tool
        async def get_server_status(server_id: int) -> dict:
            """根据服务器 ID 查询资产信息、在线状态和最近心跳。"""
            return await self.get_server_status(server_id)

        @tool
        async def get_server_resource_usage(server_id: int) -> dict:
            """查询服务器最新 CPU、内存和磁盘指标及采集时间。"""
            return await self.get_server_resource_usage(server_id)

        return [list_servers, get_server_status, get_server_resource_usage]


# Preserve the lowercase public alias used by existing imports.
server_tool = ServerTool

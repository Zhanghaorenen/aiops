from .server_manager import ServerManager
from langchain.tools import tool

class ServerTool:
    def __init__(self, server_manager: ServerManager):
        self.server_manager = server_manager

    @tool
    async def list_servers(self) -> list[dict]:
        return await self.server_manager.list_servers()

    @tool
    async def get_server_status(self, server_id: int) -> dict:
        return await self.server_manager.get_server_snapshot(server_id)

    @tool
    async def get_server_resource_usage(self, server_id: int) -> dict:
        return await self.server_manager.get_resource_usage(server_id)


# Keep the original public class name usable for existing imports.
server_tool = ServerTool

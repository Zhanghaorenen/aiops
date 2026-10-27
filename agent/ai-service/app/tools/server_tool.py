from .server_manager import ServerManager


class ServerTool:
    def __init__(self, server_manager: ServerManager):
        self.server_manager = server_manager

    async def list_servers(self) -> list[dict]:
        return await self.server_manager.list_servers()

    async def get_server_status(self, server_id: int) -> dict:
        return await self.server_manager.get_server_snapshot(server_id)

    async def get_server_resource_usage(self, server_id: int) -> dict:
        return await self.server_manager.get_resource_usage(server_id)


# Keep the original public class name usable for existing imports.
server_tool = ServerTool

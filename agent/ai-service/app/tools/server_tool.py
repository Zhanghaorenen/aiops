
from .server_manager import ServerManager

class server_tool:
    def __init__(self, server_manager:ServerManager):
        self.server_manager = server_manager
    async def list_servers(self) -> dict:
        """
        查询有权限访问的服务器列表。
        使用 ServerManager 请求 Spring Boot 后端。
        """
        servers = await self.server_manager.list_servers()
        return servers
    async def get_server_status(self, server_id: int) -> dict:
        """
        查询指定服务器的状态信息。
        使用 ServerManager 请求 Spring Boot 后端。
        """
        server_status = await self.server_manager.get_server_snapshot(server_id)
        return server_status

    async def get_server_resource_usage(self, server_id: int) -> dict:
        """
        查询指定服务器的资源使用情况。
        使用 ServerManager 请求 Spring Boot 后端。
        """
        cpu_status = await self.server_manager.get_cpu_status(server_id)
        memory_status = await self.server_manager.get_memory_status(server_id)
        disk_status = await self.server_manager.get_disk_status(server_id)

        return {
            "cpu": cpu_status,
            "memory": memory_status,
            "disk": disk_status
        }
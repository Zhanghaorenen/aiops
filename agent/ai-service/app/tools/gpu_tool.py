from langchain.tools import tool
from server_manager import ServerManager



class ServerManagerTool:
    def __init__(self, server_manager: ServerManager):
        self.server_manager = server_manager
    
   
    @tool
    async def get_resource_usage(self, server_id: int) -> dict:
        return await self.server_manager.get_resource_usage(server_id)
  
    @tool
    async def get_memory_status(self, server_id: int) -> dict:
        return await self.server_manager.get_memory_status(server_id)
    @tool
    async def get_disk_status(self, server_id: int) -> dict:
        return await self.server_manager.get_disk_status(server_id)
    @tool
    async def get_metric_history(self, server_id: int, metric_name: str, start_time: str, end_time: str) -> list[dict]:
        return await self.server_manager.get_metric_history(server_id, metric_name, start_time, end_time)
    @tool
    async def get_server_overview(self, server_id: int) -> dict:
        return await self.server_manager.get_server_overview(server_id)
    @tool
    async def get_cluster_overview(self) -> dict:
        return await self.server_manager.get_cluster_overview()
    @tool
    async def get_gpu_process(self, server_id: int, gpu_id: int):
        return await self.server_manager.get_gpu_process(server_id, gpu_id)
    @tool
    async def get_gpu_history(self, server_id: int, gpu_id: int, start_time: str, end_time: str):
        return await self.server_manager.get_gpu_history(server_id, gpu_id, start_time, end_time)

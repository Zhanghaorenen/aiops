

"""
服务器管理层
具体包含的信息有：服务器ID、服务器名称、服务器系统信息

"""
import logging
from client.platform_client import PlatformClient
from config import Config
class ServerManager:
    def __init__(self, platform_client:PlatformClient):
       self.client=platform_client
    async def list_servers(self,base_url:str,access_token:str) -> dict:
        
        try:
            # 使用 PlatformClient 查询服务器列表
            platform_client = self.client
            servers = await platform_client.get_server_list()
            return servers
        except Exception as e:
            logging.error(f"Error occurred while listing servers: {e}")
            raise e
    async def get_server_snapshot(self, server_id: int) -> dict:
        """
        查询指定服务器的详细信息。
        使用 PlatformClient 请求 Spring Boot 后端。
        """
        try:
            platform_client = self.client
            self.server_snapshot = await platform_client.get_server_snapshot(server_id)
            return self.server_snapshot
        except Exception as e:
            logging.error(f"Error occurred while getting server details for server_id {server_id}: {e}")
            raise e
    async def get_cpu_status(self,server_id:int)->dict:
        """
        查询指定服务器的CPU状态信息。
        使用 PlatformClient 请求 Spring Boot 后端。
        """
        try:
            server_info=await self.get_server_snapshot(server_id)
            cpu_status = server_info.get("system", {}).get("cpu", {})
            return cpu_status
        except Exception as e:
            logging.error(f"Error occurred while getting CPU status for server_id {server_id}: {e}")
            raise e
    async def get_memory_status(self,server_id:int)->dict:
        """
        查询指定服务器的内存状态信息。
        使用 PlatformClient 请求 Spring Boot 后端。
        """
        try:
            server_info=await self.get_server_snapshot(server_id)
            memory_status = server_info.get("system", {}).get("memory", {})
            return memory_status
        except Exception as e:
            logging.error(f"Error occurred while getting memory status for server_id {server_id}: {e}")
            raise e
        
    async def get_disk_status(self,server_id:int)->dict:
        """
        查询指定服务器的磁盘状态信息。
        使用 PlatformClient 请求 Spring Boot 后端。
        """
        try:
            server_info=await self.get_server_snapshot(server_id)
            disk_status = server_info.get("system", {}).get("disk", {})
            return disk_status
        except Exception as e:
            logging.error(f"Error occurred while getting disk status for server_id {server_id}: {e}")
            raise e
        
    async def get_gpu_process(self, server_id: int,gpu_id: int) -> dict:
        """
        查询指定服务器的GPU进程信息。
        使用 PlatformClient 请求 Spring Boot 后端。
        """
        try:
            server_info=await self.get_server_snapshot(server_id)
            gpu_process_info = server_info.get("gpus", {}).get(gpu_id, {}).get("processes", [])
            return gpu_process_info
        except Exception as e:
            logging.error(f"Error occurred while getting GPU process info for server_id {server_id}: {e}")
            raise e
        
    async def get_metric_history(self, server_id: int, metric_name: str, 
                                 start_time: str, end_time: str) -> dict:
        """
        查询指定服务器的指标历史数据。
        使用 PlatformClient 请求 Spring Boot 后端。
        """
        try:
            platform_client = self.client
            metric_history = await platform_client.get_server_metric_history(server_id, metric_name, start_time, end_time)
            return metric_history
        except Exception as e:
            logging.error(f"Error occurred while getting metric history for server_id {server_id}: {e}")
            raise e
        
    async def get_gpu_history(self,server_id:int,gpu_id:int,start_time:str,end_time:str)->dict:
        """
        查询指定服务器的GPU历史数据。
        使用 PlatformClient 请求 Spring Boot 后端。
        """
        try:
            platform_client = await self.get_metric_history(server_id, "gpu", 
                                                      start_time, end_time)
            
            gpu_history = platform_client.get("gpus", {}).get(gpu_id, {}).get("history", [])
            return gpu_history
        except Exception as e:
            logging.error(f"Error occurred while getting GPU history for server_id {server_id}: {e}")
            raise e

    async def get_server_overview(self, server_id: int) -> dict:
        """
        查询指定服务器的概览信息。
        使用 PlatformClient 请求 Spring Boot 后端。
        """
        try:
            platform_client = self.client
            server_overview = await platform_client.get_server_overview(server_id)
            return server_overview
        except Exception as e:
            logging.error(f"Error occurred while getting server overview for server_id {server_id}: {e}")
            raise e
        
    async def get_cluster_overview(self) -> dict:
        """
        查询集群的概览信息。
        使用 PlatformClient 请求 Spring Boot 后端。
        """
        try:
            platform_client = self.client
            cluster_overview = await platform_client.get_cluster_overview()
            return cluster_overview
        except Exception as e:
            logging.error(f"Error occurred while getting cluster overview: {e}")
            raise e
import httpx


class PlatformClient:
    def __init__(self, base_url: str, access_token: str):
        self.base_url = base_url
        self.access_token = access_token

    async def get_server_latest_metrics(self, node_id: int) -> dict:
        """
        查询指定服务器的最新监控数据。
        使用 httpx 请求 Spring Boot 后端。
        """
        url = f"{self.base_url}/api/servers/{node_id}/metrics/latest"
        headers = {"Authorization": f"Bearer {self.access_token}"}

        async with httpx.AsyncClient() as client:
            response = await client.get(url, headers=headers)
            response.raise_for_status()
            return response.json()
    async def get_server_list(self) -> dict:
        """
        查询有权限访问的服务器列表。
        使用 httpx 请求 Spring Boot 后端。
        """
        url = f"{self.base_url}/api/servers"
        headers = {"Authorization": f"Bearer {self.access_token}"}

        async with httpx.AsyncClient() as client:
            response = await client.get(url, headers=headers)
            response.raise_for_status()
            return response.json()
    async def get_server_snapshot(self, server_id: int) -> dict:
        """
        查询指定服务器的详细信息。
        使用 httpx 请求 Spring Boot 后端。
        """
        url = f"{self.base_url}/api/servers/{server_id}/snapshot"
        headers = {"Authorization": f"Bearer {self.access_token}"}

        async with httpx.AsyncClient() as client:
            response = await client.get(url, headers=headers)
            response.raise_for_status()
            return response.json()
    async def get_gpu_process(self, server_id: int, gpu_id: int) -> dict:
        """
        查询指定服务器的GPU进程信息。
        使用 httpx 请求 Spring Boot 后端。
        """
        url = f"{self.base_url}/api/servers/{server_id}/gpus/{gpu_id}/processes"
        headers = {"Authorization": f"Bearer {self.access_token}"}

        async with httpx.AsyncClient() as client:
            response = await client.get(url, headers=headers)
            response.raise_for_status()
            return response.json()

    async def get_server_metric_history(self, server_id: int, metric_name: str,
                                        start_time: str, end_time: str) -> dict:
        """
        查询指定服务器的指标历史数据。
        使用 httpx 请求 Spring Boot 后端。
        """
        url = f"{self.base_url}/api/servers/{server_id}/metrics/history"
        headers = {"Authorization": f"Bearer {self.access_token}"}
        params = {
            "metric_name": metric_name,
            "start_time": start_time,
            "end_time": end_time
        }

        async with httpx.AsyncClient() as client:
            response = await client.get(url, headers=headers, params=params)
            response.raise_for_status()
            return response.json()

    async def get_server_overview(self, server_id: int) -> dict:
        """
        查询指定服务器的概览信息。
        使用 httpx 请求 Spring Boot 后端。
        """
        url = f"{self.base_url}/api/servers/{server_id}/overview"
        headers = {"Authorization": f"Bearer {self.access_token}"}

        async with httpx.AsyncClient() as client:
            response = await client.get(url, headers=headers)
            response.raise_for_status()
            return response.json()

    async def get_cluster_overview(self) -> dict:
        """
        查询集群的概览信息。
        使用 httpx 请求 Spring Boot 后端。
        """
        url = f"{self.base_url}/api/cluster/overview"
        headers = {"Authorization": f"Bearer {self.access_token}"}

        async with httpx.AsyncClient() as client:
            response = await client.get(url, headers=headers)
            response.raise_for_status()
            return response.json()
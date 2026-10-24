import httpx


async def get_server_latest_metrics(
    node_id: int,
    access_token: str
) -> dict:
    """
    查询指定服务器的最新监控数据。
    使用 httpx 请求 Spring Boot 后端。
    """
    ...
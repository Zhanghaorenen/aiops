

async def get_server_latest_metrics() -> dict:
    """
    获取服务器最新的指标数据
    """
    from .server_metrics import ServerMetricsCollector

    collector = ServerMetricsCollector()
    metrics = collector.collect_metrics()
    return metrics
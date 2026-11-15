from .base import BaseAgent

MONITORING_TOOLS = ["list_servers","get_server_status","get_server_resource_usage","list_gpus","get_gpu_status","get_gpu_overview","get_gpu_processes","get_gpu_history","get_recent_gpu_history","analyze_gpu_health","compare_gpu_load"]

class MonitoringAgent(BaseAgent):
    name="monitoring"
    whitelist=MONITORING_TOOLS

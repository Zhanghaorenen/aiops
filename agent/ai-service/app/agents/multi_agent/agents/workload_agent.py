from .base import BaseAgent

WORKLOAD_TOOLS = ["list_training_jobs","analyze_training_contention","get_active_workloads","analyze_active_workload","get_resource_presets","get_workload_profile","estimate_available_capacity","predict_workload_completion","get_resource_guard_status","recommend_gpu_placement"]

class WorkloadAgent(BaseAgent):
    name="workload"
    whitelist=WORKLOAD_TOOLS

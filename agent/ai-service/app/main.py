from fastapi import FastAPI

app = FastAPI(
    title="AIOps AI Service",
    version="1.0.0",
    description="AIOps AI Service API",
)

@app.get("/api/gpu_status/{server_id}")
def get_metrics():
    from .tools.server_metrics import get_server_latest_metrics
    metrics = get_server_latest_metrics()
    return metrics
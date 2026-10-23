from fastapi import FastAPI

app = FastAPI(
    title="AIOps AI Service",
    version="1.0.0",
    description="AIOps AI Service API",
)

@app.get("/health")
def health_check():
    return {"status": "ok"}
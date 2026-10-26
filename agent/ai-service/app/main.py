from fastapi import FastAPI
from .api.chat import router as chat_router
app = FastAPI(
    title="AIOps AI Service",
    version="1.0.0",
    description="AIOps AI Service API",
)
app.include_router(chat_router)


    

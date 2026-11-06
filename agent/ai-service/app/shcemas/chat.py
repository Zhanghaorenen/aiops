"""兼容历史调用方，不重复定义请求模型。"""
from ..schemas.chat import ChatRequest

__all__ = ["ChatRequest"]

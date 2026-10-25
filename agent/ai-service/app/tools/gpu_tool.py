"""
作为AI服务的中间层，处于后端以及服务器之间的中间层，负责gpu信息的收集和处理，提供给后端使用
"""


async def get_gpu_status(server_id:int)->dict:
    """
    获取指定服务器的GPU状态信息。
    """
    from .tools.gpu_tool import get_gpu_info
    gpu_info = await get_gpu_info(server_id)
    return gpu_info
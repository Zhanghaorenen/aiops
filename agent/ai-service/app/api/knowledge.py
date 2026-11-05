from contextlib import asynccontextmanager
from functools import lru_cache
from fastapi import APIRouter, Depends, HTTPException, Request, UploadFile, File
from fastapi.responses import FileResponse
from fastapi.security import HTTPAuthorizationCredentials
import httpx
from .chat import auth_scheme, get_settings
from ..rag.schemas import SearchRequest
from ..rag.embedding_service import EmbeddingService
from ..rag.vector_store import VectorStore
from ..rag.retrieval_service import RetrievalService
from ..rag.ingestion_service import IngestionService

router=APIRouter(prefix='/api/rag',tags=['知识库'])

@lru_cache(maxsize=4)
def resources(settings):
    return EmbeddingService(settings.embedding_path,settings.embedding_version), VectorStore(settings.qdrant_url,settings.qdrant_key,settings.embedding_version)

def rag_tool(platform,settings):
    from ..tools.rag_tool import RagTool
    embedding,store=resources(settings)
    return RagTool(RetrievalService(platform,embedding,store))

@asynccontextmanager
async def session(request,credentials):
    if credentials is None: raise HTTPException(401,'需要用户登录凭证')
    settings=get_settings(request)
    if len(settings.rag_service_key)<32: raise HTTPException(503,'请配置知识库服务密钥')
    platform=request.app.state.platform_factory(settings.backend_url,credentials.credentials,settings.backend_timeout)
    try:
        await platform.get_current_user()
        yield platform,settings
    except httpx.HTTPStatusError as e:
        code=e.response.status_code
        raise HTTPException(code if code in (401,403,404,409) else 502,'知识库请求被拒绝或依赖服务异常') from e
    except httpx.RequestError as e: raise HTTPException(503,'知识库依赖服务不可用') from e
    except (ValueError,ImportError) as e: raise HTTPException(422,'文档无法解析或本地 Embedding 未配置，请检查格式与服务配置') from e
    finally: await platform.close()

@router.post('/knowledge-bases/{kb_id}/documents')
async def upload_document(kb_id:int,request:Request,file:UploadFile=File(...),credentials:HTTPAuthorizationCredentials|None=Depends(auth_scheme)):
    async with session(request,credentials) as (platform,settings):
        embedding,store=resources(settings)
        return await IngestionService(platform,embedding,store,settings).ingest_document(kb_id,file)

@router.post('/search')
async def search_knowledge(payload:SearchRequest,request:Request,credentials:HTTPAuthorizationCredentials|None=Depends(auth_scheme)):
    async with session(request,credentials) as (platform,settings):
        embedding,store=resources(settings)
        return await RetrievalService(platform,embedding,store).retrieve(payload.query,payload.knowledge_base_ids,payload.top_k)

@router.delete('/knowledge-bases/{kb_id}/documents/{doc_id}')
async def delete_document(kb_id:int,doc_id:int,request:Request,credentials:HTTPAuthorizationCredentials|None=Depends(auth_scheme)):
    async with session(request,credentials) as (platform,settings):
        embedding,store=resources(settings)
        await IngestionService(platform,embedding,store,settings).delete_document(kb_id,doc_id)
        return {'deleted':True}

@router.delete('/knowledge-bases/{kb_id}')
async def delete_base(kb_id:int,request:Request,credentials:HTTPAuthorizationCredentials|None=Depends(auth_scheme)):
    async with session(request,credentials) as (platform,settings):
        embedding,store=resources(settings)
        await platform.mark_knowledge_base_deleting(kb_id)
        for doc in await platform.list_knowledge_documents(kb_id):
            await IngestionService(platform,embedding,store,settings).delete_document(kb_id,doc['id'])
        await platform.purge_knowledge_base(kb_id,settings.rag_service_key)
        return {'deleted':True}

@router.get('/knowledge-bases/{kb_id}/documents/{doc_id}/download')
async def download_document(kb_id:int,doc_id:int,request:Request,credentials:HTTPAuthorizationCredentials|None=Depends(auth_scheme)):
    async with session(request,credentials) as (platform,settings):
        doc=await platform.get_knowledge_document(kb_id,doc_id)
        if doc['status']=='DELETING': raise HTTPException(409,'文档正在删除')
        path=IngestionService(platform,None,None,settings).path(doc['storageKey'])
        if not path.is_file(): raise HTTPException(404,'原文件不存在')
        return FileResponse(path,filename=doc['originalFilename'],media_type=doc['mimeType'])

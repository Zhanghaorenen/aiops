import asyncio
import json
import secrets
from uuid import UUID
from contextlib import AsyncExitStack
from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field
import httpx
from .chat import auth_scheme, get_settings
from ..memory.extraction import extract
from ..memory.schemas import safe, VOLATILE

router=APIRouter(prefix='/api/internal/chat-memory',tags=['私人聊天记忆任务'])


class ExtractRequest(BaseModel):
    model_config=ConfigDict(extra='forbid')
    task_id: UUID
    lease: UUID


class IndexRequest(BaseModel):
    model_config=ConfigDict(extra='forbid')
    memory_id: UUID
    version: int=Field(gt=0)


async def authorize(request,credentials,key,stack):
    settings=get_settings(request)
    if len(settings.ai_service_key)<32 or not secrets.compare_digest(key or '',settings.ai_service_key):
        raise HTTPException(403,'需要私人记忆服务凭据')
    if credentials is None:
        raise HTTPException(401,'需要用户身份')
    platform=request.app.state.platform_factory(settings.backend_url,credentials.credentials,settings.backend_timeout)
    stack.push_async_callback(platform.close)
    user=await platform.get_current_user()
    if type(user.get('id')) is not int or user['id']<=0:
        raise HTTPException(502,'用户身份无效')
    return settings,platform,user['id']


@router.post('/extract')
async def extract_task(payload:ExtractRequest,request:Request,credentials=Depends(auth_scheme),
                       key:str|None=Header(None,alias='X-AI-Service-Key')):
    async with AsyncExitStack() as stack:
        try:
            settings,platform,user=await authorize(request,credentials,key,stack)
            job=await platform.get_memory_task(str(payload.task_id),str(payload.lease),settings.ai_service_key)
            context=job['payload']
            # Retrieve older relevant preferences too; append only MySQL-validated owned versions.
            try:
                async with asyncio.timeout(3):
                    index=request.app.state.rag_resources.memory(settings)
                    query='\n'.join(m['content'] for m in context['messages'] if m['role']=='user')[-2000:]
                    if safe(query):
                        hits=await index.search(user,query,5,.72)
                        references=[{'id':h['id'],'version':h['version']} for h in hits]
                        current=await platform.verify_user_memories(references) if references else []
                        previous=context.setdefault('existing_memories',[])
                        for memory in current:
                            if any(str(m['id'])==str(memory['id']) for m in previous):continue
                            item={k:memory.get(k) for k in ('id','version','category','semantic_key','content')}
                            item['content']=item['content'][:500]
                            if len(json.dumps(context,ensure_ascii=False))+len(json.dumps(item,ensure_ascii=False))<31000:
                                previous.append(item)
            except httpx.HTTPStatusError as exc:
                if exc.response.status_code in (401,403):raise
            except Exception:
                pass # MySQL topic-key and exact-content dedup still apply while the index recovers.
            settings.require_llm()
            llm=request.app.state.llm_factory(settings.llm_model,settings.llm_base_url,settings.llm_api_key,settings.llm_timeout)
            stack.push_async_callback(llm.close)
            async with asyncio.timeout(100):
                result=await extract(llm,str(payload.task_id),context)
            # A high-similarity existing topic with no explicit replacement is conservatively
            # skipped, rather than adding a contradictory second preference.
            accepted=[]
            for candidate in result.memories:
                duplicate=False
                if not candidate.replaces_memory_id:
                    try:
                        async with asyncio.timeout(2):
                            index=request.app.state.rag_resources.memory(settings)
                            hits=await index.search(user,candidate.content,3,.94)
                            refs=[{'id':h['id'],'version':h['version']} for h in hits]
                            current=await platform.verify_user_memories(refs) if refs else []
                            duplicate=any(m['category']==candidate.category and m.get('semantic_key')!=candidate.key for m in current)
                    except httpx.HTTPStatusError as exc:
                        if exc.response.status_code in (401,403):raise
                    except Exception:
                        pass
                if not duplicate:accepted.append(candidate)
            result.memories=accepted
            return result.model_dump(mode='json')
        except HTTPException:raise
        except httpx.HTTPStatusError as exc:
            raise HTTPException(exc.response.status_code if exc.response.status_code in (401,403,404,409) else 502,'私人记忆任务未获授权或已失效') from exc
        except Exception as exc:
            raise HTTPException(502,'摘要提取未完成；任务保留以便重试') from exc


@router.post('/index')
async def index_task(payload:IndexRequest,request:Request,credentials=Depends(auth_scheme),
                     key:str|None=Header(None,alias='X-AI-Service-Key')):
    async with AsyncExitStack() as stack:
        try:
            settings,platform,user=await authorize(request,credentials,key,stack)
            memory=await platform.get_user_memory(str(payload.memory_id))
            if memory['version']!=payload.version:
                return {'status':'STALE'}
            async with asyncio.timeout(100):
                index=request.app.state.rag_resources.memory(settings)
                if memory['status']!='ACTIVE' or not safe(memory['content']) or VOLATILE.search(memory['content']):
                    await index.delete(user,str(payload.memory_id),payload.version)
                    return {'status':'REMOVED'}
                await index.upsert(user,memory)
                current=await platform.verify_user_memories([{'id':str(payload.memory_id),'version':payload.version}])
                if not current:
                    await index.delete(user,str(payload.memory_id),payload.version)
                    return {'status':'STALE_REMOVED'}
                return {'status':'INDEXED'}
        except HTTPException:raise
        except httpx.HTTPStatusError as exc:
            raise HTTPException(exc.response.status_code if exc.response.status_code in (401,403,404) else 502,'私人记忆索引未获授权') from exc
        except Exception as exc:
            raise HTTPException(502,'记忆索引未完成；保留待重试记录') from exc

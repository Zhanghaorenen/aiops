import asyncio
import json
from .schemas import SearchRequest, SearchResult

class RetrievalService:
    def __init__(self, platform, embedding, store):
        self.platform, self.embedding, self.store = platform, embedding, store

    async def retrieve(self, query, kb_ids=None, top_k=5):
        req=SearchRequest(query=query,knowledge_base_ids=kb_ids,top_k=top_k)
        mine = await self.platform.list_knowledge_bases()
        allowed={kb['id'] for kb in mine if kb['status']=='ACTIVE'}
        requested=set(req.knowledge_base_ids) if req.knowledge_base_ids is not None else allowed
        if not requested.issubset(allowed):
            from fastapi import HTTPException
            raise HTTPException(403,'无权检索指定知识库')
        if not requested: return {'results':[]}
        vector=await asyncio.to_thread(self.embedding.embed_query,req.query)
        hits=await self.store.search(vector,sorted(requested),min(50,req.top_k*5))
        results=[]
        for hit in hits:
            if hit['knowledge_base_id'] not in requested: continue
            # MySQL metadata is authoritative, including concurrent deletion and index version.
            try:
                kb=await self.platform.get_knowledge_base(hit['knowledge_base_id'])
                doc=await self.platform.get_knowledge_document(hit['knowledge_base_id'],hit['document_id'])
            except Exception as e:
                import httpx
                if isinstance(e,httpx.HTTPStatusError) and e.response.status_code==404: continue
                raise
            if kb['status']!='ACTIVE' or doc['status']!='READY' or doc['indexVersion']!=hit['index_version'] or doc['embeddingVersion']!=self.embedding.version: continue
            hit['document_title']=doc['originalFilename']
            candidate=SearchResult(**hit).model_dump()
            if len(json.dumps(results+[candidate],ensure_ascii=False))>12500: break
            results.append(candidate)
            if len(results)>=req.top_k: break
        return {'results':results}

"""Qdrant REST adapter. Collection identity includes the immutable embedding version."""
import hashlib
import uuid
import httpx

class VectorStore:
    def __init__(self, url, key, version):
        self.url = url.rstrip('/')
        self.headers = {'api-key': key} if key else {}
        self.version = version
        self.collection = 'aiops_' + hashlib.sha256(version.encode()).hexdigest()[:24]

    async def request(self, method, path, **kwargs):
        async with httpx.AsyncClient(base_url=self.url, headers=self.headers, timeout=60) as client:
            r = await client.request(method, path, **kwargs)
            r.raise_for_status()
            return r.json()

    async def ensure(self, dimension):
        try:
            data = await self.request('GET', f'/collections/{self.collection}')
        except httpx.HTTPStatusError as e:
            if e.response.status_code != 404: raise
            try:
                await self.request('PUT', f'/collections/{self.collection}', json={'vectors': {'size':dimension,'distance':'Cosine'}})
            except httpx.HTTPStatusError as conflict:
                if conflict.response.status_code not in (409, 400): raise
            data = await self.request('GET', f'/collections/{self.collection}')
        if data['result']['config']['params']['vectors']['size'] != dimension: raise ValueError('EMBEDDING_DIMENSION_MISMATCH')

    async def upsert_chunks(self, kb_id, doc_id, version, chunks, vectors, title):
        await self.ensure(len(vectors[0]))
        for start in range(0,len(chunks),64):
            points=[]
            for i in range(start,min(start+64,len(chunks))):
                chunk_id=f'doc{doc_id}-v{version}-{i:05d}'
                payload={**chunks[i].metadata,'chunk_id':chunk_id,'document_id':doc_id,'knowledge_base_id':kb_id,'index_version':version,'embedding_version':self.version,'status':'READY','document_title':title,'content':chunks[i].page_content}
                points.append({'id':str(uuid.uuid5(uuid.NAMESPACE_URL,chunk_id)), 'vector':vectors[i], 'payload':payload})
            await self.request('PUT',f'/collections/{self.collection}/points',params={'wait':'true'},json={'points':points})

    async def search(self, vector, allowed, top_k):
        if not allowed: return []
        await self.ensure(len(vector))
        filters={'must':[{'key':'knowledge_base_id','match':{'any':allowed}}, {'key':'status','match':{'value':'READY'}},{'key':'index_version','match':{'value':1}},{'key':'embedding_version','match':{'value':self.version}}]}
        data=await self.request('POST',f'/collections/{self.collection}/points/query',json={'query':vector,'filter':filters,'limit':top_k,'with_payload':True})
        return [{**p['payload'],'score':p['score']} for p in data['result']['points']]

    async def delete_document_chunks(self,kb_id,doc_id):
        try:
            await self.request('POST',f'/collections/{self.collection}/points/delete',params={'wait':'true'},json={'filter':{'must':[{'key':'knowledge_base_id','match':{'value':kb_id}},{'key':'document_id','match':{'value':doc_id}}]}})
        except httpx.HTTPStatusError as e:
            if e.response.status_code != 404: raise

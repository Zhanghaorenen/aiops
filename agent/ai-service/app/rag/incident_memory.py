"""只为已授权、已验证案例建立向量。检索后回源 MySQL 复核版本和权限。"""
import asyncio
import uuid

import httpx
from langchain_core.documents import Document
from qdrant_client import models


class IncidentMemorySearch:
    def __init__(self, platform, settings, resources, store=None):
        self.platform, self.settings, self.resources = platform, settings, resources
        self.store = store

    async def search(self, server_id: int, query: str):
        if not query.strip() or len(query) > 2000:
            raise ValueError('检索问题长度无效')
        cases = await self.platform.get_incident_cases(server_id, verified=True)
        # Java 决定权限范围；绝不接收前端传来的 owner_id 或任意 case_id 列表作为授权。
        cases = [c for c in cases if c.get('status') == 'VERIFIED'][:50]
        if not cases:
            return {'results': [], 'retrievalMethod': 'QDRANT', 'missingEvidence': ['没有获准访问的已验证案例']}
        store = self.store
        if store is None:
            _, store = self.resources.get(self.settings)
        vectorstore = await asyncio.to_thread(store._store)
        docs, ids, scope = [], [], []
        for case in cases:
            version = int(case['version'])
            text = f"{case['title']}\n症状：{case['symptoms']}\n已验证解决结果：{case['resolution']}"
            docs.append(Document(page_content=text, metadata={
                'kind': 'incident_case', 'case_id': case['id'], 'case_version': version,
                'server_id': server_id, 'owner_id': case['user_id'], 'scope': case['scope'],
                'case_status': 'VERIFIED', 'embedding_version': store.version,
            }))
            ids.append(str(uuid.uuid5(uuid.NAMESPACE_URL, f"case:{case['id']}:{version}:{store.version}")))
            scope.append(models.Filter(must=[
                models.FieldCondition(key='metadata.case_id', match=models.MatchValue(value=case['id'])),
                models.FieldCondition(key='metadata.case_version', match=models.MatchValue(value=version)),
            ]))
        await vectorstore.aadd_documents(documents=docs, ids=ids, batch_size=32)
        filters = models.Filter(must=[
            models.FieldCondition(key='metadata.kind', match=models.MatchValue(value='incident_case')),
            models.FieldCondition(key='metadata.server_id', match=models.MatchValue(value=server_id)),
            models.FieldCondition(key='metadata.case_status', match=models.MatchValue(value='VERIFIED')),
            models.FieldCondition(key='metadata.embedding_version', match=models.MatchValue(value=store.version)),
            models.Filter(should=scope),
        ])
        hits = await vectorstore.asimilarity_search_with_score(query=query, k=5, filter=filters)
        results = []
        for document, score in hits:
            meta = document.metadata
            try:
                current = await self.platform.get_incident_case(server_id, meta['case_id'])
            except httpx.HTTPStatusError as exc:
                if exc.response.status_code in (403, 404):
                    continue
                raise
            if current['status'] != 'VERIFIED' or current['version'] != meta['case_version']:
                continue
            results.append({'case_id': current['id'], 'version': current['version'], 'status': current['status'],
                            'title': current['title'], 'symptoms': current['symptoms'], 'resolution': current['resolution'],
                            'evidence_ids': current['evidence_json'], 'score': score})
        return {'results': results, 'retrievalMethod': 'QDRANT', 'scopeLimit': 50,
                'limitations': '历史经验与当前证据分别引用；仍须查询当前实时指标确认差异'}

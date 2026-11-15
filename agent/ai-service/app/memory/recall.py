import asyncio
import re
import httpx
from .schemas import safe, VOLATILE


def needs_memory(question):
    # Pure monitoring requests do not spend embeddings or LLM tokens on personal memory.
    return bool(re.search(r'偏好|习惯|记得|我的环境|之前.*(?:经验|流程)|推荐|建议|prefer|habit|workflow|my environment|remember', question, re.I))


class UserMemoryRecall:
    def __init__(self, platform, settings, resources, verified_user=None):
        self.platform,self.settings,self.resources=platform,settings,resources
        self.verified_user=verified_user

    async def __call__(self, question):
        if not needs_memory(question):
            return []
        try:
            async with asyncio.timeout(self.settings.memory_recall_timeout):
                user=self.verified_user or await self.platform.get_current_user()
                if type(user.get('id')) is not int or user['id']<=0:
                    return []
                index=self.resources.memory(self.settings)
                hits=await index.search(user['id'],question,self.settings.memory_top_k,self.settings.memory_score_threshold)
                if not hits:
                    return []
                current=await self.platform.verify_user_memories([{'id':h['id'],'version':h['version']} for h in hits])
                allowed={(m['id'],m['version']):m for m in current}
                result=[];size=0
                for hit in hits:
                    memory=allowed.get((hit['id'],hit['version']))
                    if not memory or not safe(memory['content']) or VOLATILE.search(memory['content']):
                        continue
                    text=memory['content'][:500]
                    if size+len(text)>self.settings.memory_context_chars: break
                    result.append({'memoryId':memory['id'],'version':memory['version'],'category':memory['category'],
                                   'content':text,'source':'PRIVATE_USER_MEMORY_UNTRUSTED'})
                    size+=len(text)
                return result
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code in (401,403): raise
            return []
        except Exception:
            # No fallback to unscoped vectors or unvalidated client-side content.
            return []

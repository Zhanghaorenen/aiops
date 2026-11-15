from .base import BaseAgent

KNOWLEDGE_TOOLS = ["search_knowledge","search_similar_incidents"]

class KnowledgeAgent(BaseAgent):
    name="knowledge"
    whitelist=KNOWLEDGE_TOOLS

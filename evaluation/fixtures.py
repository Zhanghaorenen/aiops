"""Human-labelled scenarios. Only the tool data plane is simulated; model and graph run normally."""

import hashlib
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

from langchain_core.documents import Document
from langchain_core.embeddings import Embeddings
from langchain_core.tools import StructuredTool
from qdrant_client import QdrantClient

from app.rag.hybrid import terms
from app.rag.vector_store import VectorStore
from app.rag.retrieval_service import RetrievalService
from app.tools.server_tool import ServerTool
from app.tools.gpu_tool import GpuTool
from app.tools.rag_tool import RagTool
from app.memory.recall import UserMemoryRecall


class FixtureEmbedding(Embeddings):
    version = "fixture-feature-hash-v1"

    def embed_query(self, text):
        values = [0.0] * 256
        for word in terms(text):
            values[hashlib.sha256(word.encode()).digest()[0]] += 1
        return values

    def embed_documents(self, texts):
        return [self.embed_query(text) for text in texts]


class FixturePlatform:
    def __init__(self, docs, version):
        self.docs = docs
        self.version = version

    async def get_server(self, server):
        if server != 41:
            raise PermissionError("UNKNOWN_FIXTURE_SERVER")
        return {"id": 41}

    async def get_server_list(self):
        return [{"id": 41, "name": "fixture-server"}]

    async def list_knowledge_bases(self):
        return [{"id": 1, "status": "ACTIVE"}]

    async def get_knowledge_base(self, kb):
        assert kb == 1
        return {"status": "ACTIVE"}

    async def list_knowledge_documents(self, kb):
        return [await self.get_knowledge_document(kb, i) for i in self.docs]

    async def get_knowledge_document(self, kb, i):
        assert kb == 1 and i in self.docs
        return {
            "id": i,
            "status": "READY",
            "indexVersion": 1,
            "embeddingVersion": self.version,
            "originalFilename": f"fixture-{i}.md",
        }

    async def verify_user_memories(self, references):
        return [
            {
                "id": 1,
                "version": 1,
                "category": "USER_PREFERENCE",
                "content": "我习惯保留额外的安全显存余量。",
            }
            for ref in references
            if ref == {"id": 1, "version": 1}
        ]


class FixtureMemoryIndex:
    async def search(self, user, question, limit, threshold):
        return [{"id": 1, "version": 1}] if user == 1 else []


async def corpus(cases, embedding=None):
    docs = {
        int(c.case_id.split("_")[1]): c.reference_document
        for c in cases
        if c.reference_document
    }
    embed = embedding or FixtureEmbedding()
    store = VectorStore("", "", embed.version, embed, client=QdrantClient(":memory:"))
    for i, content in docs.items():
        await store.upsert_chunks(
            1, i, 1, [Document(page_content=content)], f"fixture-{i}.md"
        )
    platform = FixturePlatform(docs, embed.version)
    return store, platform, RetrievalService(platform, embed, store)


def memory(platform):
    return UserMemoryRecall(
        platform,
        SimpleNamespace(
            memory_recall_timeout=2,
            memory_top_k=3,
            memory_score_threshold=0.7,
            memory_context_chars=1500,
        ),
        SimpleNamespace(memory=lambda _: FixtureMemoryIndex()),
        {"id": 1},
    )


class ToolSet:
    def __init__(self, tools):
        self.tools = tools

    def get_tools(self):
        return self.tools


def bind_tools(case, retrieval, calls):
    rag = RagTool(retrieval).get_tools()[0]

    def wrap(tool):
        async def invoke(**kwargs):
            entry = {"tool": tool.name, "arguments": kwargs, "ok": False}
            calls.append(entry)
            if (
                kwargs.get("server_id", 41) != 41
                or kwargs.get("job_id", 7) != 7
                or kwargs.get("gpu_id", 0) != 0
            ):
                raise ValueError("UNKNOWN_FIXTURE_TARGET")
            if tool.name == rag.name:
                data = await tool.ainvoke(kwargs)
            elif tool.name == "list_servers":
                data = [{"id": 41}]
            else:
                fields = case.fixture.get(tool.name)
                if fields is None:
                    raise ValueError("FIXTURE_EVIDENCE_UNAVAILABLE")
                stamp = datetime.now(timezone.utc) - timedelta(
                    seconds=fields.get("ageSeconds", 0)
                )
                data = {**fields, "serverId": 41, "collectedAt": stamp.isoformat()}
            entry.update(ok=True, data=data)
            return data

        return StructuredTool.from_function(
            name=tool.name,
            description=tool.description,
            args_schema=tool.args_schema,
            coroutine=invoke,
        )

    sources = ServerTool(None).get_tools() + GpuTool(None).get_tools() + [rag]
    return ToolSet(
        [
            wrap(t)
            for t in sources
            if t.name not in {"request_resource_relief", "get_private_memories"}
        ]
    )

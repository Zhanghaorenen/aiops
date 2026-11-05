"""Application-scoped resources; contains no user or authorization context."""
import asyncio
from threading import Lock
from .embedding_service import EmbeddingService
from .vector_store import VectorStore

class RagResources:
    def __init__(self):
        self._items = {}
        self._lock = Lock()

    def get(self, settings):
        with self._lock:
            if settings not in self._items:
                embedding = EmbeddingService(settings.embedding_path, settings.embedding_version,
                                             settings.embedding_device, settings.embedding_batch_size)
                store = VectorStore(settings.qdrant_url, settings.qdrant_key, settings.embedding_version,
                                    embedding, settings.rag_vector_schema_version)
                self._items[settings] = embedding, store
            return self._items[settings]

    async def close(self):
        with self._lock:
            items = list(self._items.values())
            self._items.clear()
        for _, store in items:
            await asyncio.to_thread(store.close)

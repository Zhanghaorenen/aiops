import asyncio
import hashlib
from pathlib import Path
import uuid
from fastapi import HTTPException
from .document_parser import parse_document, MIMES
from .text_splitter import split_documents


class IngestionService:
    def __init__(self, platform, embedding, store, settings):
        self.platform, self.embedding, self.store, self.settings = (
            platform,
            embedding,
            store,
            settings,
        )
        self.root = Path(settings.rag_storage_dir).resolve()

    def path(self, key):
        if len(key) != 32 or any(c not in "0123456789abcdef" for c in key):
            raise ValueError("INVALID_STORAGE_KEY")
        return self.root / key

    async def ingest_document(self, kb_id, file):
        kb = await self.platform.get_knowledge_base(kb_id)
        if kb["status"] != "ACTIVE":
            raise HTTPException(409, "知识库正在删除")
        filename = (file.filename or "").replace("\\", "/").rsplit("/", 1)[-1]
        mime = MIMES.get(Path(filename).suffix.lower())
        if not mime or len(filename) > 255:
            raise HTTPException(400, "仅支持 PDF/DOCX/MD/TXT")
        key = uuid.uuid4().hex
        self.root.mkdir(parents=True, exist_ok=True)
        path = self.path(key)
        doc = None
        try:
            size = 0
            digest = hashlib.sha256()
            with path.open("xb") as target:
                while data := await file.read(65536):
                    size += len(data)
                    if size > 10 * 1024 * 1024:
                        raise HTTPException(413, "文件不能超过 10 MiB")
                    digest.update(data)
                    target.write(data)
            if not size:
                raise HTTPException(400, "文件为空")
            doc = await self.platform.register_knowledge_document(
                kb_id,
                {
                    "originalFilename": filename,
                    "mimeType": mime,
                    "sha256": digest.hexdigest(),
                    "sizeBytes": size,
                    "storageKey": key,
                },
                self.settings.rag_service_key,
            )
            await self.platform.set_knowledge_status(
                doc["id"],
                {"status": "PROCESSING", "embeddingVersion": self.embedding.version},
                self.settings.rag_service_key,
            )
            docs = await asyncio.to_thread(parse_document, path, mime)
            chunks = split_documents(
                docs, self.settings.rag_chunk_size, self.settings.rag_chunk_overlap
            )
            await self.store.upsert_chunks(
                kb_id, doc["id"], doc["indexVersion"], chunks, filename
            )
            await self.platform.set_knowledge_status(
                doc["id"],
                {
                    "status": "READY",
                    "chunkCount": len(chunks),
                    "embeddingVersion": self.embedding.version,
                },
                self.settings.rag_service_key,
            )
            return await self.platform.get_knowledge_document(kb_id, doc["id"])
        except BaseException as exc:
            if doc:
                # Keep metadata and original on failure so deletion can retry vector cleanup.
                try:
                    await self.store.delete_document_chunks(kb_id, doc["id"])
                except Exception:
                    pass
                try:
                    code = (
                        str(exc)
                        if isinstance(exc, ValueError)
                        and str(exc).replace("_", "").isupper()
                        else "INDEX_FAILED"
                    )
                    await self.platform.set_knowledge_status(
                        doc["id"],
                        {
                            "status": "FAILED",
                            "errorCode": code[:80],
                            "embeddingVersion": self.embedding.version,
                        },
                        self.settings.rag_service_key,
                    )
                except Exception:
                    pass
            else:
                path.unlink(missing_ok=True)
            raise
        finally:
            await file.close()

    async def delete_document(self, kb_id, doc_id):
        doc = await self.platform.get_knowledge_document(kb_id, doc_id)
        await self.platform.mark_knowledge_document_deleting(kb_id, doc_id)
        # Use recorded embedding version to remove old collections after a model change.
        store = self.store
        if (
            doc.get("embeddingVersion")
            and doc["embeddingVersion"] != self.embedding.version
        ):
            from .vector_store import VectorStore

            store = VectorStore(
                self.settings.qdrant_url,
                self.settings.qdrant_key,
                doc["embeddingVersion"],
                schema_version=self.settings.rag_vector_schema_version,
            )
        try:
            await store.delete_document_chunks(kb_id, doc_id)
        finally:
            if store is not self.store:
                await asyncio.to_thread(store.close)
        self.path(doc["storageKey"]).unlink(missing_ok=True)
        await self.platform.purge_knowledge_document(
            doc_id, self.settings.rag_service_key
        )

    async def reindex_document(self, kb_id, doc_id):
        old = await self.platform.get_knowledge_document(kb_id, doc_id)
        path = self.path(old["storageKey"])
        if not path.is_file():
            raise HTTPException(404, "原文件不存在，不能重新索引")
        doc = await self.platform.prepare_knowledge_reindex(
            kb_id, doc_id, self.settings.rag_service_key
        )
        try:
            await self.platform.set_knowledge_status(
                doc_id,
                {"status": "PROCESSING", "embeddingVersion": self.embedding.version},
                self.settings.rag_service_key,
            )
            docs = await asyncio.to_thread(parse_document, path, doc["mimeType"])
            chunks = split_documents(
                docs, self.settings.rag_chunk_size, self.settings.rag_chunk_overlap
            )
            # 新旧版本通过 MySQL indexVersion 隔离；清掉当前模型下的旧分块再写入。
            await self.store.delete_document_chunks(kb_id, doc_id)
            await self.store.upsert_chunks(
                kb_id, doc_id, doc["indexVersion"], chunks, doc["originalFilename"]
            )
            await self.platform.set_knowledge_status(
                doc_id,
                {
                    "status": "READY",
                    "chunkCount": len(chunks),
                    "embeddingVersion": self.embedding.version,
                },
                self.settings.rag_service_key,
            )
            return await self.platform.get_knowledge_document(kb_id, doc_id)
        except BaseException:
            try:
                await self.platform.set_knowledge_status(
                    doc_id,
                    {
                        "status": "FAILED",
                        "errorCode": "INDEX_FAILED",
                        "embeddingVersion": self.embedding.version,
                    },
                    self.settings.rag_service_key,
                )
            except Exception:
                pass
            raise

import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch
import httpx
from app.main import create_app
from app.rag.ingestion_service import IngestionService
from app.config import Settings


class ReindexTests(unittest.IsolatedAsyncioTestCase):
    def setup_service(self, root):
        document = {
            "id": 2,
            "indexVersion": 2,
            "storageKey": "a" * 32,
            "mimeType": "text/markdown",
            "originalFilename": "test.md",
            "embeddingVersion": "test",
        }
        platform = SimpleNamespace(
            get_knowledge_document=AsyncMock(return_value=document),
            prepare_knowledge_reindex=AsyncMock(return_value=document),
            set_knowledge_status=AsyncMock(),
        )
        embedding = SimpleNamespace(version="test")
        store = SimpleNamespace(
            delete_document_chunks=AsyncMock(), upsert_chunks=AsyncMock()
        )
        service = IngestionService(
            platform,
            embedding,
            store,
            Settings(
                "http://localhost", rag_storage_dir=root, rag_service_key="x" * 32
            ),
        )
        return service, platform, store

    async def test_reindex_uses_existing_original_and_new_version(self):
        with tempfile.TemporaryDirectory() as root:
            service, platform, store = self.setup_service(root)
            service.path("a" * 32).write_text(
                "# GPU SOP\n\n核对采样时间和显存。", encoding="utf-8"
            )
            await service.reindex_document(1, 2)
            self.assertEqual(store.upsert_chunks.await_args.args[:3], (1, 2, 2))
            self.assertEqual(
                platform.set_knowledge_status.await_args.args[1]["status"], "READY"
            )
            self.assertTrue(service.path("a" * 32).exists())


    async def test_missing_original_never_resets_document_state(self):
        with tempfile.TemporaryDirectory() as root:
            service, platform, store = self.setup_service(root)
            with self.assertRaises(Exception):
                await service.reindex_document(1, 2)
            platform.prepare_knowledge_reindex.assert_not_awaited()
            store.delete_document_chunks.assert_not_awaited()

    async def test_index_failure_is_saved_without_losing_original(self):
        with tempfile.TemporaryDirectory() as root:
            service, platform, store = self.setup_service(root)
            service.path("a" * 32).write_text("# SOP\n\n真实文档内容", encoding="utf-8")
            store.upsert_chunks.side_effect = RuntimeError("dependency unavailable")
            with self.assertRaises(RuntimeError):
                await service.reindex_document(1, 2)
            self.assertEqual(
                platform.set_knowledge_status.await_args.args[1]["status"], "FAILED"
            )
            self.assertTrue(service.path("a" * 32).exists())


class KnowledgeAnswerTests(unittest.IsolatedAsyncioTestCase):
    async def request_answer(self, hits, *, error=None, authenticated=True):
        settings = Settings(
            "http://platform.test", "http://model.test", "test-model",
            "test-only-key", rag_service_key="r" * 32,
        )
        platform = SimpleNamespace(get_current_user=AsyncMock(), close=AsyncMock())
        model = SimpleNamespace(
            chat=AsyncMock(return_value=SimpleNamespace(content="依据文档回答")),
            close=AsyncMock(),
        )
        model.chat.side_effect = error
        factory = Mock(return_value=model)
        app = create_app(settings, lambda *_: platform, factory)
        app.state.rag_resources = SimpleNamespace(get=lambda _: (None, None))
        retrieval = SimpleNamespace(retrieve=AsyncMock(return_value={"results": hits}))
        with patch("app.api.knowledge.RetrievalService", return_value=retrieval):
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://ai.test"
            ) as client:
                response = await client.post(
                    "/api/rag/answer",
                    json={"query": "如何验证采样恢复？", "knowledge_base_ids": [1]},
                    headers={"Authorization": "Bearer test"} if authenticated else {},
                )
        return response, model, factory, retrieval

    async def test_no_sources_does_not_call_model(self):
        response, _, factory, _ = await self.request_answer([])
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["results"], [])
        factory.assert_not_called()

    async def test_answer_preserves_actual_sources_and_closes_model(self):
        hits = [{"document_title": "SOP.md", "chunk_id": "doc2-v2-00005", "content": "观察三个周期"}]
        response, model, _, retrieval = await self.request_answer(hits)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["results"], hits)
        retrieval.retrieve.assert_awaited_once_with("如何验证采样恢复？", [1], 5)
        self.assertIn("doc2-v2-00005", model.chat.await_args.args[0][1]["content"])
        model.close.assert_awaited_once()

    async def test_model_errors_are_sanitized_and_connection_closed(self):
        for error, status in [(RuntimeError("private-key-must-not-leak"), 502), (TimeoutError(), 504)]:
            with self.subTest(status=status):
                response, model, _, _ = await self.request_answer([{"content": "SOP"}], error=error)
                self.assertEqual(response.status_code, status)
                self.assertNotIn("private-key", response.text)
                model.close.assert_awaited_once()

    async def test_unauthenticated_request_cannot_retrieve_or_generate(self):
        response, _, factory, retrieval = await self.request_answer([], authenticated=False)
        self.assertEqual(response.status_code, 401)
        retrieval.retrieve.assert_not_awaited()
        factory.assert_not_called()


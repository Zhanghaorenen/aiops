import json
import os
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch, AsyncMock

import httpx
import jwt
from cryptography.hazmat.primitives.asymmetric import rsa
from langchain_core.documents import Document
from qdrant_client import QdrantClient
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
from app.agents.checkpoint import PlatformSaver, CheckpointUnavailable, clean
from app.rag.hybrid import terms, fuse, vector
from app.rag.vector_store import VectorStore
from app.rag.rebuild_sparse_index import rebuild
from app.mcp_server.server import create
from app.telemetry import request_trace, span, identify, inject_platform
from evaluation.dataset_loader import load
from evaluation.fixtures import FixtureEmbedding
from evaluation.metrics import ranking, aggregate


class EngineeringTests(unittest.IsolatedAsyncioTestCase):
    def test_trace_parent_propagation_and_safe_error(self):
        with patch.dict(os.environ, {"OTEL_ENABLED": "true"}):
            with request_trace(
                {"traceparent": "00-" + "a" * 32 + "-" + "b" * 16 + "-01"}
            ) as trace:
                identify(userId=7)
                with self.assertRaises(ValueError), span("test.tool", "tool"):
                    raise ValueError("example private body")
                self.assertEqual(trace.trace_id, "a" * 32)
                self.assertEqual(trace.spans[0]["error"], "ValueError")
                self.assertNotIn("private body", str(trace.snapshot()))

    async def test_trace_injected_only_inside_request(self):
        req = httpx.Request("GET", "http://platform/api")
        await inject_platform(req)
        self.assertNotIn("traceparent", req.headers)
        with patch.dict(os.environ, {"OTEL_ENABLED": "true"}), request_trace() as trace:
            await inject_platform(req)
            self.assertEqual(req.headers["traceparent"].split("-")[1], trace.trace_id)

    def test_terms_rrf_unique_and_metrics(self):
        ts = terms("显存压力 Xid 79 CUDA_ERROR_OUT_OF_MEMORY 550.54.15")
        for term in (
            "显存",
            "压力",
            "xid",
            "79",
            "cuda_error_out_of_memory",
            "550.54.15",
        ):
            self.assertIn(term, ts)
        self.assertEqual(vector("压力").indices, vector("压力", True).indices)
        a, b = ({"chunk_id": x, "score": 0.5} for x in "ab")
        rows = fuse([a, b, a], [b])
        self.assertEqual(rows[0]["chunk_id"], "b")
        self.assertAlmostEqual(rows[0]["rrf_score"], 1 / 61 + 1 / 62)
        self.assertEqual(ranking({"b"}, ["a", "b", "b"])["mrr_at_10"], 0.5)
        self.assertIsNone(
            aggregate([{"status": "BLOCKED_ENV"}])["metrics"]["p95_latency_ms"]["value"]
        )
        cases, _ = load()
        self.assertEqual(len(cases), 60)
        self.assertEqual(len({c.scenario_id for c in cases}), 60)

    async def test_sparse_migration_filter_delete_rerank_and_fallback(self):
        store = VectorStore(
            "", "", "v1", FixtureEmbedding(), client=QdrantClient(":memory:")
        )
        try:
            for kb, doc, v in [(1, 1, 1), (1, 1, 2), (2, 2, 2)]:
                await store.upsert_chunks(
                    kb,
                    doc,
                    v,
                    [Document(page_content="Xid 79 显卡从总线消失。")],
                    "fixture",
                )
            with tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "cursor.json"
                self.assertEqual(rebuild(store, path)["rows"], 3)
                self.assertEqual(rebuild(store, path)["rows"], 3)
            with patch.dict(
                os.environ,
                {
                    "AIOPS_HYBRID_ENABLED": "true",
                    "AIOPS_RERANKER_ENABLED": "true",
                    "AIOPS_RERANKER_PATH": "",
                },
            ):
                result = await store.search("Xid", [1], 5, [(1, 1, 2)])
                self.assertEqual(
                    [(r["document_id"], r["index_version"]) for r in result], [(1, 2)]
                )
                self.assertEqual(result[0]["retrieval_source"], ["dense", "bm25"])
                self.assertNotIn("rerank_score", result[0])
                with patch.object(store.lexical, "search", side_effect=TimeoutError):
                    hits = await store.search("Xid", [1], 5, [(1, 1, 2)])
                    self.assertEqual(hits[0]["retrieval_source"], ["dense"])
                await store.delete_document_chunks(1, 1)
                self.assertEqual(await store.search("Xid", [1], 5, [(1, 1, 2)]), [])
                self.assertEqual(store.client.count(store.lexical.collection).count, 1)
        finally:
            store.close()

    async def test_checkpoint_scope_secret_and_read_failure(self):
        saved = clean(
            {
                "jwt": "redactedvalue",
                "raw": "Bearer example-credential",
                "data": {"memory": 12},
                "envIRONment": {"x": "unsafe"},
            }
        )
        self.assertEqual(saved, {"raw": "[REDACTED]", "data": {"memory": 12}})
        client = SimpleNamespace(
            request=AsyncMock(side_effect=httpx.ConnectError("offline"))
        )
        saver = PlatformSaver(SimpleNamespace(client=client), 12, "fixture", "fixture")
        with self.assertRaises(ValueError):
            await saver.aget_tuple({"configurable": {"thread_id": "13"}})
        client.request.assert_not_called()
        with self.assertRaises(CheckpointUnavailable):
            await saver.aget_tuple({"configurable": {"thread_id": "12"}})

    async def test_http_mcp_authorization_origin_disconnect_and_delegation(self):
        private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        resource = "https://mcp.example.test/mcp"
        issuer = "https://id.example.test"

        def token(audience=resource, subject="fixture"):
            return jwt.encode(
                {
                    "iss": issuer,
                    "aud": audience,
                    "sub": subject,
                    "exp": int(time.time()) + 120,
                    "client_id": "test-client",
                    "scope": "aiops.read",
                },
                private,
                algorithm="RS256",
                headers={"kid": "local"},
            )

        downstream = []

        class Platform:
            def __init__(self, base, credential):
                downstream.append(credential)

            async def get_current_user(self):
                return {"id": 1}

            async def get_server(self, sid):
                return {"id": sid}

            async def close(self):
                pass

        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "delegations.json"
            path.write_text(
                json.dumps(
                    {
                        "fixture": {"user_id": 1, "user_jwt": "user-one"},
                        "mismatch": {"user_id": 2, "user_jwt": "user-one"},
                    }
                )
            )
            with (
                patch.dict(
                    os.environ,
                    {
                        "AIOPS_MCP_RESOURCE": resource,
                        "AIOPS_MCP_ISSUER": issuer,
                        "AIOPS_MCP_JWKS": issuer + "/jwks",
                        "AIOPS_MCP_DELEGATIONS": str(path),
                    },
                ),
                patch.object(
                    jwt.PyJWKClient,
                    "get_signing_key_from_jwt",
                    return_value=SimpleNamespace(key=private.public_key()),
                ),
            ):
                mcp = create(True, Platform)
                app = mcp.streamable_http_app()
                async with (
                    app.router.lifespan_context(app),
                    httpx.AsyncClient(
                        transport=httpx.ASGITransport(app=app),
                        base_url="https://mcp.example.test",
                    ) as http,
                ):
                    self.assertEqual(
                        (await http.post(resource, json={})).status_code, 401
                    )
                    http.headers["Authorization"] = "Bearer " + token(
                        "https://other.example.test"
                    )
                    self.assertEqual(
                        (await http.post(resource, json={})).status_code, 401
                    )
                    http.headers["Authorization"] = "Bearer " + token()
                    self.assertEqual(
                        (
                            await http.post(
                                resource,
                                json={},
                                headers={"Origin": "https://other.example.test"},
                            )
                        ).status_code,
                        403,
                    )
                    for subject in ("fixture", "mismatch", "fixture"):
                        http.headers["Authorization"] = "Bearer " + token(
                            subject=subject
                        )
                        async with streamable_http_client(
                            resource, http_client=http
                        ) as (rx, tx, _):
                            async with ClientSession(rx, tx) as session:
                                await session.initialize()
                                result = await session.call_tool(
                                    "get_server_status", {"server_id": 41}
                                )
                                self.assertEqual(result.isError, subject == "mismatch")
                                if not result.isError:
                                    self.assertEqual(
                                        result.structuredContent["data"]["id"], 41
                                    )
                    self.assertEqual(downstream, ["user-one"] * 3)

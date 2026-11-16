"""Versioned lexical index accompanying an unchanged dense Qdrant collection."""

from collections import Counter
import hashlib
import logging
import os
from pathlib import Path
import re
import time
from threading import Lock

from qdrant_client import models

log = logging.getLogger(__name__)
_TOKEN = re.compile(r"[\u3400-\u9fff]+|[a-z0-9]+(?:[._/-][a-z0-9]+)*", re.I)


def terms(text: str) -> list[str]:
    words = []
    for token in _TOKEN.findall(text.casefold()):
        if "\u3400" <= token[0] <= "\u9fff":
            words.extend(token)
            words.extend(token[i : i + 2] for i in range(len(token) - 1))
        else:
            words.append(token)
    return words


def vector(text: str, query: bool = False) -> models.SparseVector:
    tokens = terms(text)
    counts = Counter(
        int.from_bytes(hashlib.blake2s(t.encode(), digest_size=4).digest(), "big")
        for t in tokens
    )
    # BM25 k1=1.2, b=.75. Fixed reference chunk length is part of the index version.
    weight = 1.2 * (0.25 + 0.75 * len(tokens) / 256)
    ids = sorted(counts)
    return models.SparseVector(
        indices=ids,
        values=[1.0 if query else counts[i] * 2.2 / (counts[i] + weight) for i in ids],
    )


def fuse(dense: list, sparse: list) -> list:
    candidates = {}
    for name, hits in (("dense", dense), ("bm25", sparse)):
        for rank, hit in enumerate(hits, 1):
            row = candidates.setdefault(
                hit["chunk_id"], {**hit, "retrieval_source": [], "rrf_score": 0.0}
            )
            if name in row["retrieval_source"]:
                continue
            row["retrieval_source"].append(name)
            row["dense_rank" if name == "dense" else "sparse_rank"] = rank
            row[name + "_score"] = hit["score"]
            row["rrf_score"] += 1 / (60 + rank)
    result = sorted(candidates.values(), key=lambda r: (-r["rrf_score"], r["chunk_id"]))
    for rank, row in enumerate(result, 1):
        row.update(rrf_rank=rank, score=row["rrf_score"])
    return result


class LexicalIndex:
    def __init__(self, client, collection):
        self.client = client
        self.collection = collection + "_bm25_v1"
        self.ready = False
        self.lock = Lock()

    def ensure(self):
        with self.lock:
            if self.ready:
                return
            if not self.client.collection_exists(self.collection):
                try:
                    self.client.create_collection(
                        self.collection,
                        vectors_config={},
                        sparse_vectors_config={
                            "bm25": models.SparseVectorParams(
                                modifier=models.Modifier.IDF
                            )
                        },
                    )
                except Exception:
                    if not self.client.collection_exists(self.collection):
                        raise
            self.ready = True

    def put(self, ids, docs):
        self.ensure()
        points = [
            models.PointStruct(
                id=i,
                vector={"bm25": vector(doc.page_content)},
                payload={"page_content": doc.page_content, "metadata": doc.metadata},
            )
            for i, doc in zip(ids, docs, strict=True)
        ]
        for i in range(0, len(points), 64):
            self.client.upsert(self.collection, points[i : i + 64], wait=True)

    def search(self, query, filters, limit):
        result = self.client.query_points(
            self.collection,
            query=vector(query, True),
            using="bm25",
            query_filter=filters,
            limit=limit,
            with_payload=True,
        )
        return [
            {
                **p.payload["metadata"],
                "content": p.payload["page_content"],
                "score": p.score,
            }
            for p in result.points
        ]


class LocalReranker:
    def __init__(self):
        self.lock = Lock()
        self.model = None
        self.retry_at = 0

    def rank(self, query, candidates):
        if not candidates or time.monotonic() < self.retry_at:
            return candidates
        with self.lock:
            if time.monotonic() < self.retry_at:
                return candidates
            try:
                if self.model is None:
                    from sentence_transformers import CrossEncoder

                    path = os.getenv("AIOPS_RERANKER_PATH", "")
                    if not path or not Path(path).is_dir():
                        raise ValueError("RERANKER_PATH")
                    self.model = CrossEncoder(
                        path,
                        device=os.getenv("AIOPS_RERANKER_DEVICE", "cpu"),
                        local_files_only=True,
                        trust_remote_code=False,
                    )
                scores = self.model.predict(
                    [(query, c["content"]) for c in candidates], batch_size=8
                )
                ranked = [
                    {**c, "rerank_score": float(s)}
                    for c, s in zip(candidates, scores, strict=True)
                ]
                return sorted(ranked, key=lambda c: -c["rerank_score"])
            except Exception as exc:
                self.retry_at = time.monotonic() + 60
                log.warning("Reranker unavailable: %s", type(exc).__name__)
                return candidates

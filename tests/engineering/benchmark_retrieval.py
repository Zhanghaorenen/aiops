"""Benchmark reviewed fixture documents on local Dense/BM25 and optional real CrossEncoder weights."""

import argparse
import asyncio
import json
import math
import os
import statistics
import time
from pathlib import Path

from evaluation.dataset_loader import load
from evaluation.fixtures import corpus
from evaluation.metrics import ranking
from app.rag.embedding_service import EmbeddingService
from app.telemetry import request_trace


async def benchmark(args):
    os.environ.update(
        AIOPS_HYBRID_ENABLED="true",
        OTEL_ENABLED="true",
        AIOPS_RERANKER_PATH=str(args.reranker),
    )
    cases, _ = load()
    embedding = EmbeddingService(
        str(args.embedding), "benchmark-local-bge", device="cpu"
    )
    store, platform, retrieval = await corpus(cases, embedding)
    rows = []
    try:
        for name in ("dense", "hybrid", "rerank"):
            os.environ["AIOPS_HYBRID_ENABLED"] = str(name != "dense")
            os.environ["AIOPS_RERANKER_ENABLED"] = str(name == "rerank")
            for c in cases:
                if not c.relevant_chunks:
                    continue
                with request_trace() as trace:
                    started = time.perf_counter()
                    hits = (await retrieval.retrieve(c.question, top_k=10))["results"]
                    elapsed = (time.perf_counter() - started) * 1000
                if name == "rerank" and not all(
                    h.get("rerank_score") is not None for h in hits
                ):
                    raise RuntimeError("RERANKER_WAS_NOT_USED")
                rows.append(
                    {
                        "mode": name,
                        "case": c.case_id,
                        "ms": round(elapsed, 2),
                        "reranker_ms": next(
                            (
                                s["durationMs"]
                                for s in trace.spans
                                if s["name"] == "rag.rerank"
                            ),
                            None,
                        ),
                        **ranking(
                            set(c.relevant_chunks), [r["chunk_id"] for r in hits]
                        ),
                    }
                )
    finally:
        store.close()
    report = {
        "provenance": "labelled fixture documents; local CPU models; in-memory Qdrant client",
        "rows": rows,
    }
    for mode in ("dense", "hybrid", "rerank"):
        group = [r for r in rows if r["mode"] == mode]
        report[mode] = {
            key: statistics.mean(r[key] for r in group)
            for key in ("ms", "mrr_at_10", "ndcg_at_10", "recall_at_5")
        }
        report[mode]["p95_ms"] = sorted(r["ms"] for r in group)[
            math.ceil(0.95 * len(group)) - 1
        ]
    report["ndcg_improvement"] = (
        report["rerank"]["ndcg_at_10"] - report["hybrid"]["ndcg_at_10"]
    )
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for flag in ("embedding", "reranker", "output"):
        parser.add_argument("--" + flag, type=Path, required=True)
    asyncio.run(benchmark(parser.parse_args()))

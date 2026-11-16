import math
import re
from statistics import mean


def ratio(a, b):
    return a / b if b else None


def ranking(relevant, ranking):
    # One gain per unique labelled chunk, binary relevance.
    ranks = [i for i, key in enumerate(dict.fromkeys(ranking), 1) if key in relevant]
    gain = sum(1 / math.log2(i + 1) for i in ranks if i <= 10)
    ideal = sum(1 / math.log2(i + 1) for i in range(1, min(10, len(relevant)) + 1))
    return {
        "recall_at_5": ratio(sum(i <= 5 for i in ranks), len(relevant)),
        "mrr_at_10": 1 / min(ranks) if ranks and min(ranks) <= 10 else 0,
        "ndcg_at_10": ratio(gain, ideal),
    }


def score(case, row):
    calls = row["calls"]
    good = [c for c in calls if c["ok"]]
    used = {c["tool"] for c in calls}
    evidence = {c["tool"] for c in good}
    docs = next(
        (c["data"]["results"] for c in good if c["tool"] == "search_knowledge"), []
    )
    evidence.update(d["chunk_id"] for d in docs)
    answer = row["answer"].split("证据来源\n")[0]
    coverage = len(evidence.intersection(case.required_evidence)) / len(
        case.required_evidence
    )
    findings = sum(bool(re.search(f, answer, re.I)) for f in case.expected_findings)
    forbidden = sum(bool(re.search(f, answer, re.I)) for f in case.forbidden_claims)
    references = re.findall(r"E-[a-f0-9]{32}", answer)
    index = row.get("evidence", {})
    known = [index.get(r) for r in references]
    stale = [
        ref for refs in row.get("observations", []) for ref in refs if ref in index
    ]
    uncertain = bool(
        re.search(
            "缺少|不足|不能|不代表|未确认|不一定|无法|待验证|不能确定|不等于|尚未|未知",
            answer,
        )
    )
    applicable = case.expected_behavior == "qualify_uncertainty"
    matched = (
        coverage == 1
        and findings == len(case.expected_findings)
        and forbidden == 0
        and (not applicable or uncertain)
    )
    metrics = {
        "routing_accuracy": None
        if row["mode"] == "A"
        else int(set(row["agents"]) == set(case.expected_agents)),
        "tool_selection_accuracy": int(used == set(case.expected_tools)),
        "tool_success_rate": ratio(len(good), len(calls)),
        "agent_completion_rate": int(matched),
        "evidence_coverage": coverage,
        "evidence_citation_accuracy": ratio(
            sum(v is not None for v in known), len(known)
        ),
        "unsupported_claim_rate": ratio(forbidden, forbidden + findings),
        "stale_evidence_usage_rate": ratio(
            sum(index[k] == "STALE" for k in stale), len(stale)
        ),
        "diagnosis_accuracy": int(matched),
        "finding_precision": ratio(findings, findings + forbidden),
        "finding_recall": findings / len(case.expected_findings),
        "uncertainty_handling_accuracy": int(uncertain and forbidden == 0)
        if applicable
        else None,
    }
    if case.relevant_chunks:
        metrics.update(
            ranking(set(case.relevant_chunks), row.get("retriever_ranking", []))
        )
        if row.get("pre_rerank"):
            before = ranking(set(case.relevant_chunks), row["pre_rerank"])
            metrics["reranker_improvement"] = (
                metrics["ndcg_at_10"] - before["ndcg_at_10"]
            )
    return metrics


def aggregate(rows):
    from collections import Counter

    measured = [r for r in rows if r["status"] in ("PASS", "FAIL")]
    keys = sorted(
        {k for r in measured for k in r["metrics"]}
        | {"reranker_improvement", "token_usage", "estimated_cost"}
    )
    result = {}
    for k in keys:
        xs = [r["metrics"][k] for r in measured if r["metrics"].get(k) is not None]
        result[k] = {"value": mean(xs) if xs else None, "n": len(xs)}
    times = sorted(r["latency_ms"] for r in rows if r.get("latency_ms") is not None)
    for k, q in (
        ("average_latency_ms", None),
        ("p50_latency_ms", 0.5),
        ("p95_latency_ms", 0.95),
    ):
        result[k] = {
            "value": (
                mean(times) if q is None else times[math.ceil(q * len(times)) - 1]
            )
            if times
            else None,
            "n": len(times),
        }
    return {
        "status": dict(Counter(r["status"] for r in rows)),
        "total": len(rows),
        "metrics": result,
    }

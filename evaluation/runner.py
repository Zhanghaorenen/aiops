"""Run from the repository root. Every invocation gets an immutable result directory."""

import argparse
import asyncio
import json
import os
from pathlib import Path
import sys
import time
from uuid import uuid4
from .dataset_loader import ROOT, digest, load

AI = ROOT.parent / "agent/ai-service"
sys.path.insert(0, str(AI))
from app.agents.graph_agent import GraphAgent
from app.agents.multi_agent.supervisor import MultiAgentSupervisor
from app.config import Settings
from app.llm.client import LLMClient
from app.rag.embedding_service import EmbeddingService
from app.telemetry import request_trace, identify
from .fixtures import bind_tools, corpus, ToolSet, memory
from .metrics import score
from .report import persist, write


class ExperimentSupervisor(MultiAgentSupervisor):
    # Ablation only inside the offline fixture harness; deployed agents always validate evidence.
    validate_findings = True

    async def validate(self, state):
        return (
            await super().validate(state)
            if self.validate_findings
            else {"findings": state.get("findings", [])}
        )


async def execute(case, mode, args, llm, platform, retrieval, run_id):
    row = {
        "case_id": case.case_id,
        "question": case.question,
        "scenario_id": case.scenario_id,
        "mode": mode,
        "calls": [],
        "agents": [],
        "evidence": {},
        "observations": [],
        "answer": "",
        "metrics": {},
        "status": "ERROR",
    }
    if not llm:
        return {**row, "status": "BLOCKED_ENV", "reason": "LLM_NOT_CONFIGURED"}
    started = time.perf_counter()
    with request_trace() as trace:
        identify(evaluationRunId=run_id, caseId=case.case_id, agentMode=mode)
        row["trace_id"] = trace.trace_id
        try:
            tools = bind_tools(case, retrieval, row["calls"])
            use_memory = (
                (mode == "C") if args.memory == "default" else args.memory == "on"
            )
            recall = memory(platform) if use_memory else None
            if mode == "A":
                agent = GraphAgent(llm, tools, ToolSet([]), memory_recall=recall)
                row["answer"] = await asyncio.wait_for(
                    agent.answer(case.question), args.timeout
                )
            else:
                agent = ExperimentSupervisor(
                    llm,
                    tools,
                    ToolSet([]),
                    platform=platform,
                    chat_timeout=args.timeout,
                    memory_recall=recall,
                )
                agent.validate_findings = args.validation != "off"
                state = await asyncio.wait_for(
                    agent.graph.ainvoke(agent.initial(case.question, [])), args.timeout
                )
                row.update(
                    answer=state["final_answer"],
                    agents=list(state["agent_results"]),
                    evidence={
                        e.evidence_id: e.status for e in state.get("evidence_index", [])
                    },
                    observations=[
                        f.supporting_evidence_ids
                        for f in state.get("findings", [])
                        if f.finding_type == "observation"
                    ],
                )
            row["latency_ms"] = round((time.perf_counter() - started) * 1000)
            if case.relevant_chunks:
                ranked = (await retrieval.retrieve(case.question, top_k=10))["results"]
                row["retriever_ranking"] = [d["chunk_id"] for d in ranked]
                if any(d.get("rerank_score") is not None for d in ranked):
                    row["pre_rerank"] = [
                        d["chunk_id"]
                        for d in sorted(ranked, key=lambda d: d["rrf_rank"])
                    ]
            row["metrics"] = score(case, row)
            row["status"] = (
                "PASS"
                if row["metrics"]["diagnosis_accuracy"]
                and row["metrics"]["tool_selection_accuracy"]
                and row["metrics"]["routing_accuracy"] != 0
                else "FAIL"
            )
            if args.judge:
                from .judge import judge

                try:
                    row["judge"] = await judge(llm, case, row)
                except Exception as exc:
                    row["judge_error"] = type(exc).__name__
        except Exception as exc:
            row.update(
                reason=type(exc).__name__,
                latency_ms=round((time.perf_counter() - started) * 1000),
            )
        row["spans"] = trace.snapshot()["spans"]
        generations = [s for s in row["spans"] if s["type"] == "generation"]
        if generations and all(
            "gen_ai.usage.input_tokens" in s and "gen_ai.usage.output_tokens" in s
            for s in generations
        ):
            i = sum(s["gen_ai.usage.input_tokens"] for s in generations)
            o = sum(s["gen_ai.usage.output_tokens"] for s in generations)
            row["metrics"]["token_usage"] = i + o
            if args.input_price is not None and args.output_price is not None:
                row["metrics"]["estimated_cost"] = (
                    i * args.input_price + o * args.output_price
                ) / 1e6
    return row


async def main(args):
    all_cases, version = load()
    cases = [
        c
        for c in all_cases
        if (not args.dataset or c.category == args.dataset)
        and (not args.case or c.case_id == args.case)
    ]
    if not cases:
        raise ValueError("No matching case")
    cases = cases[: args.limit]
    settings = Settings.from_env()
    os.environ["AIOPS_HYBRID_ENABLED"] = str(args.rag != "dense").lower()
    os.environ["AIOPS_RERANKER_ENABLED"] = str(args.rag == "rerank").lower()
    os.environ["OTEL_ENABLED"] = "true"
    if args.trace_content:
        os.environ["AIOPS_EVAL_TRACE_CONTENT"] = "true"
    embed = (
        EmbeddingService(
            settings.embedding_path,
            settings.embedding_version,
            settings.embedding_device,
        )
        if args.embedding == "local"
        else None
    )
    model = args.model or settings.llm_model
    llm = (
        LLMClient(
            model, settings.llm_base_url, settings.llm_api_key, settings.llm_timeout
        )
        if model and settings.llm_base_url and settings.llm_api_key
        else None
    )
    store, platform, retrieval = await corpus(all_cases, embed)
    run_id = str(uuid4())
    directory = args.output / run_id
    directory.mkdir(parents=True)
    modes = list(dict.fromkeys(args.modes))
    manifest = {
        "evaluation_run_id": run_id,
        "dataset_version": version,
        "model_name": model or "unconfigured",
        "prompt_version": digest(
            [
                *(AI / "app/agents/multi_agent/prompts").glob("*.md"),
                AI / "app/agents/graph_agent.py",
                AI / "app/agents/multi_agent/supervisor.py",
            ]
        ),
        "evaluation_version": digest([ROOT/"metrics.py",ROOT/"runner.py",ROOT/"judge.py"]),
        "fixture_version": digest([ROOT/"fixtures.py"]),
        "tool_version": digest((AI / "app/tools").glob("*.py")),
        "kb_version": digest([ROOT / "datasets/rag_retrieval.jsonl"]),
        "embedding": args.embedding,
        "retrieval_config": args.rag,
        "modes": modes,
        "memory": args.memory,
        "validation": args.validation,
        "judge": "judge-v1" if args.judge else None,
        "started_at": time.time(),
        "elapsed_ms": 0,
        "source": "labelled_fixtures",
    }
    rows = []
    sem = asyncio.Semaphore(args.concurrency)

    async def one(case, mode):
        async with sem:
            row = await execute(case, mode, args, llm, platform, retrieval, run_id)
            rows.append(row)
            with (directory / "results.jsonl").open("a", encoding="utf-8") as f:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")

    try:
        await asyncio.gather(*(one(case, mode) for case in cases for mode in modes))
    finally:
        if llm:
            await llm.close()
        store.close()
        manifest["elapsed_ms"] = round((time.time() - manifest["started_at"]) * 1000)
        report = write(directory, manifest, rows)
        print(directory, flush=True)
    if args.mysql:
        persist(report)


def cli():
    parser = argparse.ArgumentParser()
    parser.add_argument("--modes", nargs="+", choices=list("ABC"), default=list("ABC"))
    parser.add_argument("--dataset")
    parser.add_argument("--case")
    parser.add_argument("--model")
    parser.add_argument("--limit", type=int)
    parser.add_argument(
        "--rag", choices=["dense", "hybrid", "rerank"], default="hybrid"
    )
    parser.add_argument("--embedding", choices=["fixture", "local"], default="fixture")
    parser.add_argument("--memory", choices=["default", "on", "off"], default="default")
    parser.add_argument("--validation", choices=["on", "off"], default="on")
    parser.add_argument(
        "--trace-content",
        action="store_true",
        help="include bounded redacted fixture prompt/response in evaluation OTLP only",
    )
    parser.add_argument("--judge", action="store_true")
    parser.add_argument("--mysql", action="store_true")
    parser.add_argument("--timeout", type=float, default=90)
    parser.add_argument("--concurrency", type=int, choices=range(1, 13), default=3)
    parser.add_argument(
        "--input-price", type=float, help="price per million input tokens"
    )
    parser.add_argument(
        "--output-price", type=float, help="price per million output tokens"
    )
    parser.add_argument("--output", type=Path, default=ROOT.parent / "data/evaluations")
    # Parse the YAML through the same option validators as explicit command-line input.
    import yaml
    pre=argparse.ArgumentParser(add_help=False)
    pre.add_argument("--config",type=Path,default=ROOT/"configs/evaluation.yaml")
    config,overrides=pre.parse_known_args()
    parser.add_argument("--config",type=Path,help="experiment YAML; command-line flags override it")
    settings=yaml.safe_load(config.config.read_text(encoding="utf-8"))
    allowed={"modes","dataset","case","model","limit","rag","embedding","memory","validation","timeout","concurrency","input-price","output-price"}
    if not isinstance(settings,dict) or set(settings)-allowed:
        parser.error("unknown experiment configuration")
    flags=[]
    for key,value in settings.items():
        values=value if isinstance(value,list) else [value]
        flags.extend(["--"+key,*map(str,values)])
    args=parser.parse_args(flags+overrides)
    if args.timeout<=0 or (args.limit is not None and args.limit<=0):
        parser.error("limit and timeout must be positive")
    return args


if __name__ == "__main__":
    asyncio.run(main(cli()))

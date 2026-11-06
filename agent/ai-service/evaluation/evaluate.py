"""真实服务评测入口。只读取平台数据，不替用户制造设备/模型成功记录。"""

import argparse
import json
import os
from pathlib import Path
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-id", type=int)
    parser.add_argument("--limit", type=int, default=30)
    parser.add_argument("--output", default="evaluation/results.local.json")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    cases = json.loads(
        Path(__file__).with_name("cases.json").read_text(encoding="utf-8")
    )[: args.limit]
    if args.dry_run:
        print(
            json.dumps(
                {
                    "case_count": len(cases),
                    "status": "NOT_EXECUTED",
                    "reason": "仅检查问题集，不代表语义质量或真实设备验收",
                },
                ensure_ascii=False,
            )
        )
        return
    token = os.getenv("AIOPS_EVALUATION_TOKEN", "")
    if not token or not args.server_id:
        parser.error("需要 AIOPS_EVALUATION_TOKEN 与 --server-id，凭据不会写入结果")
    ai = os.getenv("AIOPS_EVALUATION_AI_URL", "http://127.0.0.1:8000").rstrip("/")
    results = []
    for case in cases:
        started = time.perf_counter()
        tools = []
        sources = []
        answer = ""
        error = None
        request = urllib.request.Request(
            ai + "/api/chat/stream",
            method="POST",
            data=json.dumps(
                {
                    "question": f"服务器 ID={args.server_id}。{case['question']}",
                    "history": [],
                }
            ).encode(),
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + token,
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                for line in response:
                    if not line.startswith(b"data:"):
                        continue
                    event = json.loads(line[5:])
                    if event["type"] == "token":
                        answer += event["text"]
                    elif event["type"] == "tool_start":
                        tools.append(event["name"])
                    elif event["type"] == "sources":
                        sources.extend(event["results"])
                    elif event["type"] == "error":
                        error = event.get("status", "STREAM_ERROR")
        except Exception as exc:
            error = type(exc).__name__
        results.append(
            {
                "id": case["id"],
                "question": case["question"],
                "elapsed_seconds": round(time.perf_counter() - started, 3),
                "tools": tools,
                "tool_count": len(tools),
                "tool_check": not case["expected_tools_any"]
                or bool(set(tools) & set(case["expected_tools_any"])),
                "source_check": not case["requires_document_source"] or bool(sources),
                "sources": sources,
                "answer": answer,
                "error_code": error,
                "human_fact_review": "PENDING",
            }
        )
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(
            {
                "status": "EXECUTED_REQUIRES_HUMAN_FACT_REVIEW",
                "server_id": args.server_id,
                "results": results,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"记录 {len(results)} 个实际请求；事实正确性与来源准确率仍需人工核对。")


if __name__ == "__main__":
    main()

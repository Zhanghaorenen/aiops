import csv
import json
import os
from .metrics import aggregate


def write(directory, manifest, rows):
    modes = {
        m: aggregate([r for r in rows if r["mode"] == m]) for m in manifest["modes"]
    }
    report = {**manifest, "modes": modes}
    (directory / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    keys = sorted({k for v in modes.values() for k in v["metrics"]})
    table = [["指标", *modes]]
    for k in keys:
        values = [m["metrics"].get(k, {}) for m in modes.values()]
        table.append(
            [
                k,
                *[
                    f"{v['value']:.4f} (n={v['n']})"
                    if v.get("value") is not None
                    else "—"
                    for v in values
                ],
            ]
        )
    with (directory / "report.csv").open("w", encoding="utf-8-sig", newline="") as f:
        csv.writer(f).writerows(table)
    lines = [
        "# 模型质量对比",
        "",
        "数据为人工标注场景，工具按场景提供观测，Agent 和模型实际执行。",
        "",
        " | ".join(table[0]),
        " | ".join("---" for _ in table[0]),
        *[" | ".join(r) for r in table[1:]],
        "",
        "## 运行状态",
        "",
        *[f"- {m}：{v['status']}" for m, v in modes.items()],
    ]
    by_case = {}
    for r in rows:
        by_case.setdefault(r["case_id"], {})[r["mode"]] = r["status"]
    diffs = [
        f"- {c} · " + ", ".join(f"{m} {s}" for m, s in statuses.items())
        for c, statuses in by_case.items()
        if len(set(statuses.values())) > 1
    ]
    lines.extend(
        ["", "## 同题差异", "", *(diffs or ["本次可对比的样本未出现状态差异。"])]
    )
    (directory / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return report


def persist(report):
    import pymysql

    conn = pymysql.connect(
        host=os.getenv("AIOPS_EVAL_DB_HOST", "127.0.0.1"),
        port=int(os.getenv("AIOPS_EVAL_DB_PORT", "3306")),
        user=os.environ["AIOPS_EVAL_DB_USER"],
        password=os.environ["AIOPS_EVAL_DB_PASSWORD"],
        database=os.environ["AIOPS_EVAL_DB_NAME"],
    )
    try:
        with conn.cursor() as cur:
            for mode, results in report["modes"].items():
                cur.execute(
                    "INSERT INTO ops_evaluation_run (evaluation_run_id,agent_mode,dataset_version,model_name,prompt_version,retrieval_config,execution_time,metrics_json,result_status) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                    (
                        report["evaluation_run_id"],
                        mode,
                        report["dataset_version"],
                        report["model_name"],
                        report["prompt_version"],
                        report["retrieval_config"],
                        report["elapsed_ms"],
                        json.dumps(results),
                        "PARTIAL"
                        if results["status"].get("ERROR")
                        or results["status"].get("BLOCKED_ENV")
                        else "COMPLETED",
                    ),
                )
        conn.commit()
    finally:
        conn.close()

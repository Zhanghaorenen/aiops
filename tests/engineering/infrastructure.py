"""Start disposable loopback-only containers. No volume or connection to the application database."""

import argparse
import asyncio
from contextlib import ExitStack
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import sys
import shutil

import httpx
import pymysql
from fastapi import HTTPException
from langchain_core.documents import Document
from qdrant_client import QdrantClient
from app.rag.vector_store import VectorStore
from app.config import Settings
from app.redis_support import ChatRateLimiter
from evaluation.fixtures import FixtureEmbedding
from evaluation.report import persist


def docker(*args, env=None):
    return subprocess.check_output(["docker", *args], text=True, env=env).strip()


def container(stack, image, port, env):
    # Values never enter CLI arguments. Docker assigns the published ephemeral loopback port.
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        host_port = s.getsockname()[1]
    args = [
        "run",
        "-d",
        "--label",
        "aiops.engineering=fixture",
        "-p",
        f"127.0.0.1:{host_port}:{port}",
    ]
    for key in env:
        args += ["-e", key]
    cid = docker(*args, image, env={**os.environ, **env})
    stack.callback(docker, "rm", "-f", "-v", cid)
    return cid, int(docker("port", cid, f"{port}/tcp").split(":")[-1])


async def run(args):
    results = {}
    with ExitStack() as stack:
        redis, port = container(stack, "redis:8.2-alpine", 6379, {})
        limiter = ChatRateLimiter(
            Settings(backend_url="http://127.0.0.1", redis_port=port, redis_timeout=0.4)
        )
        try:
            for i in range(30):
                if (await limiter.stats())["status"] == "UP":
                    break
                await asyncio.sleep(0.2)
            await limiter.check({"id": 17})
            docker("stop", "-t", "1", redis)
            try:
                await limiter.check({"id": 17})
            except HTTPException as exc:
                assert exc.status_code == 503
            else:
                raise AssertionError("limiter accepted request during outage")
            docker("start", redis)
            for i in range(30):
                if (await limiter.stats())["status"] == "UP":
                    break
                await asyncio.sleep(0.2)
            await limiter.check({"id": 17})
            results["redis"] = "503 during outage, recovered after restart"
        finally:
            await limiter.close()
        key = secrets.token_hex(24)
        qid, port = container(
            stack, "qdrant/qdrant:v1.13.6", 6333, {"QDRANT__SERVICE__API_KEY": key}
        )
        endpoint = f"http://127.0.0.1:{port}"
        for i in range(40):
            try:
                if httpx.get(
                    endpoint + "/readyz", headers={"api-key": key}, timeout=1
                ).is_success:
                    break
            except httpx.TransportError:
                pass
            await asyncio.sleep(0.25)
        os.environ["AIOPS_HYBRID_ENABLED"] = "true"
        client = QdrantClient(
            url=endpoint, api_key=key, timeout=3, check_compatibility=False
        )
        store = VectorStore(
            endpoint, key, "isolated-fixture", FixtureEmbedding(), client=client
        )
        try:
            await store.upsert_chunks(
                1, 3, 1, [Document(page_content="Xid 79 GPU 总线检查")], "fixture"
            )
            hits = await store.search("Xid 79", [1], 5, [(1, 3, 1)])
            assert hits[0]["retrieval_source"] == ["dense", "bm25"]
            docker("stop", "-t", "1", qid)
            try:
                await store.search("Xid 79", [1], 5, [(1, 3, 1)])
            except Exception as exc:
                results["qdrant_outage"] = type(exc).__name__
            else:
                raise AssertionError("expected unavailable dependency")
            results["qdrant_version"] = "1.13.6"
        finally:
            store.close()
        if args.mysql:
            pwd = secrets.token_hex(32)
            _, port = container(
                stack,
                "mysql:8.4",
                3306,
                {
                    "MYSQL_ROOT_PASSWORD": pwd,
                    "MYSQL_DATABASE": "engineering",
                    "MYSQL_ROOT_HOST": "%",
                },
            )
            for i in range(90):
                try:
                    conn = pymysql.connect(
                        host="127.0.0.1",
                        port=port,
                        user="root",
                        password=pwd,
                        database="engineering",
                        connect_timeout=2,
                    )
                    break
                except pymysql.err.OperationalError:
                    if i == 89:
                        raise
                    await asyncio.sleep(1)
            try:
                if args.worker_suite or args.restart_jvm:
                    env = {
                        **os.environ,
                        "AIOPS_ENGINEERING_MYSQL_PORT": str(port),
                        "AIOPS_ENGINEERING_MYSQL_PASSWORD": pwd,
                        "AIOPS_ENGINEERING_PYTHON": sys.executable,
                        "OTEL_ENABLED": "true",
                        "OTEL_TRACES_EXPORTER": "none",
                        "OTEL_METRICS_EXPORTER": "none",
                        "OTEL_LOGS_EXPORTER": "none",
                    }
                    with args.output.with_suffix(".maven.log").open(
                        "w", encoding="utf-8"
                    ) as log:
                        phases=["prepare","resume"] if args.restart_jvm else ["suite"]
                        for phase in phases:
                            selector="EngineeringRecoveryTests"
                            if phase!="suite":
                                selector+="#recoverAcrossJvms"
                                env.update(AIOPS_ENGINEERING_RESTART_PHASE=phase,
                                           AIOPS_ENGINEERING_RESTART_FILE=str(args.output.with_suffix(".run-id").resolve()))
                            subprocess.run([shutil.which("mvn.cmd") or shutil.which("mvn"), "-q", "-Dtest="+selector,
                                "-DargLine=-javaagent:"+os.environ["AIOPS_TEST_OTEL_JAR"],"test"],
                                env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=360)
                        results["worker_validation"]=phases
                else:
                    with conn.cursor() as cur:
                        cur.execute(
                            Path(
                                "src/main/resources/db/migration/V21__evaluation_runs.sql"
                            ).read_text()
                        )
                os.environ.update(
                    AIOPS_EVAL_DB_USER="root",
                    AIOPS_EVAL_DB_PASSWORD=pwd,
                    AIOPS_EVAL_DB_HOST="127.0.0.1",
                    AIOPS_EVAL_DB_NAME="engineering",
                    AIOPS_EVAL_DB_PORT=str(port),
                )
                persist(json.loads(args.report.read_text(encoding="utf-8")))
                with conn.cursor() as cur:
                    cur.execute(
                        "SELECT agent_mode,result_status FROM ops_evaluation_run ORDER BY agent_mode"
                    )
                    results["mysql_evaluation_runs"] = cur.fetchall()
                    assert len(results["mysql_evaluation_runs"]) == 3
            finally:
                conn.close()
    args.output.write_text(json.dumps(results, indent=2), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker-suite", action="store_true")
    parser.add_argument("--restart-jvm", action="store_true")
    parser.add_argument("--mysql", action="store_true")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    asyncio.run(run(parser.parse_args()))

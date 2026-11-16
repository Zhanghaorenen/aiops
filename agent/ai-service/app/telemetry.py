"""Content-free spans. Only code-defined operation names and numerical usage leave the service."""

import base64
import os
import time
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from functools import wraps
from threading import Lock

_current = ContextVar("aiops_trace", default=None)
_provider = None
_lock = Lock()


def enabled(name: str) -> bool:
    return os.getenv(name, "").lower() in {"1", "true", "yes"}


@dataclass
class RequestTrace:
    trace_id: str
    started: float = field(default_factory=time.time)
    identity: dict = field(default_factory=dict)
    spans: list = field(default_factory=list)

    def resume(self, previous: dict):
        if (
            previous.get("traceId") != self.trace_id
            or previous["startedAt"] >= self.started
        ):
            return
        offset = round((self.started - previous["startedAt"]) * 1000)
        self.spans = [
            *previous["spans"],
            *[{**s, "startMs": s["startMs"] + offset} for s in self.spans],
        ][:512]
        self.started = previous["startedAt"]

    def snapshot(self) -> dict:
        return {
            "traceId": self.trace_id,
            "startedAt": self.started,
            "durationMs": round((time.time() - self.started) * 1000),
            **self.identity,
            "spans": sorted(self.spans, key=lambda s: s["startMs"]),
        }


def current():
    return _current.get()


def identify(**ids):
    if current():
        current().identity.update({k: str(v) for k, v in ids.items() if v is not None})


def provider():
    global _provider
    with _lock:
        if _provider is None:
            from opentelemetry.sdk.trace import TracerProvider
            from opentelemetry.sdk.resources import Resource
            from opentelemetry.sdk.trace.export import BatchSpanProcessor
            from opentelemetry.exporter.otlp.proto.http.trace_exporter import (
                OTLPSpanExporter,
            )

            p = TracerProvider(
                resource=Resource.create(
                    {"service.name": os.getenv("OTEL_SERVICE_NAME", "aiops-ai")}
                )
            )
            endpoint = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
            if endpoint and enabled("OTEL_ENABLED"):
                p.add_span_processor(
                    BatchSpanProcessor(
                        OTLPSpanExporter(endpoint.rstrip("/") + "/v1/traces")
                    )
                )
            if enabled("LANGFUSE_ENABLED"):
                host, public, secret = (
                    os.getenv(k)
                    for k in (
                        "LANGFUSE_HOST",
                        "LANGFUSE_PUBLIC_KEY",
                        "LANGFUSE_SECRET_KEY",
                    )
                )
                from urllib.parse import urlsplit

                destination = urlsplit(host or "")
                local = destination.hostname in {"localhost", "127.0.0.1", "::1"}
                if (
                    host
                    and public
                    and secret
                    and (
                        destination.scheme == "https"
                        or (local and destination.scheme == "http")
                    )
                ):
                    auth = base64.b64encode(f"{public}:{secret}".encode()).decode()
                    p.add_span_processor(
                        BatchSpanProcessor(
                            OTLPSpanExporter(
                                host.rstrip("/") + "/api/public/otel/v1/traces",
                                headers={"Authorization": "Basic " + auth},
                            )
                        )
                    )
            _provider = p
    return _provider


@contextmanager
def request_trace(headers=None):
    if not (enabled("OTEL_ENABLED") or enabled("LANGFUSE_ENABLED")):
        yield None
        return
    from opentelemetry.trace.propagation.tracecontext import (
        TraceContextTextMapPropagator,
    )

    parent = TraceContextTextMapPropagator().extract(headers or {})
    tracer = provider().get_tracer("aiops")
    with tracer.start_as_current_span(
        "aiops.request",
        context=parent,
        record_exception=False,
        set_status_on_exception=False,
    ) as root:
        ctx = RequestTrace(format(root.get_span_context().trace_id, "032x"))
        token = _current.set(ctx)
        try:
            yield ctx
        finally:
            for key, value in ctx.identity.items():
                root.set_attribute("aiops." + key, value)
                root.set_attribute("langfuse.trace.metadata." + key, value)
            _current.reset(token)


@contextmanager
def span(name: str, kind: str = "span"):
    ctx = current()
    attributes = {}
    if not ctx:
        yield attributes
        return
    from opentelemetry.trace import Status, StatusCode

    start = time.time()
    row = {
        "name": name,
        "type": kind,
        "startMs": round((start - ctx.started) * 1000),
        "status": "OK",
    }
    with (
        provider()
        .get_tracer("aiops")
        .start_as_current_span(
            name, record_exception=False, set_status_on_exception=False
        ) as active
    ):
        try:
            yield attributes
        except BaseException as error:
            row.update(status="ERROR", error=type(error).__name__)
            active.set_status(Status(StatusCode.ERROR, type(error).__name__))
            raise
        finally:
            if attributes.get("http.response.status_code", 0) >= 400:
                row.update(
                    status="ERROR",
                    error="HTTP_" + str(attributes["http.response.status_code"]),
                )
                active.set_status(Status(StatusCode.ERROR, row["error"]))
            row.update(attributes, durationMs=round((time.time() - start) * 1000))
            if len(ctx.spans) < 512:
                ctx.spans.append(row)
            active.set_attribute("langfuse.observation.type", kind)
            for k, v in {**ctx.identity, **attributes}.items():
                active.set_attribute(k, v)


def fixture_content(field, value):
    """Explicitly opted-in evaluation data only; never store content in the business timeline."""
    if (
        not current()
        or not current().identity.get("evaluationRunId")
        or not enabled("AIOPS_EVAL_TRACE_CONTENT")
    ):
        return
    import json
    from opentelemetry import trace
    from .agents.checkpoint import clean

    text = json.dumps(clean(value), ensure_ascii=False, default=str)
    trace.get_current_span().set_attribute("langfuse.observation." + field, text[:8000])


def traced(name, kind="span"):
    def decorate(fn):
        @wraps(fn)
        async def wrapped(*args, **kwargs):
            with span(name, kind):
                return await fn(*args, **kwargs)

        return wrapped

    return decorate


async def inject_platform(request):
    if current():
        from opentelemetry.trace.propagation.tracecontext import (
            TraceContextTextMapPropagator,
        )

        TraceContextTextMapPropagator().inject(request.headers)
        for key in ("incidentId", "taskId", "runId", "threadId"):
            if key in current().identity:
                request.headers["X-AIOps-" + key] = current().identity[key]


def graph_config(name: str, config=None):
    """A content-free LangChain callback for the root of each compiled graph."""
    config = dict(config or {})
    if not current():
        return config
    from langchain_core.callbacks import AsyncCallbackHandler
    from opentelemetry import trace

    class GraphCallback(AsyncCallbackHandler):
        async def on_chain_start(
            self, serialized, inputs, *, run_id, parent_run_id=None, **kwargs
        ):
            if parent_run_id is None:
                active = trace.get_current_span()
                active.set_attribute("aiops.graph", name)
                active.set_attribute("langfuse.trace.name", name)

    config["callbacks"] = [GraphCallback()]
    return config


class TraceMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        # Accept upstream context only; authorization is always established independently.
        headers = {
            k.decode(): v.decode("latin1")
            for k, v in scope["headers"]
            if k == b"traceparent"
        }
        with request_trace(headers) as ctx:

            async def respond(event):
                if ctx and event["type"] == "http.response.start":
                    event = {
                        **event,
                        "headers": [
                            *event["headers"],
                            (b"x-trace-id", ctx.trace_id.encode()),
                        ],
                    }
                await send(event)

            try:
                await self.app(scope, receive, respond)
            finally:
                if ctx:
                    from .trace_export import write_trace

                    await write_trace(scope, ctx)

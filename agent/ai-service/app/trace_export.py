"""Best effort, bounded write to the authenticated trace viewer after the HTTP response."""

import asyncio
import logging
import uuid

log = logging.getLogger(__name__)


async def write_trace(scope, ctx):
    from .config import Settings
    from .client.platform_client import PlatformClient

    if scope["path"] not in {
        "/api/chat",
        "/api/chat/stream",
        "/api/internal/investigate",
    } or not ctx.identity.get("userId"):
        return
    try:
        app = scope["app"]
        settings = app.state.settings or Settings.from_env()
        if len(settings.ai_service_key) < 32:
            return
        auth = (
            dict(scope.get("headers", [])).get(b"authorization", b"").decode("latin1")
        )
        if not auth.lower().startswith("bearer "):
            return
        timeline = ctx.snapshot()
        timeline.pop("userId", None)
        timeline.pop("runId", None)
        timeline["executionId"] = str(uuid.uuid4())
        timeline["spans"] = [
            {
                **{
                    k: s[k]
                    for k in (
                        "name",
                        "type",
                        "status",
                        "startMs",
                        "durationMs",
                        "error",
                    )
                    if k in s
                },
                "model": s.get("gen_ai.request.model"),
                "inputTokens": s.get("gen_ai.usage.input_tokens"),
                "outputTokens": s.get("gen_ai.usage.output_tokens"),
            }
            for s in timeline["spans"]
        ]
        async with asyncio.timeout(2):
            client = PlatformClient(settings.backend_url, auth[7:], 1)
            try:
                response = await client.client.post(
                    "api/ai/traces",
                    json=timeline,
                    headers={"X-AI-Service-Key": settings.ai_service_key},
                )
                response.raise_for_status()
            finally:
                await client.close()
    except Exception as exc:
        log.warning("Trace viewer export failed: %s", type(exc).__name__)

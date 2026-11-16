"""Native LangGraph adapter. The Java transaction fences both checkpoints and pending writes."""

import re
import httpx
from langgraph.checkpoint.base import (
    BaseCheckpointSaver,
    CheckpointTuple,
    WRITES_IDX_MAP,
)
from ..memory.schemas import SECRET
from ..telemetry import traced

_PRIVATE = re.compile(
    r"password|credential|secret|authorization|private.?key|api.?key|jwt|(?:access|refresh|lease).?token|^lease$|^token$|cmdline|commandline|environ",
    re.I,
)


def clean(value):
    if isinstance(value, dict):
        return {k: clean(v) for k, v in value.items() if not _PRIVATE.search(str(k))}
    if isinstance(value, (tuple, list)):
        return [clean(v) for v in value]
    if isinstance(value, str):
        return "[REDACTED]" if SECRET.search(value) else value
    if isinstance(value, BaseException):
        return type(value).__name__
    return value


class CheckpointUnavailable(RuntimeError):
    pass


class PlatformSaver(BaseCheckpointSaver):
    def __init__(self, platform, run, lease, service_key):
        super().__init__()
        self.client = platform.client
        self.thread = str(run)
        self.url = f"api/investigation-tasks/{int(run)}/checkpoint"
        self.headers = {"X-Investigation-Lease": lease, "X-AI-Service-Key": service_key}

    def config(self, checkpoint_id):
        return {
            "configurable": {
                "thread_id": self.thread,
                "checkpoint_ns": "",
                "checkpoint_id": checkpoint_id,
            }
        }

    def check(self, config):
        c = config["configurable"]
        if c["thread_id"] != self.thread or c.get("checkpoint_ns", ""):
            raise ValueError("CHECKPOINT_SCOPE")
        return c.get("checkpoint_id")

    async def request(self, method, **kwargs):
        try:
            response = await self.client.request(
                method, self.url, headers=self.headers, **kwargs
            )
            response.raise_for_status()
            return response
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code in (401, 403, 409):
                raise
            raise CheckpointUnavailable("CHECKPOINT_STORAGE_UNAVAILABLE") from exc
        except httpx.TransportError as exc:
            raise CheckpointUnavailable("CHECKPOINT_TRANSPORT_UNAVAILABLE") from exc

    @traced("checkpoint.read")
    async def aget_tuple(self, config):
        cid = self.check(config)
        r = await self.request("GET", params={"checkpoint": cid} if cid else {})
        data = r.json()
        if not data:
            return None
        checkpoint = data["checkpoint"]
        return CheckpointTuple(
            self.config(checkpoint["id"]),
            checkpoint,
            data["metadata"],
            self.config(data["parent"]) if data.get("parent") else None,
            [tuple(w) for w in data["writes"]],
        )

    @traced("checkpoint.save")
    async def aput(self, config, checkpoint, metadata, new_versions):
        parent = self.check(config)
        await self.request(
            "POST",
            json={
                "id": checkpoint["id"],
                "parent": parent,
                "checkpoint": clean(checkpoint),
                "metadata": clean(metadata),
            },
        )
        return self.config(checkpoint["id"])

    @traced("checkpoint.writes")
    async def aput_writes(self, config, writes, task_id, task_path=""):
        cid = self.check(config)
        await self.request(
            "POST",
            json={
                "id": cid,
                "writes": [
                    {
                        "task": task_id,
                        "path": task_path,
                        "idx": WRITES_IDX_MAP.get(channel, i),
                        "channel": channel,
                        "value": clean(value),
                    }
                    for i, (channel, value) in enumerate(writes)
                ],
            },
        )

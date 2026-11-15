"""Bounded model output. No inferred/assistant-authored preferences are accepted."""
import re
from typing import Literal
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field, field_validator

CATEGORIES = Literal['USER_PREFERENCE', 'WORKFLOW', 'ENVIRONMENT', 'CONFIRMED_EXPERIENCE']
SECRET = re.compile(r'(?is)bearer\s+\S+|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+|sk-[A-Za-z0-9_-]{12,}|-----BEGIN|(?:password|passwd|api[_ -]?key|secret|access[_ -]?token|token|jwt|密码|密钥|令牌)\s*(?:[:=：是为]|\bis\b)\s*\S+|Traceback|Exception in thread|同事|他人的|客户的|other user\x27s')
VOLATILE = re.compile(r'(?i)当前|此刻|刚刚|最近\s*\d+\s*分钟|采样|利用率|CPU使用率|\bpid\b|last heartbeat|(?:CPU|GPU|RAM|内存|显存|温度|IO).{0,12}(?:占用|使用|空闲|剩余|负载|可用|为|是).{0,8}\d+(?:\.\d+)?\s*(?:%|℃|GiB|GB|MiB|MB|字节)')


def safe(text):
    return isinstance(text, str) and not SECRET.search(text) and not any(ord(c) < 32 and c not in '\n\t' for c in text)


def redact(text):
    return SECRET.sub('[REDACTED]', text)


class MemoryCandidate(BaseModel):
    model_config = ConfigDict(extra='forbid', allow_inf_nan=False)
    category: CATEGORIES
    key: str = Field(min_length=1, max_length=100, pattern=r'^[a-zA-Z0-9_.:-]+$')
    content: str = Field(min_length=1, max_length=500)
    source_kind: Literal['USER_STATED', 'USER_CONFIRMED']
    source_message_id: int = Field(gt=0)
    evidence_quote: str = Field(min_length=1, max_length=400)
    confidence: float = Field(ge=0, le=1)
    subject: Literal['SELF'] = 'SELF'
    replaces_memory_id: UUID | None = None
    expected_version: int | None = Field(default=None, gt=0)


class MemoryExtraction(BaseModel):
    model_config = ConfigDict(extra='forbid')
    summary: str = Field(max_length=2000)
    memories: list[MemoryCandidate] = Field(default_factory=list, max_length=5)

    @field_validator('summary')
    @classmethod
    def safe_summary(cls, text):
        if not safe(text):
            raise ValueError('SENSITIVE_SUMMARY')
        return text

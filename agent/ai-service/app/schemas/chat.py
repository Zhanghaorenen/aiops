from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class HistoryMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=8000)

    @field_validator("content")
    @classmethod
    def nonblank(cls, value: str):
        if not value.strip():
            raise ValueError("历史消息不能为空")
        return value.strip()


class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    question: str = Field(min_length=1, max_length=4000)
    history: list[HistoryMessage] = Field(default_factory=list, max_length=20)

    @field_validator("question")
    @classmethod
    def nonblank(cls, value: str):
        if not value.strip():
            raise ValueError("问题不能为空")
        return value.strip()

    @model_validator(mode="after")
    def context_budget(self):
        if sum(len(message.content) for message in self.history) + len(self.question) > 50000:
            raise ValueError("会话上下文过长，请开启新会话")
        return self


class ChatResponse(BaseModel):
    answer: str

from typing import Literal

from pydantic import (BaseModel, ConfigDict, Field, 
                     field_validator
)


class HistoryMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")
    role: Literal["system", "user", "assistant"] = Field(
        description="消息角色，系统(system)、用户(user)、助手(assistant)"
    )
    content: str = Field(
        min_length=1,
        max_length=2000,
        description="消息内容"
    )
    @field_validator("content")
    @classmethod
    def content_not_blank(cls, value) -> str:
        if not value.strip():
            raise ValueError("消息内容不能为空")
        return value.strip()

class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    question:str=Field(
        min_length=1,
        max_length=2000,
        description="用户问题"
    )
    @field_validator("question")
    @classmethod
    def question_not_blank(cls, value) -> str:
        if not value.strip():
            raise ValueError("用户问题不能为空")
        return value.strip()

class ChatResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    answer: str = Field(
        description="模型回答"
    )
    @field_validator("answer")
    @classmethod
    def answer_not_blank(cls, value) -> str:
        if not value.strip():
            raise ValueError("模型回答不能为空")
        return value.strip()


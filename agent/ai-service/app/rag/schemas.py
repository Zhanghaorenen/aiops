from pydantic import BaseModel, Field

class SearchRequest(BaseModel):
    query: str = Field(min_length=1, max_length=2000)
    knowledge_base_ids: list[int] | None = Field(default=None, max_length=20)
    top_k: int = Field(default=5, ge=1, le=10)

class SearchResult(BaseModel):
    chunk_id: str
    document_id: int
    knowledge_base_id: int
    document_title: str
    page: int | None = None
    section: str | None = None
    content: str
    score: float

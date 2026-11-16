import hashlib
import json
from pathlib import Path
from pydantic import BaseModel, Field

ROOT = Path(__file__).resolve().parent


class Case(BaseModel):
    case_id: str
    category: str
    question: str
    scenario_id: str
    provenance: str
    expected_agents: list[str]
    expected_tools: list[str]
    required_evidence: list[str]
    expected_findings: list[str]
    forbidden_claims: list[str]
    expected_behavior: str
    fixture: dict
    reference_document: str | None = None
    relevant_chunks: list[str] = Field(default_factory=list)


def load(category=None):
    files = sorted((ROOT / "datasets").glob("*.jsonl"))
    cases = [
        Case.model_validate_json(line)
        for f in files
        for line in f.read_text(encoding="utf-8").splitlines()
        if line
    ]
    if len({c.case_id for c in cases}) != len(cases):
        raise ValueError("Duplicate case ID")
    return [c for c in cases if category is None or c.category == category], digest(
        files
    )


def digest(files):
    h = hashlib.sha256()
    for file in sorted(files):
        h.update(file.name.encode() + b"\0" + file.read_bytes())
    return h.hexdigest()[:20]

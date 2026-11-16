"""Rebuild companion vectors in bounded batches. Cursor advances after acknowledged writes."""

import argparse
import json
from pathlib import Path
from langchain_core.documents import Document
from ..config import Settings
from .resources import RagResources


def rebuild(store, cursor: Path, restart=False):
    state = {
        "collection": store.lexical.collection,
        "offset": None,
        "completed": False,
        "rows": 0,
    }
    if cursor.exists() and not restart:
        state = json.loads(cursor.read_text(encoding="utf-8"))
        if state["collection"] != store.lexical.collection:
            raise ValueError("CURSOR_COLLECTION_MISMATCH")
    while not state["completed"]:
        points, offset = store.client.scroll(
            store.collection,
            offset=state["offset"],
            limit=64,
            with_vectors=False,
            with_payload=True,
        )
        store.lexical.put(
            [p.id for p in points],
            [
                Document(
                    page_content=p.payload["page_content"],
                    metadata=p.payload["metadata"],
                )
                for p in points
            ],
        )
        # A delete can race a scroll page. Recheck the dense source after the acknowledged upsert.
        ids = [p.id for p in points]
        if ids:
            existing = {
                p.id
                for p in store.client.retrieve(
                    store.collection, ids, with_payload=False, with_vectors=False
                )
            }
            removed = [i for i in ids if i not in existing]
            if removed:
                store.client.delete(
                    store.lexical.collection, points_selector=removed, wait=True
                )
        state.update(
            offset=offset, completed=offset is None, rows=state["rows"] + len(points)
        )
        tmp = cursor.with_suffix(".tmp")
        cursor.parent.mkdir(parents=True, exist_ok=True)
        tmp.write_text(json.dumps(state), encoding="utf-8")
        tmp.replace(cursor)
    return state


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--cursor", type=Path, required=True)
    parser.add_argument("--restart", action="store_true")
    args = parser.parse_args()
    _, store = RagResources().get(Settings.from_env())
    try:
        print(rebuild(store, args.cursor, args.restart))
    finally:
        store.close()

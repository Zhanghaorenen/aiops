from pathlib import Path
from threading import Lock

class EmbeddingService:
    def __init__(self, path, version):
        self.path, self.version = path, version
        self._model = None
        self._lock = Lock()

    def model(self):
        with self._lock:
            if self._model is None:
                if not self.version or len(self.version)>100 or not self.path or not Path(self.path).is_dir():
                    raise ValueError('EMBEDDING_NOT_CONFIGURED')
                from sentence_transformers import SentenceTransformer
                self._model = SentenceTransformer(self.path, local_files_only=True, trust_remote_code=False)
            return self._model

    def embed_documents(self, texts):
        return self.model().encode(texts, normalize_embeddings=True, batch_size=16).tolist()

    def embed_query(self, text):
        return self.embed_documents([text])[0]

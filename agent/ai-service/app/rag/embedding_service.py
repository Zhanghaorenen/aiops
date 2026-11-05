"""Lazy, offline LangChain embedding adapter."""
from pathlib import Path
from threading import Lock
from langchain_core.embeddings import Embeddings
from langchain_huggingface import HuggingFaceEmbeddings


class EmbeddingService(Embeddings):
    def __init__(self, path, version, device='cpu', batch_size=16):
        self.path, self.version = path, version
        self.device, self.batch_size = device, batch_size
        self._model = None
        self._lock = Lock()

    def model(self):
        with self._lock:
            if self._model is None:
                if not self.version or len(self.version) > 100 or not self.path:
                    raise ValueError('EMBEDDING_NOT_CONFIGURED')
                directory = Path(self.path).resolve()
                if not directory.is_dir():
                    raise ValueError('EMBEDDING_DIRECTORY_NOT_FOUND')
                try:
                    self._model = HuggingFaceEmbeddings(
                        model_name=str(directory),
                        model_kwargs={'device': self.device, 'local_files_only': True, 'trust_remote_code': False},
                        encode_kwargs={'normalize_embeddings': True, 'batch_size': self.batch_size},
                    )
                except Exception as exc:
                    raise ValueError('EMBEDDING_LOAD_FAILED') from exc
            return self._model

    def embed_documents(self, texts):
        return self.model().embed_documents(texts)

    def embed_query(self, text):
        return self.model().embed_query(text)

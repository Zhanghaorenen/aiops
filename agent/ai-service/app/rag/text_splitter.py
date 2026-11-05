"""Character-based LangChain splitting; chunk_size is not a token count."""
from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter


def split_documents(docs: list[Document], chunk_size=1000, overlap=150) -> list[Document]:
    if type(chunk_size) is not int or type(overlap) is not int or not 100 <= chunk_size <= 1600 or not 0 <= overlap < chunk_size:
        raise ValueError('INVALID_CHUNK_CONFIGURATION')
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=chunk_size, chunk_overlap=overlap, add_start_index=True,
        separators=['\n\n', '\n', '。', '！', '？', '；', '，', '.', ' ', ''],
        keep_separator='end', strip_whitespace=False,
    )
    chunks = []
    for doc in docs:
        chunks.extend(splitter.split_documents([doc]))
        if len(chunks) > 10000:
            raise ValueError('TOO_MANY_CHUNKS')
    return chunks

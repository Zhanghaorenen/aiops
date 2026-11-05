from langchain_core.documents import Document

def split_documents(docs, chunk_size=1000, overlap=150):
    if not 100 <= chunk_size <= 1600 or not 0 <= overlap < chunk_size: raise ValueError('INVALID_CHUNK_CONFIGURATION')
    result = []
    for doc in docs:
        text = doc.page_content.strip()
        start = 0
        while start < len(text):
            end = min(start + chunk_size, len(text))
            if end < len(text):
                cut = max(text.rfind(sep, start+chunk_size//2, end) for sep in ('\n', '。', '；', '. ', ' '))
                if cut >= start+chunk_size//2: end = cut+1
            result.append(Document(page_content=text[start:end], metadata=dict(doc.metadata)))
            if end == len(text): break
            start = max(start+1, end-overlap)
    if len(result)>10000: raise ValueError('TOO_MANY_CHUNKS')
    return result

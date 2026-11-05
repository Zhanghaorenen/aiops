"""Bounded text-only parsing; documents are data and are never executed."""
from pathlib import Path
import zipfile
from langchain_core.documents import Document

MAX_TEXT = 2_000_000
MIMES = {'.pdf': 'application/pdf', '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', '.md': 'text/markdown', '.txt': 'text/plain'}

def parse_document(path: Path, mime_type: str) -> list[Document]:
    docs = []
    if mime_type == MIMES['.pdf']:
        if not path.read_bytes().startswith(b'%PDF-'): raise ValueError('INVALID_FORMAT')
        from pypdf import PdfReader
        reader = PdfReader(path)
        if reader.is_encrypted: raise ValueError('ENCRYPTED_PDF')
        if len(reader.pages) > 300: raise ValueError('TOO_MANY_PAGES')
        total = 0
        for i, page in enumerate(reader.pages):
            text = page.extract_text() or ''
            total += len(text)
            if total > MAX_TEXT: raise ValueError('TEXT_TOO_LARGE')
            docs.append(Document(page_content=text, metadata={'page': i+1}))
    elif mime_type == MIMES['.docx']:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if len(entries)>3000 or sum(x.file_size for x in entries)>30_000_000: raise ValueError('ARCHIVE_TOO_LARGE')
            if any('vbaProject' in x.filename for x in entries) or 'word/document.xml' not in archive.namelist(): raise ValueError('INVALID_FORMAT')
        from docx import Document as WordDocument
        word = WordDocument(path)
        section = None
        for para in word.paragraphs:
            if para.style and para.style.name.startswith('Heading'): section = para.text[:300]
            if para.text.strip(): docs.append(Document(page_content=para.text, metadata={'section': section}))
        for table in word.tables:
            docs.append(Document(page_content='\n'.join(' | '.join(c.text for c in row.cells) for row in table.rows), metadata={'section': '表格'}))
    else:
        text = path.read_text(encoding='utf-8-sig')
        if '\x00' in text or text.startswith('%PDF-') or text.startswith('PK\x03\x04'): raise ValueError('INVALID_FORMAT')
        section = None
        for block in text.split('\n\n'):
            if block.lstrip().startswith('#'): section = block.splitlines()[0].lstrip('# ').strip()[:300]
            docs.append(Document(page_content=block, metadata={'section': section}))
    if sum(len(d.page_content) for d in docs)>MAX_TEXT: raise ValueError('TEXT_TOO_LARGE')
    docs = [d for d in docs if d.page_content.strip()]
    if not docs: raise ValueError('OCR_UNSUPPORTED' if mime_type==MIMES['.pdf'] else 'EMPTY_DOCUMENT')
    return docs

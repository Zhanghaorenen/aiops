import io
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock
import httpx
from fastapi import HTTPException, UploadFile
from app.rag.document_parser import parse_document, MIMES
from app.rag.text_splitter import split_documents
from app.rag.ingestion_service import IngestionService
from app.rag.retrieval_service import RetrievalService
from app.rag.vector_store import VectorStore
from app.tools.rag_tool import RagTool

class ParserTests(unittest.TestCase):
    def test_markdown_chunks_preserve_heading_and_overlap(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'manual.md';path.write_text('# GPU 排查\n\n'+('显存不足 CUDA OOM。'*100),encoding='utf-8')
            docs=parse_document(path,MIMES['.md']);chunks=split_documents(docs,200,30)
            self.assertGreater(len(chunks),3)
            self.assertTrue(all(c.metadata['section']=='GPU 排查' for c in chunks))
            self.assertTrue(all(len(c.page_content)<=200 for c in chunks))
    def test_docx_paragraphs_tables_and_section(self):
        from docx import Document
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'manual.docx';doc=Document();doc.add_heading('GPU',level=1);doc.add_paragraph('CUDA OOM');table=doc.add_table(rows=1,cols=1);table.cell(0,0).text='检查显存';doc.save(path)
            parsed=parse_document(path,MIMES['.docx'])
            self.assertTrue(any(d.page_content=='CUDA OOM' and d.metadata['section']=='GPU' for d in parsed))
            self.assertTrue(any('检查显存' in d.page_content for d in parsed))
    def test_pdf_text_and_page_metadata(self):
        from pypdf import PdfWriter
        from pypdf.generic import DecodedStreamObject, NameObject, DictionaryObject
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'manual.pdf';writer=PdfWriter();page=writer.add_blank_page(width=300,height=300)
            font=DictionaryObject({NameObject('/Type'):NameObject('/Font'),NameObject('/Subtype'):NameObject('/Type1'),NameObject('/BaseFont'):NameObject('/Helvetica')})
            page[NameObject('/Resources')]=DictionaryObject({NameObject('/Font'):DictionaryObject({NameObject('/F1'):writer._add_object(font)})})
            stream=DecodedStreamObject();stream.set_data(b'BT /F1 12 Tf 10 100 Td (CUDA OOM) Tj ET');page[NameObject('/Contents')]=writer._add_object(stream)
            with path.open('wb') as output:writer.write(output)
            docs=parse_document(path,MIMES['.pdf']);self.assertIn('CUDA OOM',docs[0].page_content);self.assertEqual(docs[0].metadata['page'],1)
    def test_scan_and_disguised_text_rejected(self):
        from pypdf import PdfWriter
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'scan.pdf';w=PdfWriter();w.add_blank_page(width=100,height=100)
            with path.open('wb') as output:w.write(output)
            with self.assertRaisesRegex(ValueError,'OCR_UNSUPPORTED'):parse_document(path,MIMES['.pdf'])
            path.write_bytes(b'\x00MZ')
            with self.assertRaises(ValueError):parse_document(path,MIMES['.txt'])

class RagTests(unittest.IsolatedAsyncioTestCase):
    def platform(self):
        return SimpleNamespace(list_knowledge_bases=AsyncMock(return_value=[{'id':1,'status':'ACTIVE'}]),get_knowledge_base=AsyncMock(return_value={'id':1,'status':'ACTIVE'}),list_knowledge_documents=AsyncMock(return_value=[{'id':2,'status':'READY','indexVersion':1,'embeddingVersion':'test-v1'}]),get_knowledge_document=AsyncMock(return_value={'id':2,'status':'READY','indexVersion':1,'embeddingVersion':'test-v1','originalFilename':'manual.md'}))
    def hit(self):return {'chunk_id':'doc2-v1-00000','document_id':2,'knowledge_base_id':1,'index_version':1,'document_title':'manual.md','content':'CUDA OOM 检查显存','score':0.8}
    async def test_cross_user_search_never_calls_embedding_or_vectors(self):
        platform=self.platform();store=SimpleNamespace(search=AsyncMock());embedding=SimpleNamespace(embed_query=lambda q:self.fail('must not embed'))
        with self.assertRaises(HTTPException) as ctx:await RetrievalService(platform,embedding,store).retrieve('private',[99])
        self.assertEqual(ctx.exception.status_code,403);store.search.assert_not_called()
    async def test_only_ready_current_version_is_returned(self):
        platform=self.platform();store=SimpleNamespace(search=AsyncMock(return_value=[self.hit()]));embedding=SimpleNamespace(version='test-v1',embed_query=lambda q:[1,0])
        retrieval=RetrievalService(platform,embedding,store)
        result=await retrieval.retrieve('CUDA OOM',[1]);self.assertEqual(result['results'][0]['document_title'],'manual.md')
        platform.get_knowledge_document.return_value['status']='DELETING'
        self.assertEqual((await retrieval.retrieve('CUDA OOM'))['results'],[])
        platform.get_knowledge_document.return_value.update(status='READY',indexVersion=2)
        self.assertEqual((await retrieval.retrieve('CUDA OOM'))['results'],[])
    async def test_upload_ready_and_failed_index_compensation(self):
        with tempfile.TemporaryDirectory() as temp:
            platform=self.platform();platform.register_knowledge_document=AsyncMock(return_value={'id':2,'indexVersion':1});platform.set_knowledge_status=AsyncMock()
            store=SimpleNamespace(upsert_chunks=AsyncMock(),delete_document_chunks=AsyncMock())
            embedding=SimpleNamespace(version='test-v1',embed_documents=lambda texts:[[1,0] for t in texts])
            settings=SimpleNamespace(rag_storage_dir=temp,rag_service_key='x'*32,rag_chunk_size=1000,rag_chunk_overlap=150)
            service=IngestionService(platform,embedding,store,settings)
            await service.ingest_document(1,UploadFile(filename='../../manual.md',file=io.BytesIO(b'# GPU\n\nCUDA OOM')))
            self.assertEqual(platform.set_knowledge_status.await_args.args[1]['status'],'READY')
            self.assertEqual(platform.register_knowledge_document.await_args.args[1]['originalFilename'],'manual.md')
            store.upsert_chunks.side_effect=RuntimeError('unavailable')
            with self.assertRaises(RuntimeError):await service.ingest_document(1,UploadFile(filename='manual.txt',file=io.BytesIO(b'GPU test')))
            store.delete_document_chunks.assert_awaited();self.assertEqual(platform.set_knowledge_status.await_args.args[1]['status'],'FAILED')
    async def test_delete_failure_preserves_original_and_pending_cleanup(self):
        with tempfile.TemporaryDirectory() as temp:
            platform=self.platform();key='a'*32;path=Path(temp)/key;path.write_text('private')
            platform.get_knowledge_document.return_value.update(storageKey=key,embeddingVersion='test-v1');platform.mark_knowledge_document_deleting=AsyncMock();platform.purge_knowledge_document=AsyncMock()
            store=SimpleNamespace(delete_document_chunks=AsyncMock(side_effect=RuntimeError('offline')))
            service=IngestionService(platform,SimpleNamespace(version='test-v1'),store,SimpleNamespace(rag_storage_dir=temp,rag_service_key='x'*32))
            with self.assertRaises(RuntimeError):await service.delete_document(1,2)
            self.assertTrue(path.exists());platform.purge_knowledge_document.assert_not_awaited()
            store.delete_document_chunks.side_effect=None;await service.delete_document(1,2)
            self.assertFalse(path.exists());platform.purge_knowledge_document.assert_awaited()
    async def test_qdrant_query_contains_mandatory_scope(self):
        from unittest.mock import Mock
        store=VectorStore('http://localhost:6333','','test-v1')
        vectorstore=SimpleNamespace(asimilarity_search_with_score=AsyncMock(return_value=[]))
        store._store=Mock(return_value=vectorstore)
        try:
            await store.search('CUDA OOM',[1,4],5,[(1,2,2)])
            conditions=vectorstore.asimilarity_search_with_score.await_args.kwargs['filter'].model_dump()
            self.assertIn('metadata.knowledge_base_id',str(conditions))
            self.assertIn('metadata.index_version',str(conditions))
            self.assertIn("'value': 2",str(conditions))
        finally:store.close()
    async def test_forbidden_upload_and_delete_do_not_touch_local_files(self):
        with tempfile.TemporaryDirectory() as temp:
            platform=self.platform()
            response=httpx.Response(404,request=httpx.Request('GET','http://platform/api/knowledge-bases/99'))
            platform.get_knowledge_base.side_effect=httpx.HTTPStatusError('forbidden',request=response.request,response=response)
            platform.get_knowledge_document.side_effect=httpx.HTTPStatusError('forbidden',request=response.request,response=response)
            store=SimpleNamespace(delete_document_chunks=AsyncMock())
            service=IngestionService(platform,None,store,SimpleNamespace(rag_storage_dir=temp))
            with self.assertRaises(httpx.HTTPStatusError):await service.ingest_document(99,UploadFile(filename='a.txt',file=io.BytesIO(b'private')))
            with self.assertRaises(httpx.HTTPStatusError):await service.delete_document(99,2)
            self.assertEqual(list(Path(temp).iterdir()),[]);store.delete_document_chunks.assert_not_called()
    async def test_graph_emits_structured_sources_from_read_tool(self):
        from app.agents.graph_agent import GraphAgent
        from types import SimpleNamespace
        evidence={**self.hit(),'page':3,'section':'显存'}
        retrieval=SimpleNamespace(retrieve=AsyncMock(return_value={'results':[evidence]}))
        class Model:
            count=0
            async def stream_chat(self,messages,tools):
                self.count+=1
                if self.count==1:
                    yield {'type':'tool_calls','calls':[{'id':'rag1','type':'function','function':{'name':'search_knowledge','arguments':'{"query":"CUDA OOM"}'}}]}
                else: yield {'type':'token','text':'见 GPU 手册'}
        empty=SimpleNamespace(get_tools=lambda:[])
        agent=GraphAgent(Model(),empty,empty,rag_tools=RagTool(retrieval))
        events=[event async for event in agent.astream('CUDA OOM 怎么处理')]
        self.assertTrue(any(e['type']=='sources' and e['results'][0]['page']==3 for e in events))

    async def test_tool_only_exposes_read_search(self):
        retrieval=SimpleNamespace(retrieve=AsyncMock(return_value={'results':[]}));tools=RagTool(retrieval).get_tools()
        self.assertEqual([t.name for t in tools],['search_knowledge']);await tools[0].ainvoke({'query':'CUDA OOM'})
        retrieval.retrieve.assert_awaited()

if __name__=='__main__':unittest.main()


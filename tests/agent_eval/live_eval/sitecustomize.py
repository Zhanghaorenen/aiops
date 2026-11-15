"""Opt-in observational tracing, only for the isolated evaluation process."""
import os
if os.getenv('AIOPS_EVAL_TRACE'):
    import contextvars, json, time, threading
    from pathlib import Path
    from app.agents.graph_agent import GraphAgent
    from app.llm.client import LLMClient
    from app.main import app
    case = contextvars.ContextVar('eval_case', default=None)
    lock = threading.Lock()
    def clean(value):
        if isinstance(value,dict):
            return {k:('[REDACTED]' if any(s in k.lower() for s in ['password','token','credential','command','cmdline','dataset','username','path','configuration']) else clean(v)) for k,v in value.items()}
        if isinstance(value,list): return [clean(v) for v in value]
        return value
    def emit(data):
        if not case.get(): return
        with lock, Path(os.environ['AIOPS_EVAL_TRACE']).open('a',encoding='utf-8') as f:
            f.write(json.dumps(clean({'case_id':case.get(),**data}),ensure_ascii=False,default=str)+'\n')
    original_wrap = GraphAgent._wrap_tool
    def wrap(source):
        original = source.coroutine
        async def observed(**kwargs):
            t=time.perf_counter()
            emit({'event':'tool_start','name':source.name,'arguments':kwargs})
            try:
                value=await original(**kwargs)
                emit({'event':'tool_result','name':source.name,'arguments':kwargs,'result':value,'duration_ms':(time.perf_counter()-t)*1000,'ok':True})
                return value
            except Exception as e:
                emit({'event':'tool_result','name':source.name,'arguments':kwargs,'ok':False,'error_type':type(e).__name__,'duration_ms':(time.perf_counter()-t)*1000})
                raise
        return original_wrap(source.model_copy(update={'coroutine':observed}))
    GraphAgent._wrap_tool=staticmethod(wrap)
    original_stream=LLMClient.stream_chat
    async def stream(self,*a,**kw):
        emit({'event':'model_call','model':self.model})
        async for x in original_stream(self,*a,**kw):yield x
    LLMClient.stream_chat=stream
    @app.middleware('http')
    async def context(request,call_next):
        reset=case.set(request.headers.get('x-eval-case'))
        try:return await call_next(request)
        finally:case.reset(reset)

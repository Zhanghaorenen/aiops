from langgraph.config import get_stream_writer

def writer():
    try:
        return get_stream_writer()
    except RuntimeError:
        return lambda event: None

def emit(kind,**fields):
    writer()({"type":kind,**fields})

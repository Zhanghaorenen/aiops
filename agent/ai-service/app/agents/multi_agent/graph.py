from langgraph.graph import StateGraph,START,END
from .state import SupervisorState

def build(supervisor):
    graph=StateGraph(SupervisorState)
    for name in ("plan","clarification","execute_agents","merge_results","synthesize","validate","finalize"):
        from ...telemetry import traced
        graph.add_node(name,traced('supervisor.'+name)(getattr(supervisor,name)))
    graph.add_edge(START,"plan")
    graph.add_conditional_edges("plan",lambda s:"clarification" if s["plan"].clarification else "execute_agents")
    graph.add_edge("clarification",END)
    graph.add_edge("execute_agents","merge_results")
    graph.add_edge("merge_results","synthesize")
    graph.add_edge("synthesize","validate")
    graph.add_edge("validate","finalize")
    graph.add_edge("finalize",END)
    return graph.compile()

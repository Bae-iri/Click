"""One response projection for runtime, preparation, controls, results and recovery."""
from ai_agent.runtime.execution_contract import state
from app.schemas.execution import ExecutionState

def attach(response, request):
    guide=getattr(response,'guide_command',None)
    render=(guide.render_policy or {}) if guide else {}
    verification=((response.metadata or {}).get('verification') or {}).get('result') or {}
    tree=request.accessibility_tree
    graph=getattr(response,"graph_reference",None)
    graph_hash=graph.sha256 if graph else (response.metadata.get("execution_graph_reference") or {}).get("sha256")
    response.execution_state=ExecutionState(**state(session_id=response.session_id,
        episode_id=getattr(response,'episode_id',None),version=response.session_version,
        status=response.task_status,request_id=response.request_id,
        command=getattr(request,'command_type','turn'),source='turn',
        observation_id=tree.observation_id if tree else None,
        attempt_id=render.get('attempt_id'),guide_id=guide.guide_command_id if guide else None,
        verification=verification,graph_hash=graph_hash,goal_type=getattr(response,"accepted_goal_type",None) or response.metadata.get("execution_goal_type"),guide=guide.model_dump() if guide else None))
    return response

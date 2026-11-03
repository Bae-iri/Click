"""Resolve native/OCR observation context without changing request precedence."""

from ai_agent.runtime.types import AgentTurnRequest, Bounds, DeviceContext


def screen_bounds(request: AgentTurnRequest) -> Bounds | None:
    if request.screen_bounds:
        return request.screen_bounds
    if request.raw_goal and request.raw_goal.device_context.screen_bounds:
        return request.raw_goal.device_context.screen_bounds
    if request.device_context and request.device_context.screen_bounds:
        return request.device_context.screen_bounds
    snapshot = request.vision_snapshot
    if snapshot and snapshot.screen_width and snapshot.screen_height:
        return (0, 0, snapshot.screen_width, snapshot.screen_height)
    return None


def vision_package_name(request: AgentTurnRequest) -> str | None:
    if request.vision_snapshot and request.vision_snapshot.package_name:
        return request.vision_snapshot.package_name
    if request.raw_goal:
        return request.raw_goal.device_context.app_package
    if request.device_context:
        return request.device_context.app_package
    first_node = next((node for node in request.accessibility_tree if node.package_name), None)
    return first_node.package_name if first_node else None


def device_context(request: AgentTurnRequest) -> DeviceContext | None:
    return request.device_context or (
        request.raw_goal.device_context if request.raw_goal else None
    )

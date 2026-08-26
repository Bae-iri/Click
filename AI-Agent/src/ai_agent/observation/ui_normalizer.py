"""Convert raw accessibility nodes to NormalizedNode."""

from __future__ import annotations

from typing import Any

from ai_agent.schemas import NormalizedNode


class UINormalizer:
    def normalize(self, raw_nodes: list[dict[str, Any]]) -> list[NormalizedNode]:
        normalized: list[NormalizedNode] = []
        for index, node in enumerate(raw_nodes):
            text = node.get("text") or ""
            content_description = node.get("contentDescription") or ""
            label = text or content_description
            bounds = tuple(node.get("boundsInScreen") or (0, 0, 0, 0))
            normalized.append(
                NormalizedNode(
                    node_id=node.get("nodeId") or f"node_{index}",
                    label=label,
                    role=node.get("role") or node.get("className") or "unknown",
                    bounds=bounds,
                    clickable=bool(node.get("clickable")),
                    enabled=bool(node.get("enabled", True)),
                    resource_id=node.get("resourceId"),
                    class_name=node.get("className"),
                    scrollable=bool(node.get("scrollable")),
                )
            )
        return normalized

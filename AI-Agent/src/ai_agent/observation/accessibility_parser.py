"""Android Accessibility Tree parsing boundary."""

from __future__ import annotations

from typing import Any


class AccessibilityTreeParser:
    def parse(self, raw_tree: dict[str, Any]) -> list[dict[str, Any]]:
        """Flatten or normalize raw accessibility service payloads."""
        nodes = raw_tree.get("nodes", [])
        return list(nodes)

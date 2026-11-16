"""Backend-owned graph artifacts. Runtime imports contracts, never these definitions."""
import json
from pathlib import Path

from .validation import load_graph

ROOT = Path(__file__).parent


def releases():
    for release in json.loads((ROOT / "releases.json").read_text(encoding="utf-8")):
        name = release["artifact"]
        if Path(name).name != name or not name.endswith(".json"):
            raise ValueError("Invalid graph artifact path")
        body = json.loads((ROOT / "artifacts" / name).read_text(encoding="utf-8"))
        graph = load_graph(body, executable=release["approved"])
        yield release, graph
    from .settings_navigation import navigation_releases
    for release, graph in navigation_releases():
        load_graph(json.loads(json.dumps(graph.to_dict())))
        yield release, graph
    from .settings_actions import action_releases
    for release, graph in action_releases():
        load_graph(json.loads(json.dumps(graph.to_dict())))
        yield release, graph
    from .drafts import draft_releases
    for release, graph in draft_releases():
        # Drafts use future task-specific predicates and cannot be executed yet.
        load_graph(json.loads(json.dumps(graph.to_dict())), executable=False)
        yield release, graph
    from .commerce import commerce_releases
    catalog = json.loads((ROOT / 'goal_catalog.json').read_text(encoding='utf-8'))
    for release, graph in commerce_releases(catalog):
        load_graph(json.loads(json.dumps(graph.to_dict())))
        yield release, graph


def approved_graphs():
    result = {}
    for release, graph in releases():
        if release["approved"]:
            result[graph.definition.task_id] = graph
            result[graph.definition.goal_type] = graph
    return result

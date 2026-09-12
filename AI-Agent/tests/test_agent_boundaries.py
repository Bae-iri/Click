"""Keep offline graph work out of the user-facing execution dependency graph."""

import ast
import importlib.util
from pathlib import Path
import subprocess
import sys

import pytest


PROJECT = Path(__file__).resolve().parents[1]
PACKAGE = PROJECT / "src" / "ai_agent"
OFFLINE = "ai_agent.graph_proposal"
SHARED = ("ai_agent.telemetry", "ai_agent.task_graph")


def _belongs_to(module, prefix):
    return module == prefix or module.startswith(prefix + ".")


def _imports(path):
    """Resolve absolute and relative imports, including `from ai_agent import x`."""
    if path.is_relative_to(PACKAGE):
        parts = path.relative_to(PACKAGE).with_suffix("").parts
        package_parts = parts[:-1]
        package = ".".join(("ai_agent", *package_parts))
    else:
        package = "app"

    for node in ast.walk(ast.parse(path.read_text())):
        if isinstance(node, ast.Import):
            yield from (alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            module = node.module or ""
            if node.level:
                module = importlib.util.resolve_name("." * node.level + module, package)
            yield module
            yield from (f"{module}.{alias.name}" for alias in node.names)


def test_online_and_shared_modules_do_not_import_agent_two():
    sources = list(PACKAGE.rglob("*.py"))
    sources += list((PROJECT.parent / "Backend" / "app").rglob("*.py"))
    violations = []
    for path in sources:
        if path.is_relative_to(PACKAGE / "graph_proposal"):
            continue
        for module in _imports(path):
            if _belongs_to(module, OFFLINE):
                violations.append(f"{path}: {module}")
    assert not violations, "User runtime must not load Agent(2): " + str(violations)


def test_agent_two_depends_only_on_its_own_modules_and_shared_contracts():
    violations = []
    for path in (PACKAGE / "graph_proposal").rglob("*.py"):
        for module in _imports(path):
            if _belongs_to(module, "ai_agent") and not any(
                _belongs_to(module, prefix) for prefix in (OFFLINE, *SHARED)
            ):
                violations.append(f"{path}: {module}")
            if _belongs_to(module, "app"):
                violations.append(f"{path}: {module}")
    assert not violations, "Agent(2) must not call user execution or Backend: " + str(violations)


def test_shared_contracts_do_not_depend_on_either_agent_or_backend():
    sources = [PACKAGE / "schemas.py"]
    for directory in ("telemetry", "task_graph"):
        sources.extend((PACKAGE / directory).rglob("*.py"))
    violations = []
    for path in sources:
        for module in _imports(path):
            if _belongs_to(module, "ai_agent") and not any(
                _belongs_to(module, prefix) for prefix in (*SHARED, "ai_agent.schemas")
            ):
                violations.append(f"{path}: {module}")
            if _belongs_to(module, "app"):
                violations.append(f"{path}: {module}")
    assert not violations, "Shared contracts must not load either agent: " + str(violations)


@pytest.mark.parametrize("entry_point", ["runtime", "graph_proposal"])
def test_each_agent_imports_independently_in_a_fresh_process(entry_point):
    script = """
import importlib
import pkgutil
import sys

sys.path.insert(0, sys.argv[2])
package = importlib.import_module('ai_agent.' + sys.argv[1])
for module in pkgutil.walk_packages(package.__path__, package.__name__ + '.'):
    importlib.import_module(module.name)
forbidden = 'ai_agent.graph_proposal' if sys.argv[1] == 'runtime' else 'ai_agent.runtime'
assert not any(name == forbidden or name.startswith(forbidden + '.') for name in sys.modules)
"""
    result = subprocess.run(
        [sys.executable, "-I", "-c", script, entry_point, str(PROJECT / "src")],
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode == 0, result.stdout + result.stderr

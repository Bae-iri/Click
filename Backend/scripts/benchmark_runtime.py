"""Measure isolated runtime overhead on a synthetic screen, without network calls."""
import argparse
import json
import multiprocessing
import os
from pathlib import Path
import platform
from statistics import median
import sys
from time import perf_counter

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / "AI-Agent" / "src"))

from app.clients import runtime_process
from app.clients.checkpoint import TASK_GRAPHS
from app.clients.runtime_work import RuntimeWork
from app.schemas.agent_turn import AgentTurnRequest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=int, default=10, choices=range(1, 51))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    # Neither a real inference endpoint nor any personal screen data is used.
    os.environ["AI1_UI_SELECTOR_PROVIDER"] = "rules"
    for key in ("SELF_HOSTED_LLM_URL", "SELF_HOSTED_LLM_TOKEN", "SELF_HOSTED_LLM_MODEL"):
        os.environ.pop(key, None)
    request = AgentTurnRequest.model_validate(json.loads(
        (BACKEND / "examples/agent-turn.json").read_text(encoding="utf-8")))
    work = RuntimeWork(request, TASK_GRAPHS["MVP-01"], None)
    report = {"scope": "synthetic runtime IPC, imports, selection and cleanup",
              "excludes": ["HTTP", "database", "external AI", "device capture", "overlay rendering"],
              "environment": {"python": platform.python_version(), "platform": platform.system()},
              "methods": {}}
    original = runtime_process._START_METHOD
    try:
        methods = ["spawn"]
        if "forkserver" in multiprocessing.get_all_start_methods():
            methods.append("forkserver")
        for method in methods:
            runtime_process._START_METHOD = method
            started = perf_counter()
            runtime_process.warm_runtime()
            startup_ms = (perf_counter() - started) * 1000
            samples = []
            for _ in range(args.samples):
                started = perf_counter()
                result = runtime_process.run_runtime(work, 10)
                samples.append(round((perf_counter() - started) * 1000, 3))
                guide = result["response"]["guide_command"]
                if guide["target_node_id"] != "display":
                    raise RuntimeError("Synthetic highlight target changed")
            report["methods"][method] = {"startup_ms": round(startup_ms, 3),
                "samples_ms": samples, "median_ms": round(median(samples), 3),
                "highlight_target": "display"}
    finally:
        runtime_process._START_METHOD = original
    rendered = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

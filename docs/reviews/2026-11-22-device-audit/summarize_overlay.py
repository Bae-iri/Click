"""Aggregate the physical overlay audit. Every scheduled trial is in the denominator."""
import glob
import json
import statistics
import sys
from pathlib import Path

HERE = Path(__file__).parent


def load(pattern):
    rows = {}
    for path in sorted(glob.glob(str(HERE / pattern))):
        try:
            cases = json.loads(Path(path).read_text(encoding="utf-8"))["cases"]
        except (OSError, ValueError, KeyError):
            continue
        for case in cases:
            if "trial" in case:
                rows[case["trial"]] = case  # a resumed part replaces only its own trials
    return [rows[k] for k in sorted(rows)]


def pct(values, q):
    values = sorted(values)
    if not values:
        return None
    index = max(0, min(len(values) - 1, round(q * (len(values) - 1))))
    return values[index]


def summarize(rows, scheduled):
    observed = len(rows)
    passed = [r for r in rows if r.get("passed")]
    first = [r["first_visible_guidance_ms"] for r in rows if "first_visible_guidance_ms" in r]
    done = [r["result_prompt_ms"] for r in passed if "result_prompt_ms" in r]
    with_first = [r for r in rows if "first_action_correct" in r]
    failures = {}
    for r in rows:
        if not r.get("passed"):
            code = r.get("failure_code") or r.get("failure") or "UNKNOWN"
            failures[code] = failures.get(code, 0) + 1
    by_cohort = {}
    for r in rows:
        c = by_cohort.setdefault(r["cohort"], [0, 0])
        c[0] += bool(r.get("passed"))
        c[1] += 1
    by_task = {}
    for r in rows:
        t = by_task.setdefault(r["task"], [0, 0])
        t[0] += bool(r.get("passed"))
        t[1] += 1
    return {
        "scheduled_trials": scheduled,
        "observed_trials": observed,
        "missing_trials_counted_as_failure": scheduled - observed,
        "tasks": len(by_task),
        "passed": len(passed),
        "success_rate": len(passed) / scheduled,
        "false_completion": sum(bool(r.get("false_completion")) for r in rows),
        "first_action_correct": sum(bool(r.get("first_action_correct")) for r in with_first),
        "first_action_observed": len(with_first),
        "first_visible_guidance_ms": {"n": len(first), "median": statistics.median(first) if first else None,
                                      "p90": pct(first, 0.9)},
        "completion_ms_successful": {"n": len(done), "median": statistics.median(done) if done else None,
                                     "p90": pct(done, 0.9)},
        "failures": failures,
        "by_cohort": {k: {"passed": v[0], "trials": v[1]} for k, v in by_cohort.items()},
        "by_task": {k: {"passed": v[0], "trials": v[1]} for k, v in sorted(by_task.items())},
    }


if __name__ == "__main__":
    pattern = sys.argv[1] if len(sys.argv) > 1 else "fixed-matrix-part*.json"
    scheduled = int(sys.argv[2]) if len(sys.argv) > 2 else 104
    print(json.dumps(summarize(load(pattern), scheduled), ensure_ascii=False, indent=2))

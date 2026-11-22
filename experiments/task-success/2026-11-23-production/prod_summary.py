"""Production metrics: settings (judgment-ready harness) + apps (arrival vs product completion)."""
import json, math, statistics, sys
from pathlib import Path

OUT = Path(sys.argv[1])


def stats(values):
    v = sorted(x for x in values if x is not None)
    if not v:
        return None
    return {"n": len(v), "median_s": round(statistics.median(v) / 1000, 1),
            "p90_s": round(v[max(0, math.ceil(len(v) * .9) - 1)] / 1000, 1)}


settings = []
for f in sorted((OUT / "settings").glob("*/result.json")):
    a = json.loads(f.read_text(encoding="utf8"))["android"]
    settings.append(a)
entered = [a for a in settings if a.get("entry") == "CLICK_home_text_submission"]
apps = []
af = OUT / "apps-result.json"
if af.exists() and af.stat().st_size:
    apps = json.loads(af.read_text(encoding="utf8")).get("cases", [])

s = {
    "settings_recorded": len(settings), "settings_entered": len(entered),
    "settings_ready": sum(bool(a.get("judgment_ready_candidate")) for a in entered),
    "settings_arrived": sum("goal_reached_ms" in a for a in entered),
    "settings_first_guide_shown": sum(a.get("first_visible_guidance_ms") is not None for a in entered),
    "settings_first_guide_correct": sum(a.get("first_action_correct") is True for a in entered),
    "settings_first_guide_wrong": sum(a.get("first_action_correct") is False for a in entered),
    "first_guide_time": stats(a.get("first_visible_guidance_ms") for a in entered),
    "ready_time": stats(a.get("time_to_judgment_ready_ms") for a in entered if a.get("judgment_ready_candidate")),
    "settings_failures": {},
    "apps_total": len(apps),
    "apps_arrived": sum(c.get("goal_reached_ms") is not None for c in apps),
    "apps_completed": sum(bool(c.get("passed")) for c in apps),
    "apps_false_completion": sum(bool(c.get("false_completion")) for c in apps),
    "apps_completion_time": stats(c.get("verified_completion_ms") for c in apps if c.get("passed")),
}
for a in entered:
    code = a.get("failure_code") or a.get("failure")
    if code and not a.get("judgment_ready_candidate"):
        s["settings_failures"][code] = s["settings_failures"].get(code, 0) + 1
s["apps_rows"] = [(c["task"], bool(c.get("passed")), c.get("goal_reached_ms") is not None,
                   c.get("failure_code") or c.get("failure")) for c in apps]
s["settings_rows"] = [(a["task"], bool(a.get("judgment_ready_candidate")), a.get("failure_code") or a.get("failure"))
                      for a in settings]
print(json.dumps(s, ensure_ascii=False, indent=1))
(OUT / "production-summary.json").write_text(json.dumps(s, ensure_ascii=False, indent=1) + "\n", encoding="utf8")

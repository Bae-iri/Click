"""Complete, controlled Jev answers for tests of either explicit screen policy."""


def with_screen_judgments(payload, result):
    if "page" not in payload["questions"]:
        return result
    criteria = payload["questions"]["screen"]["criteria"]
    selected = criteria.get(result["answers"]["screen"]["choice"])
    candidate = selected if isinstance(selected, dict) else next(v for v in criteria.values() if isinstance(v, dict))
    pages = payload["questions"]["page"]["criteria"]
    choice = next(k for k, v in pages.items() if isinstance(v, dict) and v["state_id"] == candidate["step"])
    return {**result, "answers": {**result["answers"],
        "page": {"type": "choice", "choice": choice, "confidence": 1,
                 "probabilities": {key: float(key == choice) for key in pages}},
        "target_supported": {"type": "noul", "noul": float(isinstance(selected, dict))}}}

"""Deterministic temperature transforms, separate from the provider's acceptance evidence."""
import math
import os


def choice_temperature():
    value = float(os.getenv("JEV_CHOICE_TEMPERATURE", "0.7"))
    if not math.isfinite(value) or not 0 < value <= 10:
        raise ValueError("JEV_CHOICE_TEMPERATURE must be finite and in (0, 10]")
    return value


def temperature_softmax(probabilities, temperature):
    """softmax(log(p) / T), since Jev returns probabilities rather than logits.

    This preserves ranking and exact ties. It cannot make a weak or incorrect
    model decision trustworthy, and must never replace raw acceptance scores.
    Zero-probability options remain zero; subtracting max(log(p)) before scaling
    keeps even very small temperatures numerically stable.
    """
    if (type(temperature) not in (int, float) or not math.isfinite(temperature)
            or temperature <= 0):
        raise ValueError("Temperature must be positive and finite")
    if (not isinstance(probabilities, dict) or len(probabilities) < 2
            or any(not isinstance(key, str) or not key for key in probabilities)
            or any(type(p) not in (int, float) or not math.isfinite(p) or not 0 <= p <= 1
                   for p in probabilities.values())
            or not math.isclose(math.fsum(probabilities.values()), 1, abs_tol=1e-4)):
        raise ValueError("Invalid probability distribution")
    logs = {key: math.log(p) for key, p in probabilities.items() if p > 0}
    peak = max(logs.values())
    weights = {key: math.exp((logs[key] - peak) / temperature) if key in logs else 0.0
               for key in probabilities}
    total = math.fsum(weights.values())
    return {key: weight / total for key, weight in weights.items()}


def temperature_diagnostics(probabilities, choice, temperature):
    adjusted = temperature_softmax(probabilities, temperature)
    top = adjusted[choice]
    return {"choice_temperature": temperature, "tempered_top_probability": top,
            "tempered_probability_margin": top - max(p for key, p in adjusted.items() if key != choice),
            "tempered_confidence": (top - 1 / len(adjusted)) / (1 - 1 / len(adjusted))}

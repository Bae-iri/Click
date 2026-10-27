"""Select one explicitly configured classifier; providers never fall back to each other."""
import os

from app.services.goal_jev import JevGoalClassifier
from app.services.goal_llm import LocalGoalLlm


def configured_goal_classifier():
    provider = os.getenv("GOAL_CLASSIFIER_PROVIDER", "self_hosted").strip()
    if provider == "self_hosted":
        return LocalGoalLlm()
    if provider == "jev":
        return JevGoalClassifier()
    raise ValueError("GOAL_CLASSIFIER_PROVIDER must be self_hosted or jev")

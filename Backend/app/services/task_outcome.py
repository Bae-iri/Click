"""Task-specific result confirmation. This is independent of safety consent and ratings."""
from copy import deepcopy
from uuid import uuid4


def make_outcome(technical, now, direction="increase", *, previous=None):
    technical = deepcopy(technical) or {"status": "UNVERIFIABLE", "reason": "insufficient_evidence"}
    if technical["status"] == "CHANGED":
        message = "글자 크기를 키웠어요." if direction == "increase" else "글자 크기를 줄였어요."
    elif technical.get("reason") == "limit_reached":
        message = "이미 가장 큰 글자 크기예요." if direction == "increase" else "이미 가장 작은 글자 크기예요."
    elif technical["status"] == "NOT_ACHIEVED":
        message = "요청한 방향으로 글자 크기가 바뀌지 않은 것을 확인했어요."
    else:
        message = "변경 결과를 확인하지 못했어요."
    return {"technical": technical, "user": {"status": None, "confirmed_at": None},
        "final": None, "prompt": {"id": str(uuid4()), "expires_at": now + 180,
        "message": message + " 이제 글자가 보기 편한가요?"},
        "checklist": deepcopy((previous or {}).get("checklist", []))}


def answer_outcome(outcome, answer, now):
    result = deepcopy(outcome)
    result["user"] = {"status": answer, "confirmed_at": now}
    result["final"] = {"satisfied": "ACHIEVED" if result["technical"]["status"] == "CHANGED"
                        else "USER_REPORTED_RESOLVED", "needs_help": "ADDITIONAL_HELP",
                        "stopped": "USER_STOPPED", "no_response": "NO_RESPONSE"}[answer]
    return result


def follow_up(outcome):
    direction = outcome.get("technical", {}).get("limit_direction")
    choices = [{"id": "too_small", "label": "글자가 아직 작아요"},
               {"id": "too_large", "label": "글자가 너무 커요"},
               {"id": "other", "label": "크기 말고 다른 점이 불편해요"},
               {"id": "unsure", "label": "잘 모르겠어요"}]
    if direction == "increase":
        choices = [c for c in choices if c["id"] != "too_small"]
    if direction == "decrease":
        choices = [c for c in choices if c["id"] != "too_large"]
    message = "어떤 점이 불편한가요? 조정 방향을 확인한 뒤 진행할게요."
    if direction:
        message = "이미 조절 가능한 끝값이어서 같은 방향으로 더 바꿀 수 없어요. 다른 점이 불편한가요?"
    return {"message": message, "choices": choices}

"""Task-specific result confirmation. This is independent of safety consent and ratings."""
from copy import deepcopy
from uuid import uuid4


def make_outcome(technical, now, direction="increase", *, previous=None, setting=None):
    technical = deepcopy(technical) or {"status": "UNVERIFIABLE", "reason": "insufficient_evidence"}
    setting = setting or (previous or {}).get('setting', 'font_size')
    label = {'font_size': '글자 크기', 'brightness': '화면 밝기', 'volume': '미디어 음량'}.get(setting, '설정')
    if technical["status"] == "CHANGED":
        message = "글자 크기를 키웠어요." if direction == "increase" else "글자 크기를 줄였어요."
    elif technical.get("reason") == "limit_reached":
        message = "이미 가장 큰 글자 크기예요." if direction == "increase" else "이미 가장 작은 글자 크기예요."
    elif technical["status"] == "NOT_ACHIEVED":
        message = "요청한 방향으로 글자 크기가 바뀌지 않은 것을 확인했어요."
    else:
        message = "변경 결과를 확인하지 못했어요."
    question = '이제 글자가 보기 편한가요?'
    if setting != 'font_size':
        object_label = label + ('을' if setting == 'volume' else '를')
        subject_label = label + ('이' if setting == 'volume' else '가')
        if technical['status'] == 'CHANGED':
            message = f'{object_label} {"높였어요" if direction == "increase" else "낮췄어요"}.'
        elif technical.get('reason') == 'limit_reached':
            message = f'{subject_label} 이미 {"최댓값" if direction == "increase" else "최솟값"}이에요.'
        elif technical['status'] == 'NOT_ACHIEVED':
            message = f'{subject_label} 요청한 방향으로 바뀌지 않았어요.'
        question = '화면이 보기 편한가요?' if setting == 'brightness' else '동영상·음악 소리가 듣기 편한가요?'
    return {"setting": setting, "technical": technical, "user": {"status": None, "confirmed_at": None},
        "final": None, "prompt": {"id": str(uuid4()), "expires_at": now + 180,
        "message": message + " " + question},
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
    setting = outcome.get('setting', 'font_size')
    if setting in {'brightness', 'volume'}:
        # Keep the wire direction IDs compatible while presenting task-specific choices.
        choices[0]['label'] = '화면이 아직 어두워요' if setting == 'brightness' else '소리가 아직 작아요'
        choices[1]['label'] = '화면이 너무 밝아요' if setting == 'brightness' else '소리가 너무 커요'
        choices[2]['label'] = '다른 점이 불편해요'
    if direction == "increase":
        choices = [c for c in choices if c["id"] != "too_small"]
    if direction == "decrease":
        choices = [c for c in choices if c["id"] != "too_large"]
    message = "어떤 점이 불편한가요? 조정 방향을 확인한 뒤 진행할게요."
    if direction:
        message = "이미 조절 가능한 끝값이어서 같은 방향으로 더 바꿀 수 없어요. 다른 점이 불편한가요?"
    return {"message": message, "choices": choices}

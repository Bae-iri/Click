"""Task-specific result confirmation. This is independent of safety consent and ratings."""
from copy import deepcopy
from uuid import uuid4
from ai_agent.runtime.task_success import normalize_technical, technical_success, success_contract
from ai_agent.runtime.settings_controls import DOCUMENT, CONTROLS


def make_outcome(technical, now, direction="increase", *, previous=None, setting=None, goal_type=None):
    goal_type = goal_type or (previous or {}).get('goal_type')
    technical = deepcopy(technical) or {"status": "UNVERIFIABLE", "reason": "insufficient_evidence"}
    spec = success_contract(goal_type)
    setting = spec.get('setting') or setting or (previous or {}).get('setting', 'font_size')
    direction = spec.get('direction') or direction
    requested_state = spec.get('requested_state') if spec else (previous or {}).get('requested_state')
    if spec or requested_state is not None:
        previous = {**(previous or {}), 'requested_state': requested_state}
    technical = normalize_technical(technical, goal_type)
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
    if setting == 'wifi':
        requested = technical.get('requested_state') or (previous or {}).get('requested_state', 'on')
        state_label = '켜져' if requested == 'on' else '꺼져'
        if technical['status'] == 'CHANGED':
            message = '와이파이를 켰어요.' if requested == 'on' else '와이파이를 껐어요.'
        elif technical['status'] == 'ALREADY_SET':
            message = f'와이파이가 이미 {state_label} 있어요.'
        else:
            message = '와이파이 상태 변경을 확인하지 못했어요.'
        question = '원하신 상태로 설정되었나요?'
    if setting in CONTROLS:
        label = next(v['name'].rsplit(' ', 1)[0] for v in DOCUMENT['goals'].values() if v['setting'] == setting)
        requested = technical.get('requested_state') or (previous or {}).get('requested_state')
        if technical['status'] == 'CHANGED':
            message = f'{label}: 요청한 {"켜짐" if requested == "on" else "꺼짐"} 스위치 상태를 확인했어요.'
            if technical.get('reason') in {'system_night_mode_on', 'system_night_mode_off'}:
                message = f'{label}: 요청한 {"다크" if requested == "on" else "라이트"} 모드 적용을 확인했어요.'
        elif technical['status'] == 'ALREADY_SET':
            message = f'{label}: 이미 요청한 상태예요.'
        else:
            message = f'{label} 변경 결과를 확인하지 못했어요.'
        question = '원하신 상태로 설정되었나요?'
    if technical.get('reason') == 'manual_result_required' or setting not in {'font_size', 'brightness', 'volume', 'wifi', *CONTROLS, 'mute', 'vibrate'}:
        message = '직접 진행한 작업 결과를 확인해 주세요. 앱에서 변경 성공을 검증하지는 못했어요.'
        question = '원하시는 결과를 얻었나요?'
    if setting in {'mute', 'vibrate'}:
        value = technical.get('setting_value')
        selected = {0: '무음', 1: '진동', 2: '소리'}.get(value)
        if technical_success(technical, goal_type) and selected:
            message = f'{selected} 모드 적용을 확인했어요.' if technical['status'] == 'CHANGED' else f'이미 {selected} 모드예요.'
        else:
            message = '소리 모드 변경 결과를 확인하지 못했어요.'
        question = '원하신 소리 모드로 설정되었나요?'
    if technical.get('reason') == 'target_value_changed_opposite_direction':
        message = '요청한 방향과 반대로 바뀌었어요. 원하는 변경 방향을 다시 확인해 주세요.'
    elif technical.get('reason') == 'no_correlated_action':
        message = '조작 기록과 변경 값을 연결하지 못해 결과를 확인하지 못했어요.'
    elif technical.get('reason') in {'target_value_unobservable', 'native_switch_unobservable'}:
        message = '원래 조절하던 대상을 다시 확인하지 못해 변경 결과를 확인하지 못했어요.'
    elif technical.get('reason') == 'applied_setting_unobservable':
        message = '기기에 적용된 설정 값을 읽지 못해 변경 결과를 확인하지 못했어요.'
    kind=success_contract(goal_type).get('kind')
    if kind=='page_opened':
        message='요청하신 설정 화면을 열었어요.' if technical_success(technical,goal_type) else '요청하신 설정 화면을 확인하지 못했어요.'
        question='원하신 화면인가요?'
    elif kind=='adapter_power':
        message += ' 와이파이 켜짐 상태와 네트워크 연결은 별개예요.'
    elif goal_type=='settings.bluetooth.connect':
        message='블루투스를 켜는 것과 기기 연결은 별개예요. 연결할 기기에 실제로 연결되었는지 확인해 주세요.'
    return {"goal_type": goal_type, "setting": setting, "requested_state": (previous or {}).get('requested_state'), "technical": technical, "user": {"status": None, "confirmed_at": None},
        "final": None, "prompt": {"id": str(uuid4()), "expires_at": now + 180,
        "message": message + " " + question, "technical_status": technical["status"]},
        "checklist": deepcopy((previous or {}).get("checklist", []))}


def answer_outcome(outcome, answer, now):
    result = deepcopy(outcome)
    result["user"] = {"status": answer, "confirmed_at": now}
    result["final"] = {"satisfied": "ACHIEVED" if technical_success(result["technical"], result.get("goal_type"))
                        else "USER_REPORTED_RESOLVED", "needs_help": "ADDITIONAL_HELP",
                        "stopped": "USER_STOPPED", "no_response": "NO_RESPONSE"}[answer]
    return result


def follow_up(outcome):
    if outcome.get('setting') not in {'font_size', 'brightness', 'volume', 'wifi'}:
        return {'message': '기기에서 선택한 항목과 현재 설정 상태를 확인해 주세요. 어떤 도움이 더 필요한가요?',
            'choices': [{'id': 'other', 'label': '다른 도움이 필요해요'}, {'id': 'unsure', 'label': '잘 모르겠어요'}]}
    if outcome.get('setting') == 'wifi':
        return {'message': '와이파이 켜짐 상태와 네트워크 연결은 별개예요. 어떤 도움이 더 필요한가요?',
            'choices': [{'id': 'other', 'label': '다른 도움이 필요해요'}, {'id': 'unsure', 'label': '잘 모르겠어요'}]}
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

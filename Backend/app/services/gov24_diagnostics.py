"""Explain guard decisions using finite codes; never include AX text, IDs or input values."""
from app.services import public_guide_policy as policy


MESSAGES = {
    'authentication_required': '정부24에서 직접 로그인한 뒤 홈으로 돌아와 주세요. 원래 민원 요청을 이어갈게요.',
    'navigation_limit': '메뉴 안내가 여러 차례 반복되어 멈췄어요. 정부24에서 직접 확인한 뒤 새 안내를 시작해 주세요.',
    'observation_missing': '아직 화면 관측을 받지 못했어요. 정부24를 열고 현재 화면 확인을 눌러주세요.',
    'snapshot_missing': '기기에서 현재 화면을 읽지 못했어요. 화면이 뜬 뒤 다시 확인해 주세요.',
    'stale_observation': '받은 화면 정보가 30초보다 오래됐어요. 현재 화면을 다시 확인해 주세요.',
    'future_observation': '기기와 서버의 관측 시각이 맞지 않아요. 휴대폰의 자동 날짜·시간 설정을 확인해 주세요.',
    'outside_gov24_app': '현재 정부24 화면이 아니에요. 정부24로 돌아가 현재 화면 확인을 눌러주세요.',
    'package_mismatch': '관측의 앱 식별자가 정부24와 일치하지 않아 안내하지 않았어요. 정부24 화면을 다시 확인해 주세요.',
    'unrecognized_screen': '공개 화면의 안내 항목을 식별하지 못했어요. 화면이 뜬 뒤 다시 확인해 주세요.',
    'empty_accessibility_tree': '기기에서 접근성 요소를 읽지 못했어요. 화면이 뜬 뒤 다시 확인해 주세요.',
    'no_visible_nodes': '현재 보이는 접근성 요소를 읽지 못했어요. 화면이 뜬 뒤 다시 확인해 주세요.',
    'no_public_options': '현재 화면에서 허용된 공개 안내 항목을 찾지 못했어요. 정부24 메뉴에서 다시 확인해 주세요.',
    'ambiguous_public_options': '같은 공개 항목이 중복되어 안내 대상을 구분하지 못했어요. 화면을 다시 확인해 주세요.',
    'public_options_limit': '공개 안내 항목이 관측 한도를 초과했어요. 메뉴를 좁힌 뒤 다시 확인해 주세요.',
    'no_exported_nodes': '내보낸 공개 안내 항목이 없어요. 접근성 정보 부족인지 필터 결과인지는 이 앱 버전에서 구분할 수 없어요.',
    'invalid_public_option': '받은 안내 항목이 서버의 허용된 공개 항목 형식과 달라 사용하지 않았어요.',
    'credential_input': '입력 필드를 감지해 안내를 멈췄어요. 이 앱 버전은 검색창과 개인정보 입력창을 구분해 보고하지 않아요.',
    'password_field': '비밀번호 입력 필드를 감지했어요. 화면 전송과 안내를 멈췄으니 정부24에서 직접 진행해 주세요.',
    'sensitive_input_field': '입력 필드를 감지해 화면 전송과 안내를 멈췄어요. 개인정보 입력인지 공개 검색창인지는 확인하지 못했어요.',
    'empty_search_field': '비어 있는 검색 필드를 감지했지만 공개 화면임을 확인하지 못해 입력 필드 차단 규칙에 따라 안내를 멈췄어요.',
    'certificate_selection': '인증·개인정보 경계 문구를 감지했어요. 이 앱 버전은 정확한 경계 유형을 보고하지 않아요. 직접 확인해 주세요.',
    'certificate_prompt': '인증 관련 문구를 감지해 화면 전송과 안내를 멈췄어요. 정부24에서 직접 확인해 주세요.',
    'private_identity_label': '개인 식별정보 관련 문구를 감지해 화면 전송과 안내를 멈췄어요. 정부24에서 직접 확인해 주세요.',
    'consent_required': '개인정보 이용 동의 화면을 감지했어요. 화면 전송과 안내를 멈췄으니 내용을 직접 확인해 주세요.',
    'consent_prompt': '개인정보 이용 동의 문구를 감지했어요. 화면 전송과 안내를 멈췄으니 내용을 직접 확인해 주세요.',
    'external_auth': '외부 인증 앱으로 이동해 화면 전송과 안내를 멈췄어요. 해당 앱에서 직접 인증해 주세요.',
    'external_auth_app': '외부 인증 앱으로 이동해 화면 전송과 안내를 멈췄어요. 해당 앱에서 직접 인증해 주세요.',
    'foreign_package_node': '다른 앱 소속의 접근성 요소가 섞여 있어 화면 전송과 안내를 멈췄어요. 다른 창을 닫고 정부24에서 다시 시작해 주세요.',
    'inactive_window_node': '현재 활성 창과 다른 창의 접근성 요소가 섞여 있어 화면 전송과 안내를 멈췄어요. 창을 정리한 뒤 다시 시작해 주세요.',
    'sensitive_screen_unspecified': '기기의 개인정보 보호 검사가 안내를 중단했어요. 이 앱 버전은 세부 감지 사유를 보고하지 않아요.',
    'turn_limit': '화면 확인 횟수 한도에 도달했어요. 목표 실패로 판정한 것은 아니에요. 정부24 화면을 확인하고 새 안내를 시작해 주세요.',
    'planning_budget_exhausted': '계획 생성 횟수 한도에 도달했어요. 정부24에서 직접 확인하거나 새 안내를 시작해 주세요.',
    'authentication_step': '선택 후 이동한 화면에서 공개 안내 항목을 확인하지 못해 안내를 멈췄어요. 인증 화면인지는 확인하지 못했으니 정부24에서 직접 확인해 주세요.',
    'no_candidate_for_goal': '현재 화면에서 요청한 목표로 안내할 공개 항목을 찾지 못했어요. 목록을 이동한 뒤 다시 확인해 주세요.',
}


def inspect(screen, now_ms, *, civil=True):
    """Mirror sanitize's gates, retaining why rejected content was not used by models."""
    report = {'accepted': False, 'screen_status': screen.status if screen else None,
              'privacy_reason': screen.privacy_reason if screen else None,
              'handoff_reason': screen.handoff_reason if screen else None,
              'native_detail_available': bool(screen and screen.privacy_reason),
              'exported_node_count': len(screen.nodes) if screen else 0}
    if screen is None:
        return dict(report, code='observation_missing')
    report.update(age_ms=now_ms - screen.captured_at_ms,
                  package_matches=screen.package_name == policy.POLICY['package'])
    if report['age_ms'] > 30000:
        return dict(report, code='stale_observation')
    if report['age_ms'] < -5000:
        return dict(report, code='future_observation')
    if screen.status == 'handoff':
        if screen.handoff_reason != 'external_auth' and not report['package_matches']:
            return dict(report, code='package_mismatch')
        return dict(report, code=private_code(screen), accepted=True)
    if screen.status == 'sensitive':
        return dict(report, code=private_code(screen))
    if screen.status == 'outside_app':
        return dict(report, code='outside_gov24_app')
    if screen.status == 'unrecognized':
        return dict(report, code=screen.privacy_reason or 'unrecognized_screen')
    if not report['package_matches']:
        return dict(report, code='package_mismatch')
    if policy.sanitize(screen, now_ms, civil=civil) is None:
        return dict(report, code='invalid_public_option')
    return dict(report, code='public_observation' if screen.nodes or screen.auth_state != 'unknown' else 'no_exported_nodes', accepted=True)


def private_code(screen):
    return screen.privacy_reason or screen.handoff_reason or 'sensitive_screen_unspecified'


def diagnostic(reason_code, category, stage, **facts):
    return {'code': reason_code, 'category': category, 'stage': stage, 'message': MESSAGES[reason_code], 'facts': facts}


def attach(result, evidence, state, request, now_ms, *, civil=False):
    """Share content-free explanations across current civil-service and login flows."""
    report = inspect(request.screen, now_ms, civil=civil)
    evidence['observation_check'] = report
    detail = None
    if result.status == 'BLOCKED':
        detail = diagnostic('navigation_limit', 'budget', 'guidance_limit', navigation_turns=state.get('navigation_turns',0), max_turns=policy.POLICY['max_turns'])
    elif result.subgoal in {'await_login', 'await_public'}:
        code = private_code(request.screen) if request.screen and request.screen.status in {'handoff','sensitive'} else report['code']
        if code == 'public_observation':
            code = 'authentication_required'
        detail = diagnostic(code, 'privacy' if report['screen_status'] in {'handoff','sensitive'} else 'observation', 'manual_wait', **report)
    elif result.outcome == 'handoff':
        if evidence['reason'] == 'authentication_step':
            detail = diagnostic('authentication_step', 'observation', 'observation_validation', **report)
        else:
            detail = diagnostic(private_code(request.screen), 'privacy', 'privacy_guard', **report)
    elif request.command not in {'cancel', 'finish'}:
        if report['code'] != 'public_observation':
            detail = diagnostic(report['code'], 'observation', 'observation_validation', **report)
        elif evidence['reason'] == 'no_candidates':
            detail = diagnostic('no_candidate_for_goal', 'observation', 'candidate_selection', **report)
    if detail:
        waiting = result.subgoal in {'await_login','await_public'} and result.status == 'IN_PROGRESS'
        detail.update(version='gov24-diagnostics.v1',
                      status='WAITING' if waiting else 'BLOCKED' if detail['category'] in {'budget', 'privacy'} else 'UNKNOWN')
        evidence['reason'] = detail['code']
        if not waiting:
            result.message = detail['message']
    result.diagnostic = detail
    evidence['diagnostic'] = detail
    state['public_guide_diagnostic'] = detail
    state['public_guide_message'] = result.message

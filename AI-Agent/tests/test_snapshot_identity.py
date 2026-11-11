from dataclasses import replace
from ai_agent.runtime.state_manager import build_screen_snapshot
from ai_agent.runtime.types import NormalizedNode,ScreenSnapshot

def nodes(window=1,label='글자 크기',value=2):
    return (NormalizedNode('title','디스플레이','text',bounds=(20,20,1000,80),package_name='com.android.settings',window_id=window),
            NormalizedNode('target',label,'slider',bounds=(20,400,1000,500),package_name='com.android.settings',window_id=window,value=value))

def test_page_target_and_value_are_independent():
    before=build_screen_snapshot('S_DISPLAY',nodes())
    after=build_screen_snapshot('S_DISPLAY',nodes(window=2,label='밝기',value=3))
    assert before.page_fingerprint==after.page_fingerprint
    assert before.navigation_fingerprint!=after.navigation_fingerprint
    assert before.fingerprint!=after.fingerprint

def test_slider_and_persisted_values_are_not_navigation_identity():
    before=build_screen_snapshot('S_DISPLAY',nodes(),applied_settings={'font_scale':1})
    after=build_screen_snapshot('S_DISPLAY',nodes(value=3),applied_settings={'font_scale':1.15,'wifi_state':3})
    assert before.page_fingerprint==after.page_fingerprint
    assert before.navigation_fingerprint==after.navigation_fingerprint
    assert before.fingerprint!=after.fingerprint
    assert before.native_fingerprint!=after.native_fingerprint

def test_missing_title_is_unknown_and_legacy_checkpoint_restores():
    assert build_screen_snapshot('S_UNKNOWN',()).page_fingerprint is None
    old={'state_id':'S_UNKNOWN','fingerprint':'old'}
    restored=ScreenSnapshot(**old)
    assert restored.page_fingerprint is None and restored.navigation_fingerprint is None

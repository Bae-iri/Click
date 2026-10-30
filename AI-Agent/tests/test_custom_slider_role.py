from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.types import AccessibilityNode


def test_explicit_interactive_slider_role_preserves_generic_view_range():
    node=AccessibilityNode('n',class_name='android.view.View',role='slider',
        bounds_in_screen=(0,0,500,100),extras={'progress':2,'min':0,'max':7})
    normalized=normalize_accessibility_tree((node,))
    assert normalized[0].role=='slider'
    assert normalized[0].value==2


def test_declared_slider_without_valid_range_does_not_promote_readonly_view():
    node=AccessibilityNode('n',class_name='android.view.View',role='slider',
        bounds_in_screen=(0,0,500,100),extras={'progress':2})
    assert not normalize_accessibility_tree((node,))

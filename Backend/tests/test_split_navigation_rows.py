from dataclasses import replace
import pytest
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.targeting import eligible_nodes, selector_evidence
from ai_agent.runtime.types import AccessibilityNode
from app.task_graphs.settings_navigation import navigation_releases
import json
from pathlib import Path


def row(label='Wi-Fi',switch_left=850):
    pkg='com.android.settings'
    def node(id,**kw):return AccessibilityNode(id,package_name=pkg,window_id=7,**kw)
    return (
        node('root',bounds_in_screen=(0,0,1080,2400)),
        node('heading',parent_id='root',text='연결',class_name='android.widget.TextView',bounds_in_screen=(20,30,900,100)),
        node('list',parent_id='root',scrollable=True,class_name='android.widget.ListView',bounds_in_screen=(0,150,1080,2200)),
        node('row',parent_id='list',clickable=True,class_name='android.widget.LinearLayout',bounds_in_screen=(0,200,1080,400)),
        node('title',parent_id='row',text=label,resource_id='android:id/title',class_name='android.widget.TextView',bounds_in_screen=(30,240,700,320)),
        node('switch',parent_id='row',clickable=True,checkable=True,extras={'checked':True},
             resource_id='android:id/switch_widget',class_name='android.widget.Switch',bounds_in_screen=(switch_left,240,1040,320)),
    )


@pytest.mark.parametrize(('key','label'),[('wifi','Wi-Fi'),('bluetooth','블루투스')])
def test_split_row_navigation_and_value_control_remain_separate(key,label):
    graph=next(g for _,g in navigation_releases() if g.definition.goal_type=='settings.navigation.'+key)
    edge=next(t for t in graph.transitions if t.transition_id=='OPEN_PAGE')
    nodes=normalize_accessibility_tree(row(label),screen_bounds=(0,0,1080,2400),active_window_id=7)
    candidates=[n for n in eligible_nodes(edge,nodes,graph) if selector_evidence(n,edge,nodes,graph)[0]>=.7]
    assert [n.node_id for n in candidates]==['row']
    switch=next(n for n in nodes if n.node_id=='switch')
    assert switch.role=='toggle' and switch.checked is True
    assert switch.node_id not in {n.node_id for n in candidates}


def test_overlapping_switch_region_is_not_promoted_to_a_navigation_row():
    nodes=normalize_accessibility_tree(row(switch_left=400),screen_bounds=(0,0,1080,2400),active_window_id=7)
    assert not any(n.node_id=='row' and n.label for n in nodes)


@pytest.mark.parametrize('key',['wifi','bluetooth'])
def test_physical_samsung_nested_preference_preserves_its_menu_and_switch(key):
    fixture=json.loads((Path(__file__).parent/'fixtures/samsung-public-navigation-20261122.json').read_text())
    tree=next(c['tree'] for c in fixture['cases'] if c['task']==key)
    raw=tuple(AccessibilityNode(**{**n,'bounds_in_screen':tuple(n['bounds_in_screen'])}) for n in tree['nodes'])
    nodes=normalize_accessibility_tree(raw,screen_bounds=(0,0,tree['screen_width'],tree['screen_height']),active_window_id=tree['active_window_id'])
    graph=next(g for _,g in navigation_releases() if g.definition.goal_type=='settings.navigation.'+key)
    edge=next(e for e in graph.transitions if e.transition_id=='OPEN_PAGE')
    candidates=[n for n in eligible_nodes(edge,nodes,graph) if selector_evidence(n,edge,nodes,graph)[0]>=.7]
    assert len(candidates)==1
    assert candidates[0].role=='menu_item' and candidates[0].clickable
    assert any(n.role=='toggle' and n.checked is not None for n in nodes)

"""Graph-independent native foreground identity. Public context contains enums only."""
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
import json

@lru_cache(maxsize=1)
def policy():
    value=json.loads((Path(__file__).resolve().parents[3]/'goal_catalog/screen_environment_policy.json').read_text())
    if value['schema_version']!='screen-environment.v1':raise ValueError('Unsupported environment policy')
    return value

def recovery_rule(reason):
    return dict(policy()['recovery'].get(reason,{}))

def safe_window_context(value):
    value=value if isinstance(value,dict) else {}
    out={}
    for key,allowed in (('settings_activity',{'UNKNOWN','HOME','SUBPAGE'}),
                        ('window_kind',{'UNKNOWN','APPLICATION','INPUT_METHOD','SYSTEM','OVERLAY','DIALOG'})):
        if isinstance(value.get(key),str) and value[key] in allowed:out[key]=value[key]
    if type(value.get('keyboard_visible')) is bool:out['keyboard_visible']=value['keyboard_visible']
    return out


def native_control_surface(nodes,package=None):
    from math import isfinite
    for setting,spec in policy()['native_control_surfaces'].items():
        if package and package!=spec['package']:continue
        relevant=[n for n in nodes if getattr(n,'visible_to_user',True) and n.enabled]
        if not relevant or any(n.package_name!=spec['package'] for n in relevant):continue
        if not any(n.resource_id in spec['wrapper_ids'] for n in relevant):continue
        sliders=[n for n in relevant if n.role=='slider' or n.class_name=='android.widget.SeekBar']
        if len(sliders)!=1 or sliders[0].resource_id not in spec['slider_ids']:continue
        node=sliders[0];extras=getattr(node,'extras',{})
        values=(extras.get('min',getattr(node,'range_min',None)),extras.get('max',getattr(node,'range_max',None)),extras.get('progress',getattr(node,'value',None)))
        if not all(type(v) in (int,float) and isfinite(v) for v in values):continue
        low,high,value=values
        if not low<high or not low<=value<=high:continue
        by_id={n.node_id:n for n in relevant};needed=set();current=node
        while current is not None and current.node_id not in needed:
            needed.add(current.node_id);current=by_id.get(getattr(current,'parent_id',None))
        if not any(n.node_id in needed and n.resource_id in spec['wrapper_ids'] for n in relevant):continue
        return {'setting':setting,'package':spec['package'],'node_ids':needed}
    return None


@dataclass(frozen=True)
class Environment:
    kind:str
    reason:str
    heading:str|None=None  # Local/native evidence only. Never emitted in public diagnostics.
    app_package:str|None=None
    control_setting:str|None=None
    def public(self):return {'schema_version':'screen-environment.v1','kind':self.kind,'reason':self.reason}


def classify_environment(request):
    from ai_agent.runtime.observation_context import vision_package_name
    from ai_agent.runtime.screen_privacy import assess_screen
    from ai_agent.runtime.normalizer import normalize_accessibility_tree
    from ai_agent.runtime.classifier import _screen_titles
    pkg=next((n.package_name for n in request.accessibility_tree if n.visible_to_user and n.package_name),None) or vision_package_name(request)
    if not pkg:return Environment('UNOBSERVABLE','OBSERVATION_EMPTY')
    p=policy();nodes=request.accessibility_tree;hints=safe_window_context(request.window_context)
    settings=pkg in p['settings_packages'];launcher=pkg in p['launcher_packages']
    if pkg in p['system_packages'] and pkg!='com.android.systemui':return Environment('SYSTEM_DIALOG','SYSTEM_DIALOG_VISIBLE')
    if not settings and not launcher and pkg!='com.android.systemui':return Environment('OTHER_APP','OTHER_APP',app_package=pkg)
    if settings:
        privacy=assess_screen(nodes,None,pkg)
        if privacy.status=='sensitive_screen':return Environment('SENSITIVE','SENSITIVE_INPUT' if privacy.reason in {'input_present','credential_input'} else 'SENSITIVE_SCREEN')
    if hints.get('keyboard_visible') is True or hints.get('window_kind')=='INPUT_METHOD':return Environment('KEYBOARD','KEYBOARD_VISIBLE')
    surface=native_control_surface(nodes,pkg)
    if surface:return Environment('CONTROL_SURFACE','NATIVE_CONTROL_VISIBLE',control_setting=surface['setting'])
    if pkg=='com.android.systemui':return Environment('SYSTEM_PANEL','SYSTEM_PANEL_VISIBLE')
    def tokens(key):
        return any(n.visible_to_user and any(word in ((n.resource_id or '')+' '+(n.class_name or '')).lower() for word in p[key]) for n in nodes)
    if launcher:
        if tokens('recents_tokens'):return Environment('RECENTS','RECENTS_VISIBLE')
        if tokens('drawer_tokens'):return Environment('APP_DRAWER','APP_DRAWER_VISIBLE')
        if tokens('home_tokens'):return Environment('HOME','HOME_VISIBLE')
        return Environment('LAUNCHER','LAUNCHER_VISIBLE')
    privacy=assess_screen(nodes,None,pkg)
    if privacy.status=='sensitive_screen':return Environment('SENSITIVE','SENSITIVE_INPUT' if privacy.reason in {'input_present','credential_input'} else 'SENSITIVE_SCREEN')
    from ai_agent.runtime.screen_privacy import filter_nodes
    normalized=normalize_accessibility_tree(filter_nodes(nodes,None),screen_bounds=request.screen_bounds,active_window_id=request.active_window_id)
    titles=[n for n in _screen_titles(normalized) if n.package_name==pkg and n.source_confidence is None]
    title=titles[0].label if len(titles)==1 else None
    if hints.get('window_kind')=='DIALOG' or any(n.visible_to_user and n.class_name in p['dialog_classes'] for n in nodes):return Environment('DIALOG','DIALOG_VISIBLE',title)
    if hints.get('settings_activity')=='SUBPAGE' or title and title not in p['settings_home_titles']:
        return Environment('SETTINGS_SUBPAGE','SETTINGS_SUBPAGE_VISIBLE',title)
    if hints.get('settings_activity')=='HOME' or title and tokens('settings_home_tokens'):
        return Environment('SETTINGS_HOME','SETTINGS_HOME_VISIBLE',title)
    return Environment('SETTINGS_UNKNOWN','SETTINGS_PAGE_UNIDENTIFIED',title)


def environment_recovery(environment,setting):
    """Decide route compatibility after environment identity; never grant an operation."""
    kind=environment.kind
    if kind=='CONTROL_SURFACE':return None if environment.control_setting==setting else ('SYSTEM_PANEL_VISIBLE','dismiss_interruption')
    if kind=='OTHER_APP':
        if environment.app_package in policy()['manual_packages_by_setting'].get(setting,()):return ('MANUAL_EXTERNAL_CHOICE','manual_choice')
        return ('OTHER_APP','open_settings')
    if kind=='RECENTS':return ('RECENTS_VISIBLE','go_home')
    if kind=='SYSTEM_DIALOG':return ('SYSTEM_DIALOG_VISIBLE','manual_choice')
    if kind=='DIALOG':
        from ai_agent.runtime.screen_privacy import normalized_labels
        heading=' '.join((environment.heading or '').lower().split())
        if heading and heading in normalized_labels(setting) and heading not in policy()['settings_home_titles']:return None
    if kind in {'KEYBOARD','DIALOG','SYSTEM_PANEL'}:return (environment.reason,'dismiss_interruption')
    if kind=='SETTINGS_SUBPAGE':
        from ai_agent.runtime.screen_privacy import normalized_labels
        heading=' '.join((environment.heading or '').lower().split())
        # Generic Settings on a native sub-Activity is not evidence of the Settings home.
        if heading and (heading not in normalized_labels(setting) or heading in policy()['settings_home_titles']):
            return ('SETTINGS_OFF_PATH','go_back')
        if not heading:return ('SETTINGS_OFF_PATH','go_back')
    return None

"""Generate the shared native-navigation capability contract from approved graphs."""
import argparse
import json
from pathlib import Path
from app.task_graphs import releases
from ai_agent.runtime.targeting import selector_keywords
from ai_agent.task_graph.settings import CHOICES, choice_spec, choice_predicate

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'AI-Agent/goal_catalog/settings_capabilities.json'
# Safety constraints are explicit, finite and shared; graph approval never authorizes value controls.
RISKY = ['결제','구매','송금','전화 걸기','메시지 보내기','초기화','삭제','비밀번호',
         'payment','purchase','send message','make call','reset','delete','password']
ALIASES = {'settings':'설정','display':'디스플레이','screen':'디스플레이','화면':'디스플레이',
    'accessibility':'접근성','visibility':'시인성 향상','visibility enhancements':'시인성 향상','시인성':'시인성 향상',
    'font size':'글자 크기','text size':'글자 크기','글꼴 크기':'글자 크기','폰트 크기':'글자 크기',
    'font size and style':'글자 크기와 스타일','글꼴 크기 및 스타일':'글자 크기와 스타일','글자 크기 및 스타일':'글자 크기와 스타일',
    'general management':'일반','sound & vibration':'소리 및 진동','sound and vibration':'소리 및 진동',
    'sound':'소리','volume':'음량','brightness':'밝기'}

def normalized(value):
    return ' '.join(value.strip().lower().split())

def build():
    privacy=json.loads((ROOT/'AI-Agent/goal_catalog/screen_privacy_policy.json').read_text())
    execution=json.loads((ROOT/'AI-Agent/goal_catalog/settings_execution.json').read_text())['goals']
    goals={}; labels=set(); states=set()
    def safe(label):return not any(w in normalized(label) for w in RISKY)
    for release,graph in releases():
        if not release['approved']:continue
        routes={}
        for edge in graph.transitions:
            if (edge.action_type.value!='tap' or edge.safety_level.value!='LOW_RISK'
                    or edge.completes_task or edge.confirmation_message
                    or edge.target_role not in {'menu_item','app_icon'}):continue
            names=sorted({normalized(w) for w in selector_keywords(edge,graph) if safe(w)})
            packages=set(edge.selector.package_candidates or graph.states[edge.from_state].package_candidates) & set(privacy['settings_packages']+privacy['launcher_packages'])
            if not names or not packages or not packages.issubset(set(privacy['settings_packages']+privacy['launcher_packages'])):continue
            routes.setdefault(edge.from_state,[]).append({'transition_id':edge.transition_id,'labels':names,'label':edge.target_keywords[0],
                'packages':sorted(packages),'role':edge.target_role})
            if edge.target_role=='menu_item':states.add(edge.from_state);labels.update(names)
        slots=graph.definition.required_slots
        mode=execution.get(graph.definition.goal_type,{}).get('mode')
        kind='page_opened' if slots.get('action')=='open' else {
            'device_value':'persisted_value','device_wifi':'adapter_power','native_checked':'native_checked',
            'system_choice':'system_choice','assisted':'user_report'}.get(mode,'user_report')
        success={'kind':kind,'setting':slots.get('setting'),'direction':slots.get('direction'),
            'requested_state':slots.get('state')}
        option = choice_spec(slots)
        if option:
            contract = CHOICES[slots['setting']]
            success['system_choice'] = {
                'key': contract['system']['key'], 'source': contract['system']['source'],
                'requested_value': option['value'],
                'valid_values': sorted({v['value'] for v in contract['options'].values()}),
                'reason': choice_predicate(slots),
            }
        goals[graph.definition.goal_type]={'task_id':graph.definition.task_id,'task_type':graph.definition.task_type,
            'success':success,'navigation':routes,'page_titles':{state: [list(group) for group in graph.states[state].required_keyword_groups] for state in routes}}
    # Aliases extend only identities already present in an approved navigation route.
    identities={ALIASES.get(w,w) for w in labels}
    labels.update(k for k,v in ALIASES.items() if v in identities)
    return {'schema_version':'settings-capabilities.v1','navigation_states':sorted(states),
        'state_aliases':{'S2_SOUND_MENU':'S2_CATEGORY'},
        'navigation_labels':sorted(labels),'label_aliases':ALIASES,'blocked_label_tokens':RISKY,
        'settings_packages':privacy['settings_packages'],'launcher_packages':privacy['launcher_packages'],
        'private_identity_tokens':sorted(set(privacy['private_resource_tokens']+privacy['private_region_tokens'])),
        'split_switch_rows':{'title_resource':'android:id/title','switch_resources':['android:id/switch_widget','com.android.settings:id/switch_widget']},
        'search_budgets':{'launcher':{'max_moves':8,'max_duration_ms':45000},
                          'settings':{'max_moves':32,'max_duration_ms':120000}},'goals':goals}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--check',action='store_true');a=p.parse_args()
    data=json.dumps(build(),ensure_ascii=False,indent=2,sort_keys=True)+'\n'
    if a.check:
        if not OUTPUT.is_file() or OUTPUT.read_text()!=data:raise SystemExit('settings_capabilities.json is stale; run export_settings_capabilities.py')
    else:OUTPUT.write_text(data)
if __name__=='__main__':main()

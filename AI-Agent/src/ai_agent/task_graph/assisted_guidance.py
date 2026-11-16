"""Authored purpose and next steps for user-operated settings; never completion proof."""
import json
from pathlib import Path
from ai_agent.task_graph.settings import DOCUMENT

_DATA=json.loads((Path(__file__).resolve().parents[3]/'goal_catalog/manual_guidance.json').read_text(encoding='utf-8'))
if _DATA.get('schema_version')!='settings-manual-guidance.v1':
    raise ValueError('Unsupported manual guidance document')


def guidance(goal_type):
    record=DOCUMENT['goals'].get(goal_type,{})
    return _DATA['goals'].get(goal_type) if record.get('mode')=='assisted' else None


def start_message(goal_type, user_name=None):
    record=guidance(goal_type)
    if not record:
        return None
    if record.get('privacy_notice'):
        page = DOCUMENT['goals'][goal_type]['page_name']
        name = ' '.join(user_name.split()) if isinstance(user_name, str) else ''
        addressee = (name if name.endswith('님') else name + '님') + '께서 ' if name else ''
        return (f'{page} 설정 화면으로 안내할게요. '+record['privacy_notice']
                +f' 그때는 {addressee}직접 진행 부탁드려요!')
    return record['intent']+' 마지막 선택·입력·변경은 기기에서 직접 진행해 주세요.'


def handoff_message(goal_type,page_title,fallback):
    record=guidance(goal_type)
    return (f'‘{page_title}’ 화면이에요. '+record['manual']+' 직접 진행한 뒤 완료 확인을 눌러주세요.') if record else fallback


def privacy_message(goal_type,fallback):
    record=guidance(goal_type)
    return record['intent']+' '+fallback+' '+record['manual'] if record else fallback


def result_question(goal_type):
    record=guidance(goal_type)
    return record['result_question'] if record else None

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


def start_message(goal_type):
    record=guidance(goal_type)
    if not record:
        return None
    notice = record.get('privacy_notice', '마지막 선택·입력·변경은 기기에서 직접 진행해 주세요.')
    return record['intent']+' '+notice


def handoff_message(goal_type,page_title,fallback):
    record=guidance(goal_type)
    return (f'‘{page_title}’ 화면이에요. '+record['manual']+' 직접 진행한 뒤 완료 확인을 눌러주세요.') if record else fallback


def privacy_message(goal_type,fallback):
    record=guidance(goal_type)
    return record['intent']+' '+fallback+' '+record['manual'] if record else fallback


def result_question(goal_type):
    record=guidance(goal_type)
    return record['result_question'] if record else None

"""Task-graph input contracts owned by the server, shared by questions and selection."""
import json
from pathlib import Path
import re


def normalize(text):
    return re.sub(r"[^0-9a-z가-힣]", "", text.lower())


def negated(text):
    n = normalize(text)
    business_text = n.replace('주문을', '주문').replace('구매를', '구매').replace('결제를', '결제')
    business_cancel = n.count('취소') == 1 and any(word in business_text for word in (
        '주문취소', '구매취소', '결제취소', '취소처리', '취소상태', '취소내역'))
    return ('취소' in n and not business_cancel) or any(word in n for word in (
        "하지마", "하지말", "지마", "지말", "지않", "안해", "말아"))


class GoalInputRequirements:
    def __init__(self, document=None):
        self.document = document or json.loads((Path(__file__).parents[1] / "task_graphs/input_requirements.json").read_text(encoding="utf-8"))
        if document is None:
            catalog = json.loads((Path(__file__).parents[1] / 'task_graphs/goal_catalog.json').read_text(encoding='utf-8'))
            self.document['families'].extend({'id': g['goal_type'], 'goal_prefix': g['goal_type'],
                'keywords': [], 'fields': [], 'routes': [{'when': {}, 'target_app': 'coupang'}]}
                for g in catalog['goals'] if g['target_app'] == 'coupang')

    def family(self, goal_type=None, source_text=""):
        if goal_type:
            return next((f for f in sorted(self.document["families"], key=lambda f: len(f["goal_prefix"]), reverse=True)
                if goal_type == f['goal_prefix'] or goal_type.startswith(f['goal_prefix']) and f['goal_prefix'].endswith('.')), None)
        matches = [f for f in self.document["families"] if any(normalize(k) in normalize(source_text) for k in f["keywords"])]
        return matches[0] if len(matches) == 1 else None

    @staticmethod
    def required(family, values):
        return [f for f in family["fields"] if all(values.get(k) == v for k, v in f.get("required_when", {}).items())]

    def missing(self, family, values):
        return [f for f in self.required(family, values) if not values.get(f["id"])]

    @staticmethod
    def answer(field, value):
        if not isinstance(value, str) or not value.strip() or len(value) > field.get("max_length", 100):
            raise ValueError("INVALID_CLARIFICATION_ANSWER")
        value = value.strip()
        if any(ord(c) < 32 for c in value):
            raise ValueError("INVALID_CLARIFICATION_ANSWER")
        if field["kind"] == "choice" and value not in {c["value"] for c in field["choices"]}:
            raise ValueError("INVALID_CLARIFICATION_CHOICE")
        return value

    @staticmethod
    def directions(text, family=None):
        n = normalize(text)
        if family and family.get('direction_terms'):
            return {direction for direction, words in family['direction_terms'].items()
                    if any(normalize(word) in n for word in words)}
        return {direction for direction, words in (
            ("increase", ("크게", "키워", "키우", "확대", "늘려", "너무작", "작아서")),
            ("decrease", ("작게", "줄여", "줄이", "축소", "너무커", "커서")),
        ) if any(word in n for word in words)}

    @staticmethod
    def explicit_values(text, family=None):
        n = normalize(text)
        values = {}
        phone = any(word in n for word in ("휴대폰전체", "핸드폰전체", "스마트폰전체", "시스템전체", "모든앱",
            "기본글자", "휴대폰설정", "핸드폰설정", "설정에서", "시스템글자"))
        names = (("카카오톡", "카카오톡"), ("카톡", "카카오톡"), ("유튜브", "유튜브"),
                 ("네이버", "네이버"), ("쿠팡", "쿠팡"), ("크롬", "Chrome"), ("chrome", "Chrome"),
                 ("인스타", "Instagram"), ("삼성노트", "삼성 노트"))
        app = next((label for word, label in names if normalize(word) in n), None)
        match = re.search(r"([가-힣A-Za-z0-9 ]{1,40}?)\s*앱(?:에서|의|안|만)", text)
        if app is None and match and normalize(match[1]) not in {"이", "특정", "모든", "전체", "어떤"}:
            app = match[1].strip()
        app_scope = app is not None or any(word in n for word in ("특정앱", "이앱", "앱안에서만", "지금보는앱"))
        if phone and not app_scope:
            values["scope"] = "system"
        elif app_scope and not phone:
            values["scope"] = "app"
            if app:
                values["app_name"] = app
        if family and family['id'] in {'brightness', 'volume'}:
            # Media named after an app still uses the device's media stream. Only
            # an explicit request for an app-local control is a different scope.
            restricted = any(word in n for word in ('특정앱', '앱안에서만', '이앱만', '앱에서만'))
            if family['id'] == 'brightness' and app_scope:
                restricted = True
            values['scope'] = 'app' if restricted else 'system'
        if family and family['id'] == 'volume':
            media = any(word in n for word in ('미디어', '음악', '동영상', '영상', '유튜브', 'media', 'music'))
            other = any(word in n for word in ('벨소리', '알림', '알람', '통화', 'ringtone', 'notification', 'alarm', 'callvolume'))
            if media != other:
                values['audio_stream'] = 'media' if media else 'other'
        directions = GoalInputRequirements.directions(text, family)
        if len(directions) == 1:
            values["direction"] = next(iter(directions))
        return values

    def validate_selection(self, goal, values):
        family = self.family(goal.goal_type)
        if family is None:
            raise ValueError("GOAL_INPUT_CONTRACT_MISSING")
        if self.missing(family, values):
            raise ValueError("GOAL_INPUTS_INCOMPLETE")
        for field in self.required(family, values):
            self.answer(field, values[field["id"]])
            slot = field.get("goal_slot")
            if slot and goal.slots.get(slot) != values[field["id"]]:
                raise ValueError("GOAL_INPUTS_MISMATCH")
        route = next((r for r in family["routes"] if all(values.get(k) == v for k, v in r["when"].items())), None)
        if route is None or route["target_app"] != goal.target_app:
            raise ValueError("NO_GRAPH_FOR_REQUESTED_SCOPE")

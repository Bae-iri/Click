"""Split explicit independent requests; never infer omitted targets or conditions."""
import json
import re
from pathlib import Path
from app.services.goal_inputs import negated, normalize

_CATALOG=json.loads((Path(__file__).parents[1]/'task_graphs/goal_catalog.json').read_text())['goals']
_VERBS={'켜':'켜줘','끄':'꺼줘','키우':'키워줘','줄이':'줄여줘','낮추':'낮춰줘','높이':'높여줘',
    '열':'열어줘','바꾸':'바꿔줘','설정하':'설정해줘','연결하':'연결해줘','변경하':'변경해줘',
    '보여주':'보여줘','해주':'해줘','해 주':'해줘','해':'해줘','하':'해줘'}
_JOIN=re.compile('('+'|'.join(sorted(map(re.escape,_VERBS),key=len,reverse=True))+r')고\s*(?:나서\s*)?')
_SEPARATOR=re.compile(r'\s*(?:그리고|그\s*다음(?:에는|에|엔)?|다음으로|이어서|\band then\b|\bthen\b|\band\b|[;\n]+|,)\s*',re.I)
_CONDITIONAL=re.compile(r'하면|되면|있으면|없으면|켜지면|꺼지면|아니면|또는|혹은|대신|말고|\bif\b|\bunless\b|\bor\b',re.I)


def standalone(text):
    if re.match(r'^(싶|있는지|있어|주세요|주시|해줘|나서)',text):
        return False
    source=normalize(text)
    if any(all(any(normalize(word) in source for word in group) for group in g['keyword_groups']) for g in _CATALOG):
        return True
    # Keep an explicit unsupported request as its own queue item, so it cannot
    # silently disappear when another clause is a known settings request.
    return len(source)>=5 and bool(re.search(r'(해\s*줘|해\s*주세요|보내\s*줘|열어\s*줘|알려\s*줘|보여\s*줘)[.!?\s]*$',text)
        or re.match(r'^(open|send|show|connect|change|set|turn)\b',text,re.I))


def split_requests(source):
    original=source.strip()
    if negated(original) or _CONDITIONAL.search(original):
        return (original,)
    separated=_JOIN.sub(lambda m:_VERBS[m[1]]+'\n',original)
    parts=tuple(p.strip() for p in _SEPARATOR.split(separated) if p.strip())
    return parts if len(parts)>1 and all(standalone(p) for p in parts) else (original,)

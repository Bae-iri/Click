"""Replay captured Files screens through AppTaskService with live Qwen screen choice.

Usage: python files_replay.py <capture_dir> <env_file> <out.jsonl>
The projection approximates AppTaskObservation (app bar titles, checkable->toggle,
row label from direct child text, personal text removed).
"""
import json, os, re, sys, time
import xml.etree.ElementTree as ET

cap_dir, env_file, out = sys.argv[1:4]
for line in open(env_file, encoding='utf-8'):
    if line.startswith('OPENROUTER_API_KEY='):
        os.environ['OPENROUTER_API_KEY'] = line.split('=', 1)[1].strip().strip('"')
os.environ.update(APP_TASK_PROVIDER='openrouter', APP_TASK_OPENROUTER_SHORT_MENU='screen')

from app.schemas.app_task import AppTaskRequest, TaskApp, TaskAttempt, TaskSubgoal  # noqa: E402
from app.services.app_task import AppTaskService  # noqa: E402

APP_BAR = re.compile(r'(?:^|[:/_])(?:action_?bar|tool_?bar|app_?bar|collapsing_app_bar)(?:$|_)', re.I)
PERSONAL = re.compile(r'[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}|https?://|\b\d[\d .-]{7,}\d\b')


def label(s):
    s = ' '.join((s or '').split())
    return s if len(s) <= 80 and not PERSONAL.search(s) else ''


def project(path):
    root = ET.parse(path).getroot()
    parent, out = {}, []
    for p in root.iter():
        for c in p:
            parent[c] = p
    def b(n):
        return [int(v) for v in re.findall(r'\d+', n.get('bounds', '[0,0][0,0]'))]
    for i, n in enumerate(root.iter('node')):
        if n.get('package') != 'com.google.android.documentsui' or n.get('enabled') != 'true':
            continue
        click, scroll, chk = n.get('clickable') == 'true', n.get('scrollable') == 'true', n.get('checkable') == 'true'
        text = n.get('text') or ''
        title = False
        if not (click or scroll or chk) and text.strip():
            cur, depth = parent.get(n), 0
            while cur is not None and cur.tag == 'node' and depth < 4:
                depth += 1
                if cur.get('clickable') == 'true':
                    title = False
                    break
                if APP_BAR.search(cur.get('resource-id') or ''):
                    title = True
                cur = parent.get(cur)
        if not (click or scroll or title):
            continue
        bb = b(n)
        if bb[0] >= bb[2] or bb[1] >= bb[3] or bb[2] > 1080 or bb[3] > 2400:
            continue
        lab = label(n.get('content-desc') or text)
        if not lab:
            for c in n:
                lab = label(c.get('text') or c.get('content-desc'))
                if lab:
                    break
        if not lab and click:  # grandchild text, as AppTaskObservation.nestedLabel
            for c in n:
                if c.get('clickable') == 'true':
                    continue
                for g in c:
                    if g.get('clickable') != 'true':
                        lab = label(g.get('text') or g.get('content-desc'))
                        if lab:
                            break
                if lab:
                    break
        role = 'toggle' if chk else 'tab' if 'tab' in (n.get('class') or '').lower() else 'list' if scroll else 'button' if click else 'text'
        if not lab and scroll:
            lab = '목록'
        if not lab and not click:
            continue
        out.append(dict(id=f'node_{i}', label=lab, resource=n.get('resource-id') or '', role=role, bounds=b(n),
                        clickable=click, editable=False, scrollable=scroll, selected=n.get('selected') == 'true',
                        checked=(n.get('checked') == 'true') if chk else None, has_value=False, source='accessibility'))
    return out


CASES = [  # (goal, screen, planned, correct choice label, correct kind)
    ('파일 앱에서 최근 파일 화면을 열어줘', 'home', False, '루트 표시', 'tap'),
    ('파일 앱에서 최근 파일 화면을 열어줘', 'drawer', True, '최근', 'tap'),
    ('파일 앱에서 최근 파일 화면을 열어줘', 'recent', True, None, 'title'),
    ('파일 앱에서 문서 분류 화면을 열어줘', 'home', False, {'루트 표시', '문서'}, 'tap'),
    ('파일 앱에서 문서 분류 화면을 열어줘', 'drawer', True, '문서', 'tap'),
    ('파일 앱에서 문서 분류 화면을 열어줘', 'documents', True, None, 'title'),
    # Not arrived: must not claim a title.
    ('파일 앱에서 문서 분류 화면을 열어줘', 'recent', True, None, 'tap|none'),
    ('파일 앱에서 최근 파일 화면을 열어줘', 'documents', True, None, 'tap|none'),
]

def main():
  with open(out, 'w', encoding='utf-8') as f:
        for goal, name, planned, want, kind in CASES:
            nodes = project(os.path.join(cap_dir, name + '.xml'))
            req = AppTaskRequest(request_id=f'r-{name}', goal=goal, planning_mode='goal_first',
                                 apps=[TaskApp(package='com.google.android.documentsui', label='파일')],
                                 state=dict(task_id='t'), screen=dict(status='public', package='com.google.android.documentsui',
                                 observation_id='o', captured_at_ms=int(time.time() * 1000), fingerprint='a' * 64,
                                 width=1080, height=2400, nodes=nodes))
            if planned:
                req.state.subgoals = [TaskSubgoal(id='g0', title=goal)]
                req.state.attempts = [TaskAttempt(fingerprint='b' * 64, tool='tap', target='x', outcome='verified')]
            t = time.monotonic()
            r = AppTaskService().step(req)
            ms = round((time.monotonic() - t) * 1000)
            d = r.diagnostic
            picked = (r.action and next((n['label'] for n in nodes if n['id'] == r.action.target_id), None)) or \
                     (r.completion and r.completion.checks[0].label)
            got = d.get('choice_kind') or 'error'
            ok = (got == kind or got in kind.split('|')) and (want is None or picked in (want if isinstance(want, set) else {want}))
            if kind == 'title':
                ok = ok and r.status == 'confirm_result'
            row = dict(goal=goal, screen=name, status=r.status, kind=got, picked=picked, ok=ok, ms=ms,
                       titles=d.get('choice_titles'), taps=d.get('choice_taps'), reason=d.get('reason_code'),
                       model=d.get('model'), in_tok=d.get('input_tokens'), out_tok=d.get('output_tokens'))
            f.write(json.dumps(row, ensure_ascii=False) + '\n')
            print(json.dumps(row, ensure_ascii=False))


if __name__ == '__main__':
    main()

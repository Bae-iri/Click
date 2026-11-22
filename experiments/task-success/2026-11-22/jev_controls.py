"""Score Jev on fixed public navigation labels visible in a Galaxy accessibility tree."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(ROOT / 'Backend'), str(ROOT / 'AI-Agent/src')]
from dotenv import dotenv_values
from app.services.goal_jev import JevGoalClassifier, _choice

# Each case supplies the first correct control on the app's initial screen.
APPS = [
    ('com.android.settings', ['디스플레이', '연결', '소리 및 진동', '알림', '배경화면 및 스타일', '홈 화면', '테마'],
     [('디스플레이 설정 열어줘', '디스플레이'), ('와이파이 설정 열어줘', '연결')]),
    ('com.sec.android.app.clockpackage', ['알람', '세계시각', '스톱워치', '타이머'],
     [('시계에서 타이머 탭으로 이동해줘', '타이머'), ('시계에서 스톱워치 탭으로 이동해줘', '스톱워치')]),
    ('com.android.chrome', ['Chrome 맞춤설정 및 제어', '탭 전환', '홈', '설정', '새 탭', '더보기 옵션', '탭 전환 또는 닫기'],
     [('Chrome 메뉴를 열어줘', 'Chrome 맞춤설정 및 제어'), ('Chrome 설정 화면을 열어줘', 'Chrome 맞춤설정 및 제어')]),
    ('com.google.android.youtube', ['홈', 'Shorts', '구독', '검색', 'YouTube 검색', '알림', '만들기', '내 페이지'],
     [('YouTube에서 Shorts 탭으로 이동해줘', 'Shorts'), ('YouTube 검색창을 열어줘', '검색')]),
]
ALIASES = {'YouTube 검색': '검색', '더보기 옵션': 'Chrome 맞춤설정 및 제어'}


def candidates(xml, package, labels):
    root = ET.fromstring(xml)
    found = set()

    def visit(node, parent_actionable):
        a = node.attrib
        actionable = a.get('clickable') == 'true' or parent_actionable
        if a.get('package') == package and actionable and a.get('enabled') != 'false':
            for raw in [a.get('text', ''), a.get('content-desc', '')]:
                for label in labels:
                    if raw == label or raw.startswith((label + ',', label + ' 탭')):
                        found.add(ALIASES.get(label, label))
        for child in node:
            visit(child, actionable)
    visit(root, False)
    return sorted(found)


def hierarchy(shell):
    # Some Android builds do not stream a dump to /dev/tty.
    try:
        dump = subprocess.check_output(shell + ['uiautomator', 'dump', '/dev/tty'], timeout=18)
        xml = re.search(rb'<\?xml.*?</hierarchy>', dump, re.S)
        if xml:
            return xml.group()
    except (subprocess.SubprocessError, OSError):
        pass
    path = '/data/local/tmp/click-nav-' + uuid.uuid4().hex + '.xml'
    try:
        subprocess.run(shell + ['uiautomator', 'dump', '--compressed', path],
                       check=True, timeout=18, stdout=subprocess.DEVNULL)
        return subprocess.check_output(shell + ['cat', path], timeout=5)
    except (subprocess.SubprocessError, OSError):
        return b''
    finally:
        subprocess.run(shell + ['rm','-f',path], stdout=subprocess.DEVNULL)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    shell = [args.adb, '-s', args.serial, 'shell']
    config = dotenv_values(ROOT / 'Backend/.env')
    for key in ['TYPESAFE_API_KEY', 'JEV_MODEL', 'JEV_MIN_CONFIDENCE']:
        if config.get(key):
            os.environ[key] = config[key]
    jev = JevGoalClassifier()
    _, model, threshold = jev.configuration()
    rows = []
    for package, labels, tasks in APPS:
        if package == 'com.android.settings':
            launch = ['am', 'start', '-W', '-a', 'android.settings.SETTINGS']
        else:
            launch = ['monkey', '-p', package, '-c', 'android.intent.category.LAUNCHER', '1']
        subprocess.run(shell + launch, stdout=subprocess.DEVNULL, check=True)
        time.sleep(1.5)
        xml = hierarchy(shell)
        options = candidates(xml, package, labels) if xml else []
        for query, expected in tasks:
            row = dict(package=package, goal=query, candidates=options, expected=expected, model=model,
                       threshold=threshold, target_present=expected in options, called=False)
            rows.append(row)
            if len(options) < 2:
                row['result'] = 'insufficient_candidates'
                continue
            criteria = {f'c{i}': label for i, label in enumerate(options)}
            criteria['none'] = '현재 후보에 알맞은 다음 버튼이 없음'
            payload = dict(model=model, state=query, questions={'button':dict(type='choice', criteria=criteria,
                           instructions='Identify the public control corresponding to the requested navigation step, including an already selected tab. Choose none if absent.')})
            row['called'] = True
            start = time.monotonic()
            try:
                response = jev._post(payload, timeout=12)
                row['http'] = response.status_code
                if response.status_code != 200:
                    row.update(result="provider_error", error="http_status", elapsed_ms=round(1000 * (time.monotonic() - start)))
                    continue
                value = response.json()
                if value.get('model') != model:
                    raise ValueError('model mismatch')
                choice = _choice(value['answers']['button'], criteria, threshold)
                selected = criteria.get(choice) if choice != 'none' else None
                row.update(choice=choice, selected=selected)
                if choice is None:
                    row['result'] = 'held'
                elif expected in options:
                    row['result'] = 'correct' if selected == expected else 'incorrect'
                else:
                    row['result'] = 'correct_absence' if choice == 'none' else 'incorrect'
            except Exception as error:
                row.update(result='provider_error', error=type(error).__name__)
            row['elapsed_ms'] = round(1000 * (time.monotonic() - start))
        args.output.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n')
    subprocess.run(shell + ['input', 'keyevent', 'KEYCODE_HOME'], check=True)

if __name__ == '__main__':
    main()

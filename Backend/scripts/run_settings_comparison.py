"""Paired Settings replay through the real runtime. No phone is changed.

controlled: fixture answers for both providers (integration validation).
live: real Jev and Gemini, synthetic screens/actions (provider replay).
Neither mode is evidence of success on a physical device.
"""
import argparse
from copy import deepcopy
from dataclasses import replace
from hashlib import sha256
import json
import os
from pathlib import Path
import sys
from time import monotonic, time
from uuid import uuid4

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent/src'))


def node(identifier, text, *, slider=False, resource=None):
    from ai_agent.runtime.types import AccessibilityNode
    return AccessibilityNode(identifier, package_name='com.android.settings', window_id=7, text=text,
        class_name='android.widget.SeekBar' if slider else 'android.widget.TextView',
        clickable=not slider and identifier != 'title', resource_id=resource,
        bounds_in_screen=(20, 300, 900, 370) if slider else
            (20, 20, 900, 90) if identifier == 'title' else (20, 130, 900, 220),
        extras={'progress': 3, 'min': 0, 'max': 7} if slider else {})


class Replay:
    def __init__(self, goal_type, arm, providers):
        from ai_agent.runtime import ClickAgentEngine
        from ai_agent.runtime.types import TypedGoal
        from app.services.screen_jev import JevScreenResolver
        from app.services.settings_experiment import SettingsExperimentResolver
        from app.services.settings_planner import GeminiSettingsPlanner
        from app.task_graphs import approved_graphs
        self.graph = approved_graphs()[goal_type]
        self.arm, self.providers = arm, providers
        self.session = uuid4().hex
        self.at, self.records, self.edge = 0, [], None
        self.goal = TypedGoal(goal_type=goal_type, target_app=self.graph.definition.target_app,
            task_type=self.graph.definition.task_type, slots=self.graph.definition.required_slots,
            source_text='', confidence=1, task_id=self.graph.definition.task_id,
            is_supported=True, required_slots_filled=True)
        inner = JevScreenResolver(self.controlled_jev if providers == 'controlled' else None)
        planner = GeminiSettingsPlanner(self.controlled_gemini if providers == 'controlled' else None)
        self.resolver = SettingsExperimentResolver(inner, planner)
        self.resolver.prepare(None, arm, 'replay')
        self.resolver.state['scope_hash'] = sha256(b'settings-synthetic-replay.v1').hexdigest()
        self.engine = ClickAgentEngine(task_graphs={self.goal.task_id: self.graph}, screen_resolver=self.resolver)
        self.applied = {'source': 'android.provider.Settings.System.FONT_SCALE', 'font_scale': 1.1,
            'brightness_source': 'android.provider.Settings.System.SCREEN_BRIGHTNESS', 'screen_brightness': 100,
            'media_volume_source': 'android.media.AudioManager.STREAM_MUSIC', 'media_volume': 4}

    def controlled_gemini(self, payload, **kwargs):
        """An explicitly labelled oracle for integration checks, never live evidence."""
        import httpx
        from app.services.settings_planner import catalogue
        steps = [t['id'] for t in catalogue(self.graph)[0]]
        return httpx.Response(200, json={'candidates': [{'finishReason': 'STOP',
            'content': {'parts': [{'text': json.dumps({'subgoals': steps})}]}}]})

    def controlled_jev(self, payload, **kwargs):
        import httpx
        criteria = payload['questions']['screen']['criteria']
        choice = next((k for k, v in criteria.items() if isinstance(v, dict)
            and v['transition'] == self.edge.transition_id), 'insufficient_evidence')
        def answer(selected, values):
            return dict(type='choice', choice=selected, confidence=1,
                probabilities={k: float(k == selected) for k in values})
        answers = {'screen': answer(choice, criteria)}
        if 'page' in payload['questions']:
            pages = payload['questions']['page']['criteria']
            page = next(k for k, v in pages.items() if isinstance(v, dict)
                and v['state_id'] == self.edge.from_state)
            answers.update(page=answer(page, pages),
                target_supported={'type': 'noul', 'noul': float(isinstance(criteria.get(choice), dict))})
        return httpx.Response(200, json={'model': payload['model'], 'answers': answers})

    def observe(self, nodes, *, event=None, confirmation=None, applied=None):
        from ai_agent.runtime.types import AgentTurnRequest, DeviceContext
        from app.services.settings_experiment import trace
        before = deepcopy(self.engine.session_store.get(self.session))
        self.at += 1000
        request = AgentTurnRequest(request_id=f'replay-{self.at}', session_id=self.session,
            episode_id='replay', observation_id=f'o{self.at}', captured_at_ms=self.at,
            raw_goal=None, typed_goal=self.goal if before is None else None,
            device_context=DeviceContext('android'), screen_bounds=(0, 0, 1080, 2400), active_window_id=7,
            accessibility_tree=nodes, applied_settings=applied or self.applied,
            user_action_event=event, user_confirmation=confirmation)
        self.resolver.turn = {'planner': {'called': False}, 'jev': {'called': False}}
        started = monotonic()
        result = self.engine.handle_turn(request)
        after = self.engine.session_store.get(self.session)
        record = trace(self.resolver, request, result, before, after, self.graph,
            round((monotonic() - started) * 1000))
        self.engine.session_store.update(self.session, settings_experiment=deepcopy(self.resolver.state))
        self.records.append(dict(at=time(), **record))
        return result

    def expect(self, result, action):
        if result.guide_command.action_type.value != action:
            raise ReplayStopped('expected_' + action)

    def run(self):
        from ai_agent.runtime.types import UserActionEvent, UserActionType
        setting, direction = self.graph.definition.required_slots['setting'], self.graph.definition.required_slots['direction']
        self.edge = next(t for t in self.graph.transitions if t.from_state == 'S1_SETTINGS_HOME')
        result = self.observe((node('title', '설정'), node('menu', self.edge.target_keywords[0])))
        self.expect(result, 'tap')
        # Simulated user actions are bound to the guide, exactly as in the device contract.
        def tap(previous):
            return UserActionEvent(UserActionType.TAP_COMPLETED, previous.guide_command.guide_command_id,
                previous.guide_command.target_node_id, occurred_at_ms=self.at + 500,
                event_id=f'tap-{self.at}', episode_id='replay', session_id=self.session)
        if setting in {'font_size', 'volume'}:
            event = tap(result)
            page = self.edge.to_state
            self.edge = next(t for t in self.graph.transitions if t.from_state == page)
            state = self.graph.states[page]
            title = state.required_keywords[0] if state.required_keywords else state.required_keyword_groups[0][0]
            result = self.observe((node('title', title), node('submenu', self.edge.target_keywords[0])), event=event)
            self.expect(result, 'tap')
        event = tap(result)
        self.edge = next(t for t in self.graph.transitions if t.target_role == 'slider')
        label = {'font_size': '글자 크기', 'brightness': '밝기', 'volume': '미디어'}[setting]
        control = (node('title', label), node('target', label, slider=True,
            resource='com.android.settings:id/seekBarForFontSize' if setting == 'font_size' else None))
        result = self.observe(control, event=event)
        if result.guide_command.command_type.value != 'confirmation':
            raise ReplayStopped('expected_confirmation')
        result = self.observe(control, confirmation='accepted')
        self.expect(result, 'drag')
        sign = 1 if direction == 'increase' else -1
        changed = (control[0], replace(control[1], extras={'progress': 3 + sign, 'min': 0, 'max': 7}))
        key = {'font_size': 'font_scale', 'brightness': 'screen_brightness', 'volume': 'media_volume'}[setting]
        event = UserActionEvent(UserActionType.DRAG_COMPLETED, result.guide_command.guide_command_id,
            'target', occurred_at_ms=self.at + 500, event_id='drag', episode_id='replay', session_id=self.session)
        self.observe(changed, event=event,
            applied={**self.applied, key: self.applied[key] + sign * (.2 if setting == 'font_size' else 1)})


class ReplayStopped(Exception):
    pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--providers', choices=('controlled', 'live'), required=True)
    parser.add_argument('--all-goals', action='store_true')
    parser.add_argument('--repeat', type=int, default=1)
    parser.add_argument('--env-file', type=Path, default=BACKEND / 'instance/settings-experiment/.env')
    parser.add_argument('--model', help='Explicit Gemini model for this live replay only')
    parser.add_argument('--output-dir', type=Path)
    args = parser.parse_args()
    if not 1 <= args.repeat <= 10:parser.error('--repeat must be between 1 and 10')
    if args.model and args.providers != 'live':parser.error('--model requires --providers live')
    from dotenv import load_dotenv
    if args.providers == 'live' and not args.env_file.is_file():parser.error('Prepare experiment environment first')
    if args.providers == 'live':load_dotenv(args.env_file, override=True)
    else:
        os.environ['TYPESAFE_API_KEY'] = 'controlled-not-a-real-key'
        os.environ['JEV_MODEL'] = 'jev-1.14.0'
    if args.model:os.environ['GEMINI_SETTINGS_MODEL'] = args.model
    os.environ['AI1_UI_SELECTOR_PROVIDER'] = 'jev'
    from app.services.settings_comparison import compare, trial_summaries, write_report
    from app.services.settings_planner import GOALS, model_name
    goals = sorted(GOALS) if args.all_goals else ['settings.font_size.increase']
    records, outcomes = [], []
    for repeat in range(args.repeat):
        for goal in goals:
            for arm in (('graph', 'llm') if repeat % 2 == 0 else ('llm', 'graph')):
                replay = Replay(goal, arm, args.providers)
                try:
                    replay.run()
                except ReplayStopped as error:
                    reason = str(error)
                else:reason = None
                records.extend(replay.records)
                trial = trial_summaries(replay.records)[0]
                outcome = dict(arm=arm, goal_type=goal, repeat=repeat + 1,
                    simulated_success=trial['native_success'], provider_unavailable=trial['provider_unavailable'],
                    stop_reason=reason, model_wait_ms=trial['model_wait_ms'])
                outcomes.append(outcome)
                print(json.dumps(outcome), flush=True)
    report = compare(records)
    report.update(evidence_mode=args.providers + '_synthetic_replay', device_validation='NOT_RUN',
        planner_model=model_name(), outcomes=outcomes)
    output = args.output_dir or BACKEND / 'instance/settings-experiment' / (args.providers + '-replay-' + uuid4().hex[:8])
    write_report(report, output)
    with (output / 'traces.jsonl').open('w', encoding='utf-8') as stream:
        for record in records:stream.write(json.dumps(record, ensure_ascii=False) + '\n')
    print(json.dumps(dict(report=str(output / 'comparison.md'),
        evidence_mode=report['evidence_mode'], device_validation='NOT_RUN')), flush=True)
    return 0 if all(t['simulated_success'] for t in outcomes) else 1


if __name__ == '__main__':raise SystemExit(main())

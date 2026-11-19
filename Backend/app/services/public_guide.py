"""Semantic public login guide; authentication is always a user-controlled handoff."""
from copy import deepcopy
from hashlib import sha256
import json
from uuid import uuid4
from app.services.browse import BrowseService
from app.services import public_guide_policy as policy
from app.services.public_guide_jev import JevPublicGuideSelector
from app.schemas.public_guide import PublicGuideGoal, PublicGuideResponse
from app.schemas.goal_preparation import InputQuestion, InputChoice

def digest(value):
    return sha256(json.dumps(value,sort_keys=True,ensure_ascii=False).encode()).hexdigest()

class PublicGuideService(BrowseService):
    def __init__(self, factory, sessions, selector=None):
        super().__init__(factory,sessions,selector=selector or JevPublicGuideSelector())
        self.policy=policy
        self.goal_model,self.response_model=PublicGuideGoal,PublicGuideResponse
        self.execution_mode,self.target_app='public_guide','gov24'
        self.goal_field='public_guide_goal'
        self.task_id,self.event_kind='GOV24-LOGIN','public_guide'
        from app.services.gov24_planner import GeminiGovernmentPlanner
        from app.services.gov24_jev import JevGovernmentSelector
        self.government_planner = GeminiGovernmentPlanner()
        self.government_selector = JevGovernmentSelector()

    def snapshot_details(self, state):
        waiting = state.get('waiting_for')
        if waiting in {'await_login', 'await_public'}:
            return dict(subgoal=waiting, message=self.wait_message(waiting), reason_code='authentication_required' if waiting == 'await_login' else state.get('waiting_reason', 'private_input'))
        return {}

    @staticmethod
    def wait_message(waiting):
        return ('정부24에서 직접 로그인한 뒤 홈으로 돌아와 주세요. 원래 요청을 기억하고 이어갈게요.'
                if waiting == 'await_login' else '입력이나 화면 로딩을 직접 확인해 주세요. 정부24의 공개 화면으로 돌아오면 원래 안내를 이어갈게요.')

    def wait(self, state, result, evidence, waiting, reason):
        state['waiting_for'] = waiting
        code = 'authentication_required' if waiting == 'await_login' else 'screen_unrecognized' if reason == 'screen_unrecognized' else 'private_input'
        state['waiting_reason'] = code
        state.pop('pending', None)
        state.pop('question', None)
        state.pop('authentication', None)
        result.subgoal, result.message = waiting, self.wait_message(waiting)
        result.reason_code = code
        evidence.update(page=waiting, reason=reason)

    @staticmethod
    def navigation_block(result, evidence):
        result.status, result.outcome, result.reason_code = 'BLOCKED', 'blocked', 'navigation_limit'
        result.message = '메뉴 안내가 여러 차례 반복되어 멈췄어요. 정부24에서 직접 확인한 뒤 새 안내를 시작해 주세요.'
        evidence['reason'] = 'navigation_limit'

    def observe_login(self, request, screen, state, result, evidence, *, civil=False):
        if policy.navigation_limit(state, request, screen, civil=civil):
            self.navigation_block(result, evidence)
            return
        state.pop('waiting_for', None)
        authentication = state.setdefault('authentication', {}) if civil else state
        self.observe_public(request, screen, authentication, result, evidence)

    def authentication(self, request, screen, state, result, evidence, *, civil=False):
        public = screen is not None and screen.status == 'public'
        if public and screen.auth_state == 'signed_in':
            state.pop('auth_required', None)
            state.pop('waiting_for', None)
            state.pop('authentication', None)
            if civil:
                return False
            result.status, result.outcome = 'COMPLETED', 'verified_navigation'
            result.message = '정부24 홈의 로그아웃 표시를 확인했어요. 로그인되어 있어요.'
            evidence.update(page='signed_in_home', reason='signed_in_home')
            return True
        login_choices = public and any(n.option_id in policy.OPTIONS for n in screen.nodes)
        if civil and public and screen.auth_state == 'signed_out':
            state['auth_required'] = True
        if civil and login_choices:
            state['auth_required'] = True
            state.pop('pending', None)
            self.observe_login(request, screen, state, result, evidence, civil=True)
            if screen.auth_state == 'signed_out' and result.status == 'IN_PROGRESS':
                result.message = '먼저 정부24에 로그인해 주세요. 로그인 후 원래 민원 요청을 이어갈게요.'
            return True
        private = (screen is not None and screen.status == 'handoff') or (request.screen is not None and request.screen.status == 'sensitive')
        if private:
            reason = screen.handoff_reason if screen is not None else 'private_screen'
            if request.recovery_version is None:
                result.status, result.outcome, result.subgoal = 'COMPLETED', 'handoff', 'handoff'
                result.message = '여기부터는 정부24에서 직접 인증하거나 입력해 주세요. 화면 전송과 안내를 멈췄어요.'
                evidence.update(page='handoff', reason=reason)
                state.pop('question', None)
                return True
            requires_login = not civil or state.get('auth_required') or reason in {'certificate_selection', 'external_auth'}
            self.wait(state, result, evidence, 'await_login' if requires_login else 'await_public', reason)
            return True
        if not public and state.get('waiting_for') in {'await_login', 'await_public'}:
            self.wait(state, result, evidence, state['waiting_for'], 'awaiting_public_observation')
            return True
        if civil and state.get('auth_required'):
            self.wait(state, result, evidence, 'await_login', 'awaiting_signed_in_home')
            return True
        if public:
            state.pop('waiting_for', None)
        return False

    def decide(self,request,state,now):
        if state.get('experiment') or state['goal'].get('planner'):
            # Opt-in Gemini planner pilot; deterministic baseline graphs stay the default.
            from app.services.gov24_experiment import decide
            return decide(self, request, state, now)
        if state['goal']['goal_type'] in policy.CIVIL_GRAPHS:
            from app.services.gov24_navigation import decide
            return decide(self, request, state, now)
        state=deepcopy(state); step=state.get('steps',0)+1
        result=PublicGuideResponse(request_id=request.request_id,session_id=request.session_id,
            session_version=request.expected_version+1,status='IN_PROGRESS',outcome='in_progress',step=step,
            message='정부24를 열고 로그인 화면에서 현재 화면 확인을 눌러주세요.')
        evidence={'schema_version':'public-guide-event.v1','policy_version':policy.VERSION,
            'page':'unknown','step':step,'outcome':'in_progress','reason':'entry_required'}
        if request.command in {'cancel','finish'}:
            result.status='CANCELLED' if request.command=='cancel' else 'COMPLETED'
            result.outcome='cancelled' if request.command=='cancel' else 'user_reported'
            result.message='정부24 로그인 안내를 마쳤어요. 실제 로그인 완료 여부는 사용자가 확인해 주세요.'
        else:
            screen=policy.sanitize(request.screen,now*1000)
            if self.authentication(request,screen,state,result,evidence):
                pass
            elif screen and screen.status=='public':
                self.observe_login(request,screen,state,result,evidence)
            elif request.screen and request.screen.package_name==policy.POLICY['package'] and state.get('selected_option') and request.screen.status=='unrecognized':
                if request.recovery_version is not None:
                    self.wait(state,result,evidence,'await_public','screen_unrecognized')
                else:
                    result.status,result.outcome,result.subgoal='COMPLETED','handoff','handoff'
                    result.message='선택한 인증 화면은 정부24에서 직접 확인해 주세요.'
                    evidence.update(page='handoff',reason='authentication_step')
            elif request.answer or request.choice:
                result.message='선택 화면을 다시 확인해야 해요. 정부24로 돌아가 현재 화면 확인을 눌러주세요.'
        state.update(steps=step,outcome=result.outcome)
        evidence['outcome']=result.outcome
        evidence['navigation_turns']=state.get('navigation_turns',0)
        from app.services.gov24_diagnostics import attach
        attach(result,evidence,state,request,now*1000)
        return result,evidence,state

    def observe_public(self,request,screen,state,result,evidence):
        screen_hash=policy.question_fingerprint(screen)
        # Provider dialogs can overlay the old method page. Only the foreground
        # provider choices are eligible in that case.
        group='provider' if any(policy.OPTIONS.get(n.option_id,{}).get('group')=='provider' for n in screen.nodes) else 'method'
        candidates={}
        for n in screen.nodes:
            if policy.OPTIONS.get(n.option_id,{}).get('group')==group:
                candidates.setdefault(n.option_id,[]).append(n)
        candidates={k:v[0] for k,v in candidates.items() if len(v)==1}
        result.subgoal='choose_provider' if group=='provider' else 'choose_method'
        evidence.update(page=result.subgoal,screen_hash=screen_hash,candidate_count=len(candidates))
        if not candidates:
            entries=[n for n in screen.nodes if n.option_id=='entry_login']
            if len(entries)==1:
                self.target(result,screen,entries[0]);result.subgoal='open_login'
                result.message='정부24의 표시한 로그인 버튼을 눌러주세요. 다음 화면에서 로그인 방법을 함께 고를게요.'
            else:result.message='로그인 선택 항목을 확인하지 못했어요. 로그인 화면으로 이동하거나 목록을 스크롤한 뒤 현재 화면 확인을 눌러주세요.'
            return
        preferred=state.pop('public_preference',None) if group=='provider' else None
        if preferred and request.answer is None and request.choice is None:
            selected=self.selector.choose(preferred,list(candidates),result.subgoal)
            if selected in candidates:
                self.target(result,screen,candidates[selected])
                result.message=f"앞서 말씀하신 {policy.OPTIONS[selected]['label']}이 보여요. 표시한 항목을 눌러주세요. 실제 인증은 직접 진행해요."
                evidence.update(reason='preference_resolved',selected_option=selected)
                state['selected_option']=selected;state.pop('question',None)
                return
        old=state.get('question') or {}
        if request.answer is not None or request.choice is not None:
            if old.get('id')!=request.question_id or old.get('screen_hash')!=screen_hash:
                evidence['reason']='stale_question'
                prefix='화면이 바뀌었어요. 지금 보이는 선택지에서 다시 골라주세요. '
            else:
                selected=request.choice if request.choice in candidates else (
                    self.selector.choose(request.answer,list(candidates),result.subgoal) if request.answer else None)
                if selected in candidates:
                    self.target(result,screen,candidates[selected])
                    result.message=f"{policy.OPTIONS[selected]['label']}을 선택했어요. 표시한 항목을 눌러주세요. 개인정보나 인증 입력이 나오면 직접 진행해요."
                    evidence.update(reason='choice_resolved',selected_option=selected)
                    state.pop('question',None)
                    state['selected_option']=selected
                    state.pop('public_preference',None)
                    if selected=='method_simple' and request.answer:
                        preference=policy.preference_text(request.answer)
                        if preference and any(policy.normalize(alias) in policy.normalize(preference)
                                for spec in policy.OPTIONS.values() if spec['group']=='provider' for alias in spec['aliases']):
                            state['public_preference']=preference
                    return
                prefix='어떤 선택인지 확실하지 않아요. 사용하실 항목을 하나만 말씀하거나 버튼으로 골라주세요. '
                evidence['reason']='clarification_required'
        else:prefix=''
        question_id='login-'+uuid4().hex
        message=prefix+('어느 인증기관을 사용하실까요?' if group=='provider' else '어떤 방법으로 로그인할까요? 카카오·네이버·PASS 인증서는 간편인증에서 고를 수 있어요.')+' 버튼을 누르거나 말·글로 답해주세요. 없거나 모르겠으면 직접 확인해도 괜찮아요.'
        result.message=message
        result.question=InputQuestion(id=question_id,field='login_choice',label='로그인 방법' if group=='method' else '인증기관',
            kind='choice',message=message,choices=[InputChoice(value=k,label=policy.OPTIONS[k]['label']) for k in candidates],max_length=160)
        state['question']={'id':question_id,'screen_hash':screen_hash}

    @staticmethod
    def target(result,screen,node):
        result.guide_id=uuid4().hex;result.observation_id=screen.observation_id
        result.target_node_id=node.node_id;result.target_bounds=node.bounds;result.action_kind='login_navigation'

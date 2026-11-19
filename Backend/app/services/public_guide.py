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

    def decide(self,request,state,now):
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
        elif step>policy.POLICY['max_turns']:
            result.status,result.outcome,result.message='BLOCKED','blocked','진행을 확인하지 못했어요. 정부24에서 직접 로그인하거나 새로 시작해 주세요.'
        else:
            screen=policy.sanitize(request.screen,now*1000)
            if screen and screen.status=='handoff':
                result.status,result.outcome,result.subgoal='COMPLETED','handoff','handoff'
                result.message=('개인정보 이용 동의부터는 직접 확인해 주세요. ' if screen.handoff_reason=='consent_required' else '여기부터는 직접 인증해 주세요. ')+'개인정보·인증서·비밀번호·인증번호는 클릭에 입력하지 마세요. AI 화면 전송과 안내를 멈췄어요. 실제 로그인은 정부24에서 마쳐 주세요.'
                evidence.update(page='handoff',reason=screen.handoff_reason)
                state.pop('question',None)
            elif screen and screen.status=='public':
                self.observe_public(request,screen,state,result,evidence)
            elif request.screen and request.screen.package_name==policy.POLICY['package'] and state.get('selected_option') and request.screen.status=='unrecognized':
                result.status,result.outcome,result.subgoal='COMPLETED','handoff','handoff'
                result.message='선택한 로그인 수단의 다음 화면은 직접 확인해 주세요. 인증 화면의 개인정보를 읽지 않도록 안내를 멈췄어요.'
                evidence.update(page='handoff',reason='authentication_step')
                state.pop('question',None)
            elif request.screen and request.screen.status=='sensitive':
                result.status,result.outcome,result.subgoal='COMPLETED','handoff','handoff'
                result.message='인증이나 개인정보 화면이에요. 화면 전송을 멈췄어요. 정부24에서 직접 진행해 주세요.'
                evidence.update(page='handoff',reason='private_screen')
            elif request.answer or request.choice:
                result.message='선택 화면을 다시 확인해야 해요. 정부24로 돌아가 현재 화면 확인을 눌러주세요.'
        state.update(steps=step,outcome=result.outcome)
        evidence['outcome']=result.outcome
        from app.services.gov24_diagnostics import attach
        attach(result,evidence,state,request,now*1000)
        return result,evidence,state

    def observe_public(self,request,screen,state,result,evidence):
        material=screen.model_dump(exclude={'observation_id','captured_at_ms'})
        screen_hash=digest(material)
        # Provider dialogs can overlay the old method page. Only the foreground
        # provider choices are eligible in that case.
        group='provider' if any(policy.OPTIONS[n.option_id]['group']=='provider' for n in screen.nodes) else 'method'
        candidates={}
        for n in screen.nodes:
            if policy.OPTIONS[n.option_id]['group']==group:
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

import json
from types import SimpleNamespace
import httpx
from scripts.generate_tutorial_narration import main
from app.services.goal_llm import LocalGoalLlm


def test_offline_generation_creates_unapproved_draft_without_changing_source(tmp_path,monkeypatch):
    source=tmp_path/'reviewed.json';output=tmp_path/'draft.json'
    original={'version':1,'scripts':[{'source':'아래 클릭 버튼을 눌러보세요.','script':'기존 대본','approved':True}]}
    source.write_text(json.dumps(original),encoding='utf-8')
    def send(payload,**kwargs):
        assert payload['messages'][1]['content']=='아래 클릭 버튼을 눌러보세요.'
        return httpx.Response(200,request=httpx.Request('POST','https://private.example'),
            json={'choices':[{'finish_reason':'stop','message':{'content':json.dumps({'script':'화면 아래의 클릭 버튼을 눌러보세요.'})}}]})
    monkeypatch.setattr(LocalGoalLlm,'configuration',staticmethod(lambda:(SimpleNamespace(post=send),'test')))
    assert main(['--source',str(source),'--output',str(output)])==0
    entry=json.loads(output.read_text())['scripts'][0]
    assert entry['approved'] is False
    assert entry['reviewed_by']==''
    assert entry['source']==original['scripts'][0]['source']
    assert json.loads(source.read_text())==original


def test_generation_error_does_not_publish_partial_asset(tmp_path,monkeypatch):
    source=tmp_path/'source.json';output=tmp_path/'draft.json'
    source.write_text(json.dumps({'scripts':[{'source':'시작해요.'}]}))
    def fail(*a,**kw): raise TimeoutError()
    monkeypatch.setattr(LocalGoalLlm,'configuration',staticmethod(lambda:(SimpleNamespace(post=fail),'test')))
    assert main(['--source',str(source),'--output',str(output)])==1
    assert not output.exists()

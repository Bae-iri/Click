from uuid import uuid4
from sqlalchemy import select
from fastapi.testclient import TestClient
from app.main import create_app
from app.models.voice_queue import VoiceRequest
from tests.helpers import register
from tests.test_voice_queue import enqueue,claim,done,DoneAgent,BASE


def test_compound_requests_are_atomic_fifo_and_duplicate_retry_does_not_duplicate_parts():
    app=create_app();app.state.turn_service.engine_factory=DoneAgent
    with TestClient(app) as client:
        register(client);identifier=str(uuid4())
        a=enqueue(client,'와이파이 켜고 밝기 낮춰줘',request_id=identifier);assert a.status_code==200,a.text
        assert [r['raw_goal'] for r in a.json()['queued']]==['와이파이 켜줘','밝기 낮춰줘']
        replay=enqueue(client,'와이파이 켜고 밝기 낮춰줘',request_id=identifier)
        assert [r['id'] for r in replay.json()['queued']]==[r['id'] for r in a.json()['queued']]
        enqueue(client,'블루투스 꺼줘')
        first=claim(client).json()['running'];assert first['raw_goal']=='와이파이 켜줘'
        done(client,first)
        second=client.post(BASE+'/'+first['id']+'/complete').json()['running'];assert second['raw_goal']=='밝기 낮춰줘'
        assert second['agent_session_id']!=first['agent_session_id']
        done(client,second)
        third=client.post(BASE+'/'+second['id']+'/complete').json()['running'];assert third['raw_goal']=='블루투스 꺼줘'
        with app.state.session_factory() as db:
            rows=list(db.scalars(select(VoiceRequest).where(VoiceRequest.priority=='NORMAL').order_by(VoiceRequest.sequence)))
            assert len(rows)==3 and [r.sequence for r in rows]==[1,2,3]
            assert rows[0].raw_goal is None and rows[1].raw_goal is None


def test_partial_batch_never_fills_beyond_capacity_or_leaves_first_half():
    with TestClient(create_app()) as client:
        register(client)
        for _ in range(99):assert enqueue(client,'와이파이 켜줘').status_code==200
        response=enqueue(client,'와이파이 켜고 밝기 낮춰줘');assert response.status_code==429
        snapshot=client.get(BASE).json();assert len(snapshot['queued'])==99


def test_unsupported_explicit_second_request_is_preserved_for_its_own_preparation():
    with TestClient(create_app()) as client:
        register(client)
        response=enqueue(client,'와이파이 켜줘 그리고 친구에게 메시지 보내줘').json()
        assert [r['raw_goal'] for r in response['queued']]==['와이파이 켜줘','친구에게 메시지 보내줘']


def test_concurrent_batches_keep_each_utterances_parts_together(tmp_path):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier
    url='sqlite+pysqlite:///'+(tmp_path/'batches.db').as_posix()
    app=create_app(url)
    with TestClient(app) as one,TestClient(create_app(url)) as two:
        auth=register(one);two.headers['Authorization']='Bearer '+auth['access_token'];barrier=Barrier(2)
        batches=['와이파이 켜고 밝기 낮춰줘','블루투스 켜고 글자 크기 키워줘']
        def submit(args):
            client,text=args;barrier.wait(timeout=5);return enqueue(client,text)
        with ThreadPoolExecutor(max_workers=2) as pool:
            responses=list(pool.map(submit,zip((one,two),batches)))
        assert all(r.status_code==200 for r in responses)
        with app.state.session_factory() as db:
            rows=list(db.scalars(select(VoiceRequest).order_by(VoiceRequest.sequence)))
            texts=[r.raw_goal for r in rows]
            expected=[['와이파이 켜줘','밝기 낮춰줘'],['블루투스 켜줘','글자 크기 키워줘']]
            assert texts in [expected[0]+expected[1],expected[1]+expected[0]]

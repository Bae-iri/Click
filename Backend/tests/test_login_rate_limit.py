import pytest
from fastapi import HTTPException
from sqlalchemy import select
from app.main import create_app
from app.models.auth import LoginRateLimit
from app.services.auth import AuthService, LOGIN_ATTEMPTS_PER_WINDOW


def test_login_endpoint_enforces_account_limit_before_the_ip_limit():
    from fastapi.testclient import TestClient
    with TestClient(create_app()) as client:
        body={'phone_number':'01012345678','password':'9999'}
        for _ in range(LOGIN_ATTEMPTS_PER_WINDOW):
            assert client.post('/api/v1/auth/login',json=body).status_code==401
        response=client.post('/api/v1/auth/login',json=body)
        assert response.status_code==429
        assert 1 <= int(response.headers['Retry-After']) <= 300


def test_account_limit_is_shared_by_workers_and_does_not_store_phone_numbers():
    app=create_app()
    first=app.state.auth_service
    second=AuthService(app.state.session_factory)
    for index in range(LOGIN_ATTEMPTS_PER_WINDOW):
        (first if index % 2 else second).throttle_account('01012345678')
    with pytest.raises(HTTPException) as error:
        second.throttle_account('01012345678')
    assert error.value.status_code==429
    assert 1 <= int(error.value.headers['Retry-After']) <= 300
    first.throttle_account('01087654321')
    with app.state.session_factory() as db:
        rows=list(db.scalars(select(LoginRateLimit)))
        assert len(rows)==2
        assert all(len(row.account_key)==64 and '01012345678' not in row.account_key for row in rows)


def test_account_limit_resets_in_the_next_window(monkeypatch):
    app=create_app()
    now=[1800000000]
    monkeypatch.setattr('app.services.auth.time.time',lambda:now[0])
    for _ in range(LOGIN_ATTEMPTS_PER_WINDOW):app.state.auth_service.throttle_account('01012345678')
    now[0]+=300
    app.state.auth_service.throttle_account('01012345678')
    with app.state.session_factory() as db:
        rows=list(db.scalars(select(LoginRateLimit)))
        assert len(rows)==1 and rows[0].attempts==1


def test_parallel_attempts_and_service_restart_cannot_reset_the_limit(tmp_path):
    from concurrent.futures import ThreadPoolExecutor
    url='sqlite+pysqlite:///'+(tmp_path/'login-limit.db').as_posix()
    app=create_app(url)
    def attempt(_):
        try:
            app.state.auth_service.throttle_account('01012345678')
            return True
        except HTTPException as error:
            assert error.status_code==429
            return False
    with ThreadPoolExecutor(max_workers=6) as pool:
        assert sum(pool.map(attempt,range(20)))==LOGIN_ATTEMPTS_PER_WINDOW
    app.state.engine.dispose()
    restarted=create_app(url)
    try:
        with pytest.raises(HTTPException) as error:
            restarted.state.auth_service.throttle_account('01012345678')
        assert error.value.status_code==429
    finally:restarted.state.engine.dispose()

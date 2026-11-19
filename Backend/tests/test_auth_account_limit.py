from concurrent.futures import ThreadPoolExecutor
import time

import pytest
from fastapi import HTTPException
from sqlalchemy import select
from app.main import create_app
from app.models.auth import AuthLoginWindow
from app.schemas.auth import Credentials
from app.services.auth import AuthService, LOGIN_ATTEMPTS, LOGIN_WINDOW_SECONDS


def test_account_limit_survives_service_restart_and_expires(tmp_path, monkeypatch):
    app = create_app(f'sqlite+pysqlite:///{tmp_path}/accounts.db')
    factory = app.state.session_factory
    credentials = Credentials(phone_number='01012345678', password='9999')
    service = AuthService(factory)
    for _ in range(LOGIN_ATTEMPTS):
        with pytest.raises(HTTPException) as error:
            service.login(credentials)
        assert error.value.status_code == 401
    # A second process/service and a different IP cannot obtain another account budget.
    with pytest.raises(HTTPException) as error:
        AuthService(factory).login(credentials)
    assert error.value.status_code == 429
    assert 0 < int(error.value.headers['Retry-After']) <= LOGIN_WINDOW_SECONDS
    with factory() as db:
        row = db.scalar(select(AuthLoginWindow))
        assert len(row.identity_hash) == 64 and credentials.phone_number not in row.identity_hash
    now = int(time.time())
    monkeypatch.setattr('app.services.auth.time.time', lambda: now + LOGIN_WINDOW_SECONDS + 1)
    with pytest.raises(HTTPException) as error:
        AuthService(factory).login(credentials)
    assert error.value.status_code == 401


def test_parallel_workers_reserve_a_single_shared_account_budget(tmp_path):
    app = create_app(f'sqlite+pysqlite:///{tmp_path}/concurrent.db')
    services = [AuthService(app.state.session_factory) for _ in range(2)]
    def attempt(index):
        try:
            services[index % 2].reserve_login_attempt('01012345678')
            return 200
        except HTTPException as error:
            return error.status_code
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(attempt, range(12)))
    assert results.count(200) == LOGIN_ATTEMPTS
    assert results.count(429) == 12 - LOGIN_ATTEMPTS

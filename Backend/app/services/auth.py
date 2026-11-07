import time
from dataclasses import dataclass
from threading import Lock

from fastapi import HTTPException
from sqlalchemy import delete, select, update
from sqlalchemy.exc import IntegrityError

from app.core.security import hash_password, new_token, token_hash, verify_password
from app.models.auth import Account, AuthSession, KakaoIdentity
from app.clients.kakao import KakaoTokenVerifier
from app.models.user import User
from app.core.data_encryption import cipher_for
from app.schemas.auth import TokenResponse

ACCESS_TTL = 900
REFRESH_TTL = 7 * 24 * 60 * 60


@dataclass(frozen=True)
class Principal:
    user_id: str
    session_id: str


class AuthService:
    def __init__(self, factory):
        self.factory = factory
        self._dummy = hash_password(new_token())
        self._attempts = {}
        self._lock = Lock()
        self.kakao = KakaoTokenVerifier()

    def throttle(self, identity):
        # Single-process development limiter. No forwarded-header trust.
        now = time.monotonic()
        key = token_hash(identity)
        with self._lock:
            self._attempts = {k: v for k, v in self._attempts.items() if v[0] > now}
            expiry, count = self._attempts.get(key, (now + 60, 0))
            if count >= 20 or (key not in self._attempts and len(self._attempts) >= 10000):
                raise HTTPException(429, headers={"Retry-After": "60"})
            self._attempts[key] = (expiry, count + 1)

    @staticmethod
    def tokens(user_id):
        access, refresh = new_token(), new_token()
        return TokenResponse(user_id=user_id, access_token=access, refresh_token=refresh,
                             expires_in=ACCESS_TTL, refresh_expires_in=REFRESH_TTL)

    @staticmethod
    def add_session(db, tokens):
        now = int(time.time())
        db.execute(delete(AuthSession).where(AuthSession.refresh_expires_at <= now))
        db.add(AuthSession(user_id=tokens.user_id,
                           access_hash=token_hash(tokens.access_token), refresh_hash=token_hash(tokens.refresh_token),
                           access_expires_at=now + ACCESS_TTL, refresh_expires_at=now + REFRESH_TTL))

    def register(self, request):
        hashed = hash_password(request.password.get_secret_value())
        with self.factory() as db:
            try:
                user = User(name=request.name)
                db.add(user)
                db.flush()
                db.add(Account(user_id=user.id, phone_number=request.phone_number, password_hash=hashed))
                db.flush()
                tokens = self.tokens(user.id)
                self.add_session(db, tokens)
                db.commit()
                return tokens
            except IntegrityError as error:
                db.rollback()
                raise HTTPException(409) from error

    def login_kakao(self, request):
        subject = self.kakao.verify(request.access_token.get_secret_value())
        with self.factory() as db:
            subject = cipher_for(db.bind.dialect).lookup(subject, "kakao.subject")
            identity = db.get(KakaoIdentity, subject)
            if identity is None:
                try:
                    user = User()
                    db.add(user)
                    db.flush()
                    identity = KakaoIdentity(subject=subject, user_id=user.id)
                    db.add(identity)
                    db.flush()
                except IntegrityError:
                    # A concurrent first login may have registered this same identity.
                    db.rollback()
                    identity = db.get(KakaoIdentity, subject)
                    if identity is None:
                        raise HTTPException(409)
            tokens = self.tokens(identity.user_id)
            self.add_session(db, tokens)
            db.commit()
            return tokens

    def login(self, request):
        with self.factory() as db:
            lookup = cipher_for(db.bind.dialect).lookup(request.phone_number, "accounts.phone_number")
            account = db.scalar(select(Account).where(Account.phone_lookup == lookup).with_for_update())
            valid = verify_password(request.password.get_secret_value(), account.password_hash if account else self._dummy)
            if not account or not valid:
                raise HTTPException(401)
            locked = db.execute(update(Account).where(Account.user_id == account.user_id,
                Account.password_hash == account.password_hash).values(password_hash=account.password_hash))
            if locked.rowcount != 1:
                raise HTTPException(401)
            tokens = self.tokens(account.user_id)
            self.add_session(db, tokens)
            db.commit()
            return tokens

    def authenticate(self, token):
        if not 40 <= len(token) <= 128:
            raise HTTPException(401, headers={"WWW-Authenticate": "Bearer"})
        with self.factory() as db:
            session = db.scalar(select(AuthSession).where(AuthSession.access_hash == token_hash(token),
                                                           AuthSession.access_expires_at > int(time.time())))
            if session is None:
                raise HTTPException(401, headers={"WWW-Authenticate": "Bearer"})
            return Principal(session.user_id, session.id)

    def refresh(self, token):
        now = int(time.time())
        old_hash = token_hash(token)
        with self.factory() as db:
            session = db.scalar(select(AuthSession).where(AuthSession.refresh_hash == old_hash,
                                                           AuthSession.refresh_expires_at > now))
            if session is None:
                raise HTTPException(401)
            tokens = self.tokens(session.user_id)
            # Compare-and-swap prevents two concurrent refreshes from both succeeding.
            result = db.execute(update(AuthSession).where(AuthSession.id == session.id,
                AuthSession.refresh_hash == old_hash, AuthSession.refresh_expires_at > now).values(
                access_hash=token_hash(tokens.access_token), refresh_hash=token_hash(tokens.refresh_token),
                access_expires_at=now + ACCESS_TTL))
            if result.rowcount != 1:
                db.rollback()
                raise HTTPException(401)
            tokens.refresh_expires_in = session.refresh_expires_at - now
            db.commit()
            return tokens

    def logout(self, principal):
        with self.factory() as db:
            db.execute(delete(AuthSession).where(AuthSession.id == principal.session_id))
            db.commit()

    def change_password(self, principal, request):
        with self.factory() as db:
            account = db.scalar(select(Account).where(Account.user_id == principal.user_id).with_for_update())
            if not account or not verify_password(request.current_password.get_secret_value(), account.password_hash):
                raise HTTPException(401)
            changed = db.execute(update(Account).where(Account.user_id == account.user_id,
                Account.password_hash == account.password_hash).values(password_hash=hash_password(request.new_password.get_secret_value())))
            if changed.rowcount != 1:
                raise HTTPException(401)
            db.execute(delete(AuthSession).where(AuthSession.user_id == principal.user_id))
            db.commit()

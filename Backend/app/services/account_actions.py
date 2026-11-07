"""Single-use account action tokens; delivery is an injected boundary."""
import os
import smtplib
import ssl
import time
from email.message import EmailMessage
from fastapi import HTTPException
from sqlalchemy import select, delete, update
from app.core.security import new_token, token_hash, hash_password
from app.models.auth import Account, AuthSession
from app.models.lifecycle import AccountAction, EmailVerification
from app.core.data_encryption import cipher_for


class SMTPDelivery:
    def configured(self):
        return bool(os.getenv("SMTP_HOST") and os.getenv("SMTP_FROM"))

    def send(self, email, purpose, token):
        message = EmailMessage()
        message["From"], message["To"] = os.environ["SMTP_FROM"], email
        message["Subject"] = "Click email verification" if purpose == "verify_email" else "Click password reset"
        message.set_content("Enter this one-time token in Click. Do not share it.\n\n" + token)
        with smtplib.SMTP(os.environ["SMTP_HOST"], int(os.getenv("SMTP_PORT", "587")), timeout=10) as smtp:
            smtp.starttls(context=ssl.create_default_context())
            if os.getenv("SMTP_USERNAME"):
                smtp.login(os.environ["SMTP_USERNAME"], os.environ.get("SMTP_PASSWORD", ""))
            smtp.send_message(message)


class AccountActionService:
    def __init__(self, factory, delivery=None, clock=time.time):
        self.factory, self.delivery, self.clock = factory, delivery or SMTPDelivery(), clock

    def request(self, purpose, email=None, user_id=None):
        if not self.delivery.configured():
            raise HTTPException(503)
        now, token = int(self.clock()), new_token()
        with self.factory() as db:
            lookup = cipher_for(db.bind.dialect).lookup(email, "accounts.email")
            query = select(Account).where(Account.user_id == user_id) if user_id else select(Account).where(Account.email_lookup == lookup)
            account = db.scalar(query.with_for_update())
            if account is None or not account.email:
                return
            db.execute(delete(AccountAction).where(AccountAction.expires_at <= now))
            db.execute(delete(AccountAction).where(AccountAction.user_id == account.user_id, AccountAction.purpose == purpose))
            db.add(AccountAction(token_hash=token_hash(token), user_id=account.user_id, purpose=purpose,
                expires_at=now + (86400 if purpose == "verify_email" else 1200),
                credential_hash=token_hash(account.password_hash)))
            recipient = account.email
            db.commit()
        try:
            self.delivery.send(recipient, purpose, token)
        except Exception as error:
            with self.factory() as db:
                db.execute(delete(AccountAction).where(AccountAction.token_hash == token_hash(token)))
                db.commit()
            raise HTTPException(503) from error

    def consume(self, token, purpose, password=None):
        now = int(self.clock())
        # Expensive hashing happens before acquiring account locks.
        hashed = hash_password(password) if password is not None else None
        with self.factory() as db:
            action = db.get(AccountAction, token_hash(token))
            if action is None or action.purpose != purpose or action.expires_at <= now:
                raise HTTPException(400)
            account = db.scalar(select(Account).where(Account.user_id == action.user_id).with_for_update())
            if account is None or token_hash(account.password_hash) != action.credential_hash:
                raise HTTPException(400)
            changed = db.execute(delete(AccountAction).where(AccountAction.token_hash == token_hash(token),
                AccountAction.expires_at > now))
            if changed.rowcount != 1:
                raise HTTPException(400)
            if purpose == "reset_password":
                changed = db.execute(update(Account).where(Account.user_id == account.user_id,
                    Account.password_hash == account.password_hash).values(password_hash=hashed))
                if changed.rowcount != 1:
                    raise HTTPException(400)
                db.execute(delete(AuthSession).where(AuthSession.user_id == account.user_id))
                db.execute(delete(AccountAction).where(AccountAction.user_id == account.user_id))
            elif db.get(EmailVerification, account.user_id) is None:
                db.add(EmailVerification(user_id=account.user_id, verified_at=now))
            db.commit()

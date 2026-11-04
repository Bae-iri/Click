import re

from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator, model_validator


class Credentials(BaseModel):
    model_config = ConfigDict(extra="forbid")
    email: str = Field(min_length=3, max_length=254)
    password: SecretStr = Field(min_length=8, max_length=128)

    @field_validator("email")
    @classmethod
    def normalize_email(cls, value):
        value = value.strip().lower()
        if not re.fullmatch(r"[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,63}", value):
            raise ValueError("Invalid email format")
        return value


class RegisterRequest(Credentials):
    name: str | None = Field(default=None, min_length=1, max_length=50)

    @model_validator(mode="before")
    @classmethod
    def discard_retired_fields(cls, value):
        # Old APKs may still send these fields. Accept the account request while
        # dropping demographics before validation, persistence or response output.
        if isinstance(value, dict):
            return {k: v for k, v in value.items() if k not in {"age_group", "occupation_category"}}
        return value

    @field_validator("name")
    @classmethod
    def normalize_name(cls, value):
        if value is None:
            return None
        value = value.strip()
        if not value:
            raise ValueError("Name must not be blank")
        return value


class RefreshRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    refresh_token: SecretStr = Field(min_length=40, max_length=128)


class KakaoLoginRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    access_token: SecretStr = Field(min_length=1, max_length=2048)

    @field_validator("access_token")
    @classmethod
    def validate_access_token(cls, value):
        token = value.get_secret_value()
        if any(char.isspace() or ord(char) < 33 or ord(char) > 126 for char in token):
            raise ValueError("Invalid access token")
        return value


class TokenResponse(BaseModel):
    user_id: str
    token_type: str = "bearer"
    access_token: str
    refresh_token: str
    expires_in: int
    refresh_expires_in: int


class ChangePasswordRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    current_password: SecretStr = Field(min_length=1, max_length=128)
    new_password: SecretStr = Field(min_length=8, max_length=128)

import re

from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator, model_validator


class Credentials(BaseModel):
    model_config = ConfigDict(extra="forbid")
    phone_number: str = Field(min_length=10, max_length=20)
    password: SecretStr = Field(min_length=4, max_length=4)

    @field_validator("phone_number")
    @classmethod
    def normalize_phone(cls, value):
        value = re.sub(r"[ -]", "", value.strip())
        if not re.fullmatch(r"01[016789][0-9]{7,8}", value):
            raise ValueError("Invalid phone number")
        return value

    @field_validator("password")
    @classmethod
    def validate_pin(cls, value):
        if not re.fullmatch(r"[0-9]{4}", value.get_secret_value()):
            raise ValueError("Password must contain exactly four digits")
        return value


class RegisterRequest(Credentials):
    name: str = Field(min_length=1, max_length=50)

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
    new_password: SecretStr = Field(min_length=4, max_length=4)

    @field_validator("new_password")
    @classmethod
    def validate_pin(cls, value):
        return Credentials.validate_pin(value)

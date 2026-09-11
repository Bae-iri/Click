import re

from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator

from app.schemas.personalization import AgeGroup


class Credentials(BaseModel):
    model_config = ConfigDict(extra="forbid")
    email: str = Field(min_length=3, max_length=254)
    password: SecretStr = Field(min_length=12, max_length=128)

    @field_validator("email")
    @classmethod
    def normalize_email(cls, value):
        value = value.strip().lower()
        if not re.fullmatch(r"[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,63}", value):
            raise ValueError("Invalid email format")
        return value


class RegisterRequest(Credentials):
    age_group: AgeGroup = "unknown"
    occupation_category: str | None = Field(default=None, max_length=50)


class RefreshRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    refresh_token: SecretStr = Field(min_length=40, max_length=128)


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
    new_password: SecretStr = Field(min_length=12, max_length=128)

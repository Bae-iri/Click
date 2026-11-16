"""Feedback is an interaction, never an executable goal or completion signal."""
from typing import Literal
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field, field_validator

FeedbackSentiment = Literal['negative', 'positive']


class ClassifyInteraction(BaseModel):
    model_config = ConfigDict(extra='forbid')
    id: UUID
    text: str = Field(min_length=1, max_length=500)
    session_id: str | None = Field(default=None, min_length=1, max_length=200)

    @field_validator('text')
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError('Text is required')
        return value.strip()


class FeedbackDetail(BaseModel):
    model_config = ConfigDict(extra='forbid')
    detail: str = Field(min_length=1, max_length=2000)

    @field_validator('detail')
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError('Feedback is required')
        return value.strip()


class ProductFeedbackResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: str
    sentiment: FeedbackSentiment
    source_text: str
    detail: str | None
    created_at: int
    updated_at: int


class InteractionResponse(BaseModel):
    intent: Literal['task', 'negative_feedback', 'positive_feedback']
    message: str | None = None
    feedback: ProductFeedbackResponse | None = None


class FeedbackPage(BaseModel):
    items: list[ProductFeedbackResponse]
    next_cursor: str | None

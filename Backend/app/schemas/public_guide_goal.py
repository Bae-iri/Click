from typing import Literal

from pydantic import BaseModel, ConfigDict

from app.schemas.public_guide_types import PublicGuideGoalType


class PublicGuideGoal(BaseModel):
    model_config = ConfigDict(extra='forbid')
    goal_type: PublicGuideGoalType = 'gov24.login'
    # Opt-in Gemini planner pilot (CLICK_GOV24_PLANNER_ENABLED + gov24_planner capability).
    planner: bool = False
    request_scope: Literal['screen_only', 'task_completion'] = 'screen_only'

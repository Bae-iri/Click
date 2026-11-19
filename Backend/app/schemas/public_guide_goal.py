from typing import Literal

from pydantic import BaseModel, ConfigDict


PublicGoalType = Literal[
    'gov24.login', 'gov24.resident_copy.open', 'gov24.resident_extract.open',
    'gov24.move_in.open', 'gov24.building_register.open',
]


class PublicGuideGoal(BaseModel):
    model_config = ConfigDict(extra='forbid')
    goal_type: PublicGoalType = 'gov24.login'
    request_scope: Literal['screen_only', 'task_completion'] = 'screen_only'

"""Account information and opt-in data-use consents; no user modeling."""
from app.repositories.users import UserRepository
from app.schemas.users import ConsentResponse, UserResponse


class UserService:
    def __init__(self, repository: UserRepository):
        self.repository = repository

    def get_user(self, user_id):
        return UserResponse.model_validate(self.repository.get_user(user_id), from_attributes=True)

    def record_consent(self, user_id, request):
        return ConsentResponse.model_validate(self.repository.record_consent(
            user_id, request.purpose, request.consented, request.policy_version))

    def delete_user(self, user_id):
        self.repository.delete_user(user_id)

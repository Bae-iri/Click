"""Route social feedback before creating or advancing any executable request."""
import json
import re
import time
from pathlib import Path
from fastapi import HTTPException
from sqlalchemy import or_, and_, select, update
from app.models.agent_session import AgentSession
from app.models.product_feedback import ProductFeedback
from app.models.user import User
from app.schemas.interaction import ProductFeedbackResponse, InteractionResponse, FeedbackPage
from app.services.goal_classifier import configured_goal_classifier
from app.services.goal_inputs import normalize

MESSAGES = {
    'negative': '죄송합니다. 클릭이 아직 성장 중이라 부족한 점이 있어요. 어떤 점이 불편하셨는지 적어주시면 개선에 참고할게요.',
    'positive': '고마워요! 도움이 되었다니 기뻐요. 좋았던 점도 알려주시면 앞으로 개선하는 데 참고할게요.',
}
# Explicit imperative requests win over surrounding praise/complaints. Past-tense
# thanks ("켜줘서 고마워") must still reach the social-intent classifier.
_EXACT_FEEDBACK = {
    **dict.fromkeys(('고마워', '고마워요', '정말고마워', '고맙다', '고맙습니다', '감사해', '감사해요', '감사합니다',
        '클릭고마워', '고마워클릭', '잘했어', '클릭최고야', '너최고야', '도움됐어'), 'positive_feedback'),
    **dict.fromkeys(('야이상한색기야', '야이상한새끼야', '야이상한색끼야', '이상한색기야', '이상한새끼야',
        '너멍청해', '너진짜멍청하다', '클릭답답해', '클릭진짜답답하네', '클릭별로야', '클릭쓸모없어'), 'negative_feedback'),
}
_COMMAND_END = re.compile(r'(켜줘|켜주세요|꺼줘|꺼주세요|키워줘|줄여줘|낮춰줘|높여줘|바꿔줘|열어줘|보여줘|설정해줘|연결해줘|해줘|해주세요|켜|꺼|끄기|켜기|크게|작게|밝게|어둡게)$')


class InteractionService:
    def __init__(self, factory, classifier=None, clock=time.time):
        self.factory, self.clock = factory, clock
        self.classifier = classifier if classifier is not None else configured_goal_classifier()
        self.catalog = json.loads((Path(__file__).parents[1] / 'task_graphs/goal_catalog.json').read_text())['goals']

    @staticmethod
    def _owned(db, user_id, identifier):
        row = db.get(ProductFeedback, str(identifier))
        if row is None or row.user_id != user_id:
            raise HTTPException(404)
        return row

    @staticmethod
    def _response(row):
        return InteractionResponse(intent=row.sentiment + '_feedback', message=MESSAGES[row.sentiment],
            feedback=ProductFeedbackResponse.model_validate(row))

    @staticmethod
    def _same(row, user_id, request):
        if row.user_id != user_id:
            raise HTTPException(404)
        if row.source_text != request.text or row.session_id != request.session_id:
            raise HTTPException(409, detail='INTERACTION_ID_REUSED')

    def _explicit_task(self, source):
        text = normalize(source)
        if any(text == normalize(utterance) for goal in self.catalog for utterance in goal.get('utterances', [])):
            return True
        if not _COMMAND_END.search(text):
            return False
        return any(all(any(normalize(word) in text for word in group) for group in goal['keyword_groups'])
                   for goal in self.catalog)

    def classify(self, user_id, request):
        with self.factory() as db:
            cached = db.get(ProductFeedback, str(request.id))
            if cached:
                self._same(cached, user_id, request)
                return self._response(cached)
            if request.session_id:
                session = db.get(AgentSession, request.session_id)
                if session is None or session.user_id != user_id:
                    raise HTTPException(404)
        if self._explicit_task(request.text):
            return InteractionResponse(intent='task')
        classify = getattr(self.classifier, 'classify_interaction', None)
        intent = classify(request.text) if classify else None
        if intent is None:
            # Exact common phrases remain usable without model availability.
            # No substring matching: quoted insults and mixed tasks cannot land here.
            intent = _EXACT_FEEDBACK.get(normalize(request.text))
        # No alternate model provider or execution permission on uncertainty.
        # Unknown wording keeps the normal goal clarification path available.
        if intent not in {'negative_feedback', 'positive_feedback'}:
            return InteractionResponse(intent='task')
        sentiment = intent.removesuffix('_feedback')
        now = int(self.clock())
        with self.factory() as db:
            # Same serialization order as account deletion and the request queue.
            if db.execute(update(User).where(User.id == user_id).values(id=user_id)).rowcount != 1:
                raise HTTPException(401)
            existing = db.get(ProductFeedback, str(request.id))
            if existing:
                self._same(existing, user_id, request)
                return self._response(existing)
            if request.session_id:
                session = db.get(AgentSession, request.session_id)
                if session is None or session.user_id != user_id:
                    raise HTTPException(404)
            row = ProductFeedback(id=str(request.id), user_id=user_id, session_id=request.session_id,
                sentiment=sentiment, source_text=request.text, detail=None, created_at=now, updated_at=now)
            db.add(row)
            db.commit()
            return self._response(row)

    def submit(self, user_id, identifier, body):
        with self.factory() as db:
            if db.execute(update(User).where(User.id == user_id).values(id=user_id)).rowcount != 1:
                raise HTTPException(401)
            row = self._owned(db, user_id, identifier)
            row.detail, row.updated_at = body.detail, int(self.clock())
            db.commit()
            return ProductFeedbackResponse.model_validate(row)

    def get(self, user_id, identifier):
        with self.factory() as db:
            return ProductFeedbackResponse.model_validate(self._owned(db, user_id, identifier))

    def review(self, limit, sentiment=None, before=None):
        with self.factory() as db:
            query = select(ProductFeedback)
            if sentiment:
                query = query.where(ProductFeedback.sentiment == sentiment)
            if before:
                cursor = db.get(ProductFeedback, str(before))
                if cursor is None:
                    raise HTTPException(404)
                query = query.where(or_(ProductFeedback.created_at < cursor.created_at,
                    and_(ProductFeedback.created_at == cursor.created_at, ProductFeedback.id < cursor.id)))
            rows = db.scalars(query.order_by(ProductFeedback.created_at.desc(), ProductFeedback.id.desc()).limit(limit + 1)).all()
            return FeedbackPage(items=[ProductFeedbackResponse.model_validate(row) for row in rows[:limit]],
                next_cursor=rows[limit-1].id if len(rows) > limit else None)

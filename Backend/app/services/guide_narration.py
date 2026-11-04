"""Optional presentation-only LLM. Never receives screen trees, user text or execution tools."""
import json
import os
from queue import Empty, Queue
from threading import BoundedSemaphore, Thread

from app.services.goal_llm import LocalGoalLlm, _unique_object

_slots = BoundedSemaphore(2)
# Only public menu vocabulary is eligible for inference. Unknown UI text stays local.
LABELS = frozenset({"디스플레이", "일반", "접근성", "시인성 향상", "글자 크기",
                    "글자 크기와 스타일", "글자 크기 및 스타일", "삼성 키보드 설정", "설정"})
OPENINGS = ("", "천천히 한 단계씩 진행해 볼게요.", "이제 다음 단계로 가볼게요.")


def options_for(label, action, direction=None):
    if label not in LABELS:
        return []
    if action == "tap":
        return [f"화면에서 {label} 항목을 눌러주세요.", f"{label} 항목을 찾아 눌러주세요."]
    if action == "drag" and direction in {"right", "left"}:
        side = "오른쪽" if direction == "right" else "왼쪽"
        return [f"{label} 조절 막대를 {side}으로 한 단계 움직여주세요."]
    return []


class GuideNarrator:
    def __init__(self, sender=None, timeout=0.8):
        self.sender = sender
        self.timeout = timeout

    def generate(self, label, action, direction=None, *, setting=None):
        instructions = options_for(label, action, direction)
        if not instructions:
            return None
        # A closed expression grammar is intentional: text may never invent a new action,
        # reverse direction, claim completion, or drop the target from an instruction.
        openings = (*OPENINGS, "글자 크기를 바꾸러 가볼게요.") if setting == "font_size" else OPENINGS
        payload_data = {"target": label, "action": action, "direction": direction,
                        "openings": openings, "instructions": instructions}
        if not _slots.acquire(blocking=False):
            return None
        result = Queue(maxsize=1)

        def run():
            try:
                endpoint, model = LocalGoalLlm.configuration()
                payload = {"model": model, "temperature": 0, "max_tokens": 160, "stream": False,
                           "response_format": {"type": "json_object"}, "messages": [
                               {"role": "system", "content":
                                '확정된 행동의 한국어 음성 대본 표현만 고른다. 입력의 openings와 instructions에서 '
                                '각각 하나씩 원문 그대로 선택해 {"opening":"...","instruction":"..."} JSON만 반환한다. '
                                '대상·행동·방향을 변경하거나 완료·자동실행을 주장하지 않는다.'},
                               {"role": "user", "content": json.dumps(payload_data, ensure_ascii=False)}]}
                response = (self.sender or endpoint.post)(payload, timeout=self.timeout)
                if response.status_code != 200:
                    return
                choices = response.json()["choices"]
                if not isinstance(choices, list) or len(choices) != 1:
                    return
                choice = choices[0]
                message = choice["message"]
                text = message["content"]
                if (choice.get("finish_reason") != "stop" or message.get("tool_calls")
                        or message.get("refusal") or not isinstance(text, str) or len(text) > 1200):
                    return
                value = json.loads(text, object_pairs_hook=_unique_object)
                if (not isinstance(value, dict) or set(value) != {"opening", "instruction"}
                        or value["opening"] not in openings or value["instruction"] not in instructions):
                    return
                result.put(" ".join(filter(None, [value["opening"], value["instruction"]])))
            except Exception:
                # No prompt/response logs, retries, persistence or third-party fallback.
                pass
            finally:
                _slots.release()
                if result.empty():
                    result.put(None)

        try:
            Thread(target=run, daemon=True, name="guide-narration").start()
        except Exception:
            _slots.release()
            return None
        try:
            return result.get(timeout=self.timeout)
        except Empty:
            return None

    def decorate(self, response, *, graph=None):
        if os.getenv("GUIDE_NARRATION_ENABLED", "false").lower() != "true":
            return response
        guide = response.guide_command
        policy = guide.render_policy
        if (response.task_status != "IN_PROGRESS" or guide.command_type not in {"highlight", "gesture_hint"}
                or guide.safety_level not in {"LOW_RISK", "CONFIRM_REQUIRED"} or not guide.requires_user_action
                or guide.target_bounds is None or policy.get("vision_derived")
                or any(k in policy for k in ("recovery_action", "fallback_action", "vision_fallback"))):
            return response
        direction = policy.get("direction")
        if guide.action_type == "drag" and policy.get("automatable_setting") != "font_size":
            return response
        if policy.get("runtime_strategy") not in {None, "continue"}:
            return response
        if set(policy) - {"avoid_covering_target", "preferred_position", "direction", "automatable_setting",
                          "runtime_strategy", "attempt_id", "step_id"}:
            return response
        # Match the immutable transition and its whole original message. This also preserves
        # warnings or explanations added by any other presentation layer.
        if graph is None:
            from ai_agent.runtime.tasks import TASK_GRAPHS
            graph = TASK_GRAPHS.get(response.task_id)
        if graph is None or graph.definition.task_id != response.task_id:
            return response
        transition = next((item for item in graph.transitions
                           if item.transition_id == policy.get("step_id")
                           and item.from_state == response.state_id
                           and item.action_type.value == guide.action_type
                           and item.guide_message == guide.message), None)
        if transition is None:
            return response
        # The HTTP boundary strips observed target_label. Use only fixed graph vocabulary.
        label = next((word for word in transition.target_keywords if word in LABELS), None)
        if guide.target_label is not None and guide.target_label != label:
            return response
        script = self.generate(label, guide.action_type, direction,
                               setting="font_size" if response.task_id in {"MVP-01", "MVP-02"} else None)
        if script is None:
            return response
        decorated = response.model_copy(deep=True)
        decorated.guide_command.message = script
        return decorated

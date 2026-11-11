"""Normal user choices are independent of technical results and failures."""

def user_declined(setting=None, *, previously_changed=False, scope="action"):
    if scope == "vision":
        message = "화면을 분석하지 않고 안내를 마칠게요."
    elif previously_changed:
        message = "추가로 조절하지 않고 안내를 마칠게요."
    else:
        message = {"brightness": "밝기를 바꾸지 않고 안내를 마칠게요.",
                   "font_size": "글자 크기를 바꾸지 않고 안내를 마칠게요.",
                   "volume": "음량을 바꾸지 않고 안내를 마칠게요."}.get(setting,
                       "설정을 바꾸지 않고 안내를 마칠게요.")
    return {"version": "task-termination.v1", "category": "USER_CHOICE",
            "code": "USER_DECLINED", "scope": scope, "message": message,
            "retryable": False, "counts_as_failure": False}

# Click Backend

FastAPI 기반 Android 사용 안내 서버입니다.

영구 DB를 사용하려면 `DATA_ENCRYPTION_KEYS`와 `DATA_LOOKUP_KEY` 설정 및 암호화 마이그레이션이 필요합니다. [암호화 저장·키 교체·배포 순서](docs/data-encryption.md)를 먼저 확인하세요. AI2의 새 역할과 분석 명령은 [Jev 판단 평가 안내](../docs/ai2-jev-assessment.md)에 정리했습니다.

**입력 분류:** `resolve_goal`은 기본 자체 서버 분류기 또는 명시적으로 선택한 Jev를 사용한다. 전송 전 개인정보 패턴 마스킹, 승인 후보·응답 검증이 적용되며 추론 실패 시 질문 흐름으로 돌아간다. [자체 서버 설정](inference/README.md#입력-분류-llm만-연결하기), [Jev 설정과 연결 점검](inference/jev.md)을 참고한다.

```powershell
python -m pip install -e ".[test]"
.\scripts\dev.ps1
```

[개발·운영 안내](docs/README.md)에 현재 상태·실행·API·개인정보·모델 연결·배포·검증을 모았습니다. 정확한 API 필드는 [OpenAPI](docs/openapi.json), AI2 상세는 [Agent 2 안내](../AI-Agent/src/ai_agent/graph_proposal/README.md)를 참고하세요.

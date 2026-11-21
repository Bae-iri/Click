# MCv2 타깃 비용 검증 재현

실행 소스·환경·APK·준비 파일은 `source-freeze.json`과 `environment.json`을 기준으로 한다. [MCv1 재현 방법](../2026-11-20-native-public-apps-b2/reproduce.md)의 빌드·loopback facade·실제 공급자·카탈로그·안전 제약을 유지한다. 원자료나 기존 앱 데이터를 삭제하지 않고 새 실행 ID와 ASCII suffix를 사용한다.

이 코호트의 run ID는 `native-public-apps-mcv2-nofx-20261121-r1`, suffix는 `mcv2-nofx-20261121-r1`이다. 다음 네 단계 모두 `-e outputSuffix <새 suffix> -e runId <새 run ID>`를 전달한다. 다른 APK나 측정 context의 준비 결과를 재사용하지 않는다.

1. `MetaControllerPublicAppsMatrixTest#validateFrozenFixtures`, `-e publicAppsPreflight true`.
2. `#validateNativeGuards`, `-e publicAppsGuardPreflight true`.
3. `#runPublicAppsMatrix`, `-e publicAppsExperiment true -e conditionsJson '[]' -e phase freeze`.
4. 실제 모델 호출 전에 전체 36회 일정, 선택된 12회와 NOT_RUN 24회, source commit/tree·모든 소스·APK·환경·준비 해시를 동결한다. Facade가 꺼져 있고 포트 8769가 닫혀 있어야 한다. 이후 새 provider-terminal 경로로 facade를 시작하고 `#runPublicAppsMatrix`, `-e publicAppsExperiment true -e conditionsJson '["accepted_no_effect"]' -e phase targeted-no-effect`만 한 번 실행한다.

`catalogue64`는 기존 카탈로그 파일 바이트의 base64다. `adb shell` 문자열은 Python 리스트를 `shlex.join`으로 전달한다. 실제 임시 실행 helper는 `/private/tmp/click-mcv2-instrument.py`였으며, 호스트 helper가 없어도 위 public 계측 API로 재현할 수 있다. 새 재현 실행은 이전 코호트 ID/suffix/terminal을 사용하지 않는다.

장치의 `files/public-apps-meta-v1-<suffix>`에서 readiness, guard-readiness, manifest, manifest-freeze, manifest-targeted-no-effect와 episodes를 보관한다. `no_backup/app-task-diagnostics`와 `no_backup/app-task-completion-diagnostics`는 디렉터리 목록을 먼저 얻고 존재하는 previous/current만 읽는다. 명령 return code만으로 성공을 추정하지 않고 JSONL의 모든 줄을 엄격히 파싱한다. 원본 전체 journal을 유지하고 이번 episode task_ref 집합으로 범위를 제한한 파생 로그를 별도로 만든다. 알 수 없는 진단 줄을 자동 제거하지 않는다. 호스트 facade terminal과 원래 native 시각도 보존한다.

새 `RESULT` 디렉터리에 다음을 실행한다.

```sh
python Backend/scripts/audit_meta_controller_public_apps_provider.py \
  --episodes RESULT/episodes.jsonl --terminal RESULT/provider-terminal.jsonl \
  --output RESULT/request-audit.json
python Backend/scripts/summarize_meta_controller_targeted_cost.py \
  --episodes RESULT/episodes.jsonl --manifest RESULT/manifest.json \
  --readiness RESULT/readiness.json --request-audit RESULT/request-audit.json \
  --reference-episodes docs/reviews/2026-11-20-native-public-apps-b2/episodes.jsonl \
  --reference-audit docs/reviews/2026-11-20-native-public-apps-b2/request-audit.json \
  --output RESULT/summary.json
```

12회 완료·6쌍·정책 A=current/B=mc_v2·시작 상태·관찰 창·원자료 직접 결합을 먼저 확인한다. API 시도/unknown과 알려진 토큰 사용량을 보고한다. 실패 포함 시간은 공동 성공 시각 또는 30초다. 전체 36회 완료는 false이며, 제외한 정상/지연 24회를 실패로 채우거나 이 타깃 실험만으로 기본 활성화 GO를 만들지 않는다. 직전 MCv1과의 비교는 시간·순서·모델 응답 변화가 남는 기술적 비교다.

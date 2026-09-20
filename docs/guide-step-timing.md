# 단계별 안내 시간 수집 (첫 구현)

## 무엇을 측정하는가

`guide_action_observed`는 **탭 안내를 화면/음성으로 제시한 시점부터 해당 앱에서 첫 탭을 관측한 시점까지**의 밀리초다. 앱을 여는 첫 단계는 `S0_UNKNOWN`, `step=0`; 설정 앱 안의 다음 메뉴는 해당 state와 step으로 기록한다. 오버레이의 설정 열기 버튼을 누른 경우도 첫 탭으로 기록한다. Android 접근성 이벤트 `TYPE_VIEW_CLICKED`를 사용하므로 슬라이더 드래그, 탭 이벤트를 제공하지 않는 앱, 첫 탭 이후 완료까지의 시간은 이 지표에 포함되지 않는다.

첫 탭이 올바른 대상이었는지, 다음 화면에 도달했는지, 사용자가 이해했는지는 이 이벤트만으로 알 수 없다. 긴 시간에는 읽기, 접근성 조작, 잠시 중단도 포함될 수 있다. 이 시간을 `comprehension_level` 점수에 더하거나 빼지 않는다.

## 저장 계약

- 앱의 메뉴에서 사용자가 개인화 기록에 명시적으로 동의한다. 동의가 없거나 확인에 실패하면 전송하지 않는다.
- FE는 각 이벤트 전 최신 개인화 동의를 조회하고, BE도 저장 전에 다시 확인한다.
- `ui_events`에 `user_id`, `session_id`, `event_type=guide_action_observed`, `screen_name`(등록된 state ID), `metadata.step`, `metadata.duration_ms`를 저장한다. 화면 글자, 클릭 좌표, 비밀번호는 이 이벤트에 넣지 않는다.
- 동의 철회는 기존 개인화 이벤트를 삭제하고 프로필을 기본값으로 되돌리는 기존 Backend 동작을 사용한다.
- 앱의 안내 경로는 이 기록 전송을 기다리지 않는다. 전송 실패는 사용자의 안내를 중단하지 않는다.

## 기기 테스트

1. Render DB 자격 증명을 교체하고 앱 서버의 `DATABASE_URL`을 갱신한다. 서버가 `/ready`에 응답하는지 확인한다.
2. 앱 로그인 후 메뉴에서 단계별 안내 시간 기록에 동의한다.
3. 글자 크기 확대 요청을 실행하고 홈 화면에서 설정 앱을 누른 뒤 설정 메뉴도 눌러본다.
4. 테스트 계정의 기록만 조회한다. 아래 SQL의 `<테스트_사용자_ID>`는 인증 응답에서 받은 UUID로 대체한다. 접속 URL과 토큰은 공유하지 않는다.

```sql
SELECT session_id, screen_name, metadata->>'step' AS step,
       metadata->>'duration_ms' AS duration_ms, created_at
FROM ui_events
WHERE user_id = '<테스트_사용자_ID>' AND event_type = 'guide_action_observed'
ORDER BY created_at DESC
LIMIT 20;
```

단계별 비교는 기록 수와 중앙값을 함께 본다. 한 번의 긴 시간은 개인의 어려움으로 판단하지 않는다.

```sql
SELECT screen_name, COUNT(*) AS samples,
       ROUND((percentile_cont(0.5) WITHIN GROUP
           (ORDER BY (metadata->>'duration_ms')::numeric))::numeric) AS median_ms
FROM ui_events
WHERE user_id = '<테스트_사용자_ID>' AND event_type = 'guide_action_observed'
GROUP BY screen_name
ORDER BY median_ms DESC;
```

5. 기록 동의를 철회하고 같은 사용자에 대한 `guide_action_observed` 기록이 삭제됐는지 확인한다. 다시 안내를 실행해도 새 기록이 생기지 않아야 한다.

기기에서 실제 접근성 클릭 이벤트가 오는지, Render의 배포 버전이 이 코드를 포함하는지는 코드 테스트만으로 확인할 수 없다. Android SDK가 준비된 환경에서 앱 빌드와 실제 기기 테스트가 필요하다.

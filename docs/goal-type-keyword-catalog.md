# Goal Type·키워드 전체 표

> 이 문서는 `AI-Agent/goal_catalog/mvp_goals.json`에서 자동 생성한다. 직접 수정하지 말고 JSON 수정 후 `cd AI-Agent && python scripts/export_goal_catalog_markdown.py`를 실행한다.

- Catalog version: `2.5.0`
- 전체 Goal: **131개** (Android 설정 111개, 정부24 20개)
- 그래프 실행 승인: **111개**, 공개 화면 안내 연결: **2개**, 분류 전용: **18개**
- `키워드 그룹`의 각 괄호에서는 하나 이상, 괄호와 괄호 사이는 모두 충족해야 분류 후보가 된다.
- 등록된 `utterances` 예문과 전체 문장이 일치하면 키워드 그룹이 빠져 있어도 후보로 인식한다. 띄어쓰기와 문장부호는 무시한다.
- `catalog_only`는 Goal 추출만 가능하다는 뜻이다. 승인 Task Graph가 없으므로 실제 작업은 시작하지 않는다.
- `public_guide`는 별도 공개 화면 안내로 연결된다. 로그인 성공이나 민원 신청·발급 완료를 뜻하지 않는다.
- 설정 변경 상태 검증 경로: **56개**, 직접 진행 안내: **34개**. 승인 표시는 실행 안내를 시작할 수 있다는 뜻이며 실기기 검증 완료를 뜻하지 않는다.
- `settings_assisted`는 화면 안내 뒤 사용자가 직접 선택·입력한다. 사용자 완료 응답은 기술 검증과 별도 기록한다.
- v2.5.0부터 쿠팡 71개 목표는 활성 목록에서 제외했다. 기존 버전의 기록·그래프 호환용 목록은 `legacy_coupang_goals.json`에 보존한다.
- 정부24는 로그인 방법 선택·주민등록등본 발급 안내 화면 찾기 두 목표가 연결되어 있다. 나머지 민원은 준비 중이며 실제 화면 경로를 임의 생성하지 않는다.
- 이전 v2.0은 82개였고, v2.1은 160개였고, v2.2는 설정 화면 열기 경로를 추가했다.

## Android 설정

| # | Goal Type | 표시 이름 | 키워드 그룹 | 실행 상태 | 위험도 |
| ---: | --- | --- | --- | --- | --- |
| 1 | `settings.font_size.increase` | 글자 크기 키우기 | (글자 / 글씨 / 글꼴 / 폰트 / font size / text size) + (키워 / 키우 / 크게 / 확대 / 늘려 / 너무 작 / 작아서 / bigger / larger / increase) | approved (`MVP-01`) | normal |
| 2 | `settings.font_size.decrease` | 글자 크기 줄이기 | (글자 / 글씨 / 글꼴 / 폰트 / font size / text size) + (줄여 / 작게 / 축소 / 낮춰 / 너무 커 / 커서 / smaller / decrease) | approved (`MVP-02`) | normal |
| 3 | `settings.wifi.enable` | 와이파이 켜기 | (와이파이 / wifi) + (켜 / 활성화 / 사용하게 / 키고 / 키는 / 킬 / 켤) | approved (`SET-wifi-enable`) | normal |
| 4 | `settings.wifi.disable` | 와이파이 끄기 | (와이파이 / wifi) + (꺼 / 비활성화 / 해제 / 끄고 / 끄는 / 끌) | approved (`SET-wifi-disable`) | normal |
| 5 | `settings.wifi.connect` | 와이파이 연결 | (와이파이 / wifi / 무선인터넷) + (연결 / 잡아 / 네트워크 선택) | approved · 직접 진행 (`SET-wifi-connect`) | normal |
| 6 | `settings.bluetooth.enable` | 블루투스 켜기 | (블루투스 / bluetooth) + (켜 / 활성화 / 키고 / 키는 / 킬 / 켤) | approved (`SET-bluetooth-enable`) | normal |
| 7 | `settings.bluetooth.disable` | 블루투스 끄기 | (블루투스 / bluetooth) + (꺼 / 비활성화 / 해제 / 끄고 / 끄는 / 끌) | approved (`SET-bluetooth-disable`) | normal |
| 8 | `settings.bluetooth.connect` | 블루투스 기기 연결 | (블루투스 / bluetooth / 이어폰 / 헤드셋) + (연결 / 페어링 / 등록) | approved · 직접 진행 (`SET-bluetooth-connect`) | normal |
| 9 | `settings.mobile_data.enable` | 모바일 데이터 켜기 | (모바일데이터 / 데이터 / 셀룰러) + (켜 / 활성화 / 키고 / 키는 / 킬 / 켤) | approved (`SET-mobile_data-enable`) | normal |
| 10 | `settings.mobile_data.disable` | 모바일 데이터 끄기 | (모바일데이터 / 데이터 / 셀룰러) + (꺼 / 비활성화 / 끄고 / 끄는 / 끌) | approved (`SET-mobile_data-disable`) | normal |
| 11 | `settings.airplane_mode.enable` | 비행기 모드 켜기 | (비행기모드 / 에어플레인모드) + (켜 / 활성화 / 키고 / 키는 / 킬 / 켤) | approved (`SET-airplane_mode-enable`) | normal |
| 12 | `settings.airplane_mode.disable` | 비행기 모드 끄기 | (비행기모드 / 에어플레인모드) + (꺼 / 비활성화 / 끄고 / 끄는 / 끌) | approved (`SET-airplane_mode-disable`) | normal |
| 13 | `settings.hotspot.enable` | 모바일 핫스팟 켜기 | (핫스팟 / 테더링) + (켜 / 활성화 / 공유 / 키고 / 키는 / 킬 / 켤) | approved (`SET-hotspot-enable`) | normal |
| 14 | `settings.hotspot.disable` | 모바일 핫스팟 끄기 | (핫스팟 / 테더링) + (꺼 / 비활성화 / 끄고 / 끄는 / 끌) | approved (`SET-hotspot-disable`) | normal |
| 15 | `settings.brightness.increase` | 화면 밝기 높이기 | (밝기 / 화면빛) + (높여 / 밝게 / 키워 / 올려 / 어두워 / 밝게) | approved (`SET-brightness-increase`) | normal |
| 16 | `settings.brightness.decrease` | 화면 밝기 낮추기 | (밝기 / 화면빛) + (낮춰 / 어둡게 / 줄여 / 눈부셔 / 어둡게) | approved (`SET-brightness-decrease`) | normal |
| 17 | `settings.dark_mode.enable` | 다크 모드 켜기 | (다크모드 / 어두운테마 / 야간모드) + (켜 / 적용 / 바꿔 / 키고 / 키는 / 킬 / 켤) | approved (`SET-dark_mode-enable`) | normal |
| 18 | `settings.dark_mode.disable` | 다크 모드 끄기 | (다크모드 / 어두운테마 / 야간모드) + (꺼 / 해제 / 밝은테마 / 끄고 / 끄는 / 끌) | approved (`SET-dark_mode-disable`) | normal |
| 19 | `settings.screen_timeout.set` | 화면 자동 꺼짐 시간 변경 | (화면자동꺼짐 / 화면꺼짐시간 / 스크린타임아웃) + (변경 / 바꿔 / 늘려 / 줄여 / 설정) | approved · 직접 진행 (`SET-screen_timeout-set`) | normal |
| 20 | `settings.display_size.increase` | 화면 요소 크게 하기 | (화면크기 / 화면요소 / 아이콘크기 / 디스플레이크기) + (키워 / 크게 / 확대 / 늘려) | approved (`SET-display_size-increase`) | normal |
| 21 | `settings.display_size.decrease` | 화면 요소 작게 하기 | (화면크기 / 화면요소 / 아이콘크기 / 디스플레이크기) + (줄여 / 작게 / 축소) | approved (`SET-display_size-decrease`) | normal |
| 22 | `settings.volume.increase` | 미디어 음량 높이기 | (소리 / 볼륨 / 음량 / 미디어 / 음악 / 동영상) + (키워 / 크게 / 높여 / 올려 / 잘안들려) | approved (`SET-volume-increase`) | normal |
| 23 | `settings.volume.decrease` | 미디어 음량 낮추기 | (소리 / 볼륨 / 음량 / 미디어 / 음악 / 동영상) + (줄여 / 작게 / 낮춰 / 시끄러워) | approved (`SET-volume-decrease`) | normal |
| 24 | `settings.mute.enable` | 무음 모드 켜기 | (무음 / 소리안나게 / 조용히) + (켜 / 설정 / 바꿔 / 키고 / 키는 / 킬 / 켤) | approved (`SET-mute-enable`) | normal |
| 25 | `settings.mute.disable` | 무음 모드 끄기 | (무음 / 소리안나게) + (꺼 / 해제 / 소리나게 / 끄고 / 끄는 / 끌) | approved (`SET-mute-disable`) | normal |
| 26 | `settings.ringtone.change` | 벨소리 변경 | (벨소리 / 전화소리 / 링톤) + (바꿔 / 변경 / 선택) | approved · 직접 진행 (`SET-ringtone-change`) | normal |
| 27 | `settings.vibrate.enable` | 진동 모드 켜기 | (진동 / 바이브레이션) + (켜 / 설정 / 바꿔 / 키고 / 키는 / 킬 / 켤) | approved (`SET-vibrate-enable`) | normal |
| 28 | `settings.vibrate.disable` | 진동 모드 끄기 | (진동 / 바이브레이션) + (꺼 / 해제 / 끄고 / 끄는 / 끌) | approved (`SET-vibrate-disable`) | normal |
| 29 | `settings.notifications.enable` | 앱 알림 켜기 | (알림 / 푸시) + (켜 / 허용 / 받게 / 키고 / 키는 / 킬 / 켤) | approved · 직접 진행 (`SET-notifications-enable`) | normal |
| 30 | `settings.notifications.disable` | 앱 알림 끄기 | (알림 / 푸시) + (꺼 / 차단 / 안받게 / 끄고 / 끄는 / 끌) | approved · 직접 진행 (`SET-notifications-disable`) | normal |
| 31 | `settings.location.enable` | 위치 서비스 켜기 | (위치 / gps) + (켜 / 허용 / 활성화 / 키고 / 키는 / 킬 / 켤) | approved (`SET-location-enable`) | normal |
| 32 | `settings.location.disable` | 위치 서비스 끄기 | (위치 / gps) + (꺼 / 차단 / 비활성화 / 끄고 / 끄는 / 끌) | approved (`SET-location-disable`) | normal |
| 33 | `settings.power_saving.enable` | 절전 모드 켜기 | (절전 / 배터리절약 / 저전력) + (켜 / 활성화 / 설정 / 키고 / 키는 / 킬 / 켤) | approved (`SET-power_saving-enable`) | normal |
| 34 | `settings.power_saving.disable` | 절전 모드 끄기 | (절전 / 배터리절약 / 저전력) + (꺼 / 해제 / 비활성화 / 끄고 / 끄는 / 끌) | approved (`SET-power_saving-disable`) | normal |
| 35 | `settings.screen_lock.change` | 화면 잠금 방식 변경 | (화면잠금 / 잠금방식 / 비밀번호 / 핀번호 / 패턴) + (바꿔 / 변경 / 설정) | approved · 직접 진행 (`SET-screen_lock-change`) | normal |
| 36 | `settings.biometric.setup` | 생체 인식 등록 | (지문 / 얼굴인식 / 생체인식) + (등록 / 추가 / 설정) | approved · 직접 진행 (`SET-biometric-setup`) | normal |
| 37 | `settings.apps.uninstall` | 앱 삭제 | (앱 / 어플 / 애플리케이션) + (삭제 / 지워 / 제거) | approved · 직접 진행 (`SET-apps-uninstall`) | normal |
| 38 | `settings.app_permissions.manage` | 앱 권한 관리 | (앱권한 / 어플권한 / 권한) + (관리 / 허용 / 차단 / 바꿔) | approved · 직접 진행 (`SET-app_permissions-manage`) | normal |
| 39 | `settings.storage.cleanup` | 저장 공간 정리 | (저장공간 / 스토리지 / 용량) + (정리 / 비워 / 확보 / 부족) | approved · 직접 진행 (`SET-storage-cleanup`) | normal |
| 40 | `settings.talkback.enable` | TalkBack 켜기 | (톡백 / talkback / 화면읽기) + (켜 / 활성화 / 키고 / 키는 / 킬 / 켤) | approved (`SET-talkback-enable`) | normal |
| 41 | `settings.talkback.disable` | TalkBack 끄기 | (톡백 / talkback / 화면읽기) + (꺼 / 비활성화 / 끄고 / 끄는 / 끌) | approved (`SET-talkback-disable`) | normal |
| 42 | `settings.magnification.enable` | 화면 확대 기능 켜기 | (화면확대 / 돋보기 / 확대기능) + (켜 / 활성화 / 설정 / 키고 / 키는 / 킬 / 켤) | approved (`SET-magnification-enable`) | normal |
| 43 | `settings.magnification.disable` | 화면 확대 기능 끄기 | (화면확대 / 돋보기 / 확대기능) + (꺼 / 비활성화 / 해제 / 끄고 / 끄는 / 끌) | approved (`SET-magnification-disable`) | normal |
| 44 | `settings.language.change` | 기기 언어 변경 | (기기언어 / 휴대폰언어 / 시스템언어) + (바꿔 / 변경 / 설정) | approved · 직접 진행 (`SET-language-change`) | normal |
| 45 | `settings.date_time.automatic.enable` | 날짜와 시간 자동 설정 켜기 | (날짜시간 / 자동시간 / 시간자동) + (켜 / 활성화 / 자동으로 / 키고 / 키는 / 킬 / 켤) | approved (`SET-date_time-automatic-enable`) | normal |
| 46 | `settings.date_time.automatic.disable` | 날짜와 시간 자동 설정 끄기 | (날짜시간 / 자동시간 / 시간자동) + (꺼 / 비활성화 / 수동으로 / 끄고 / 끄는 / 끌) | approved (`SET-date_time-automatic-disable`) | normal |
| 47 | `settings.keyboard.change` | 기본 키보드 변경 | (키보드 / 입력기 / 자판) + (바꿔 / 변경 / 선택 / 설정 / 기본 / 바꾸) | approved · 직접 진행 (`SET-keyboard-change`) | normal |
| 48 | `settings.default_apps.change` | 기본 앱 변경 | (기본앱 / 기본어플 / 디폴트앱) + (바꿔 / 변경 / 선택) | approved · 직접 진행 (`SET-default_apps-change`) | normal |
| 49 | `settings.wifi.share` | 와이파이 공유 | (와이파이 / wifi / 무선인터넷) + (qr로공유 / 큐알로공유 / 공유해 / 공유 / 큐알 / qr / 비밀번호 보여) | approved · 직접 진행 (`SET-wifi-share`) | normal |
| 50 | `settings.wifi.saved_networks.view` | 저장된 와이파이 보기 | (저장된와이파이 / 저장된네트워크 / 와이파이목록) + (보여 / 열어 / 확인) | approved · 직접 진행 (`SET-wifi-saved_networks-view`) | normal |
| 51 | `settings.nfc.enable` | NFC 켜기 | (nfc / 엔에프씨) + (켜 / 활성화 / 사용 / 키고 / 키는 / 킬 / 켤) | approved (`SET-nfc-enable`) | normal |
| 52 | `settings.nfc.disable` | NFC 끄기 | (nfc / 엔에프씨) + (꺼 / 비활성화 / 해제 / 끄고 / 끄는 / 끌) | approved (`SET-nfc-disable`) | normal |
| 53 | `settings.do_not_disturb.enable` | 방해 금지 모드 켜기 | (방해금지 / 집중모드 / dnd) + (켜 / 활성화 / 설정 / 키고 / 키는 / 킬 / 켤) | approved (`SET-do_not_disturb-enable`) | normal |
| 54 | `settings.do_not_disturb.disable` | 방해 금지 모드 끄기 | (방해금지 / 집중모드 / dnd) + (꺼 / 비활성화 / 해제 / 끄고 / 끄는 / 끌) | approved (`SET-do_not_disturb-disable`) | normal |
| 55 | `settings.auto_rotate.enable` | 화면 자동 회전 켜기 | (자동회전 / 화면회전) + (켜 / 활성화 / 허용 / 잠금해제 / 키고 / 키는 / 킬 / 켤) | approved (`SET-auto_rotate-enable`) | normal |
| 56 | `settings.auto_rotate.disable` | 화면 자동 회전 끄기 | (자동회전 / 화면회전) + (꺼 / 비활성화 / 잠가 / 끄고 / 끄는 / 끌) | approved (`SET-auto_rotate-disable`) | normal |
| 57 | `settings.eye_comfort.enable` | 편안하게 화면 보기 켜기 | (블루라이트 / 편안하게화면보기 / 눈보호모드) + (켜 / 활성화 / 설정 / 키고 / 키는 / 킬 / 켤) | approved (`SET-eye_comfort-enable`) | normal |
| 58 | `settings.eye_comfort.disable` | 편안하게 화면 보기 끄기 | (블루라이트 / 편안하게화면보기 / 눈보호모드) + (꺼 / 비활성화 / 해제 / 끄고 / 끄는 / 끌) | approved (`SET-eye_comfort-disable`) | normal |
| 59 | `settings.adaptive_brightness.enable` | 자동 밝기 켜기 | (자동밝기 / 밝기자동조절 / 적응형밝기) + (켜 / 활성화 / 설정 / 키고 / 키는 / 킬 / 켤) | approved (`SET-adaptive_brightness-enable`) | normal |
| 60 | `settings.adaptive_brightness.disable` | 자동 밝기 끄기 | (자동밝기 / 밝기자동조절 / 적응형밝기) + (꺼 / 비활성화 / 해제 / 끄고 / 끄는 / 끌) | approved (`SET-adaptive_brightness-disable`) | normal |
| 61 | `settings.data_saver.enable` | 데이터 절약 모드 켜기 | (데이터절약 / 데이터세이버 / 데이터아끼기) + (켜 / 활성화 / 설정 / 키고 / 키는 / 킬 / 켤) | approved (`SET-data_saver-enable`) | normal |
| 62 | `settings.data_saver.disable` | 데이터 절약 모드 끄기 | (데이터절약 / 데이터세이버 / 데이터아끼기) + (꺼 / 비활성화 / 해제 / 끄고 / 끄는 / 끌) | approved (`SET-data_saver-disable`) | normal |
| 63 | `settings.data_roaming.enable` | 데이터 로밍 켜기 | (데이터로밍 / 해외로밍 / 로밍데이터) + (켜 / 활성화 / 허용 / 키고 / 키는 / 킬 / 켤) | approved (`SET-data_roaming-enable`) | high |
| 64 | `settings.data_roaming.disable` | 데이터 로밍 끄기 | (데이터로밍 / 해외로밍 / 로밍데이터) + (꺼 / 비활성화 / 차단 / 끄고 / 끄는 / 끌) | approved (`SET-data_roaming-disable`) | normal |
| 65 | `settings.vpn.connect` | VPN 연결 | (vpn / 브이피엔) + (연결 / 켜 / 접속) | approved · 직접 진행 (`SET-vpn-connect`) | normal |
| 66 | `settings.vpn.disconnect` | VPN 연결 해제 | (vpn / 브이피엔) + (연결해제 / 접속해제 / 해제 / 꺼 / 끊어) | approved · 직접 진행 (`SET-vpn-disconnect`) | normal |
| 67 | `settings.private_dns.change` | 프라이빗 DNS 변경 | (프라이빗dns / 개인dns / 비공개dns) + (바꿔 / 변경 / 설정) | approved · 직접 진행 (`SET-private_dns-change`) | normal |
| 68 | `settings.sim.manage` | SIM 관리 | (sim / 심카드 / 유심 / esim) + (관리 / 변경 / 설정 / 전환 / 추가 / 등록) | approved · 직접 진행 (`SET-sim-manage`) | high |
| 69 | `settings.hotspot.password.change` | 핫스팟 비밀번호 변경 | (핫스팟비밀번호 / 테더링비밀번호 / 핫스팟비번 / 테더링비번 / 하스팟비번 / 하스팟비밀번호) + (바꿔 / 변경 / 설정) | approved · 직접 진행 (`SET-hotspot-password-change`) | normal |
| 70 | `settings.wallpaper.change` | 배경 화면 변경 | (배경화면 / 월페이퍼 / 잠금화면사진) + (바꿔 / 변경 / 설정) | approved · 직접 진행 (`SET-wallpaper-change`) | normal |
| 71 | `settings.notification_sound.change` | 알림 소리 변경 | (알림소리 / 알림음 / 문자소리) + (바꿔 / 변경 / 선택) | approved · 직접 진행 (`SET-notification_sound-change`) | normal |
| 72 | `settings.battery_percentage.enable` | 배터리 잔량 표시 켜기 | (배터리퍼센트 / 배터리잔량표시 / 배터리숫자 / 배터리잔량 / 배터리백분율 / 배터리비율) + (켜 / 보이게 / 나오게 / 키고 / 키는 / 킬 / 켤 / 표시해 / 표시하기) | approved (`SET-battery_percentage-enable`) | normal |
| 73 | `settings.battery_percentage.disable` | 배터리 잔량 표시 끄기 | (배터리퍼센트 / 배터리잔량표시 / 배터리숫자 / 배터리잔량 / 배터리백분율 / 배터리비율) + (꺼 / 숨겨 / 안보이게 / 끄고 / 끄는 / 끌) | approved (`SET-battery_percentage-disable`) | normal |
| 74 | `settings.battery_status.view` | 배터리 사용량 보기 | (배터리사용량 / 배터리상태 / 배터리소모) + (보여 / 확인 / 열어) | approved · 직접 진행 (`SET-battery_status-view`) | normal |
| 75 | `settings.app_cache.clear` | 앱 캐시 삭제 | (앱캐시 / 어플캐시 / 캐시데이터) + (삭제 / 지워 / 비워) | approved · 직접 진행 (`SET-app_cache-clear`) | normal |
| 76 | `settings.app_data.clear` | 앱 데이터 삭제 | (앱데이터 / 어플데이터 / 애플리케이션데이터) + (삭제 / 지워 / 초기화) | approved · 직접 진행 (`SET-app_data-clear`) | high |
| 77 | `settings.notification_history.view` | 알림 기록 보기 | (알림기록 / 알림내역 / 지난알림) + (보여 / 확인 / 열어) | approved · 직접 진행 (`SET-notification_history-view`) | normal |
| 78 | `settings.lockscreen_notifications.manage` | 잠금 화면 알림 관리 | (잠금화면알림 / 락스크린알림 / 잠금화면의알림) + (관리 / 숨겨 / 표시 / 바꿔 / 설정 / 변경) | approved · 직접 진행 (`SET-lockscreen_notifications-manage`) | normal |
| 79 | `settings.color_inversion.enable` | 색상 반전 켜기 | (색상반전 / 색반전 / 화면색반전) + (켜 / 활성화 / 설정 / 키고 / 키는 / 킬 / 켤) | approved (`SET-color_inversion-enable`) | normal |
| 80 | `settings.color_inversion.disable` | 색상 반전 끄기 | (색상반전 / 색반전 / 화면색반전) + (꺼 / 비활성화 / 해제 / 끄고 / 끄는 / 끌) | approved (`SET-color_inversion-disable`) | normal |
| 81 | `settings.captions.enable` | 실시간 자막 켜기 | (실시간자막 / 라이브캡션 / 자막기능) + (켜 / 활성화 / 설정 / 키고 / 키는 / 킬 / 켤) | approved (`SET-captions-enable`) | normal |
| 82 | `settings.captions.disable` | 실시간 자막 끄기 | (실시간자막 / 라이브캡션 / 자막기능) + (꺼 / 비활성화 / 해제 / 끄고 / 끄는 / 끌) | approved (`SET-captions-disable`) | normal |
| 83 | `settings.mono_audio.enable` | 모노 오디오 켜기 | (모노오디오 / 단일오디오 / 한쪽이어폰소리) + (켜 / 활성화 / 설정 / 키고 / 키는 / 킬 / 켤) | approved (`SET-mono_audio-enable`) | normal |
| 84 | `settings.mono_audio.disable` | 모노 오디오 끄기 | (모노오디오 / 단일오디오 / 한쪽이어폰소리) + (꺼 / 비활성화 / 해제 / 끄고 / 끄는 / 끌) | approved (`SET-mono_audio-disable`) | normal |
| 85 | `settings.backup.enable` | 기기 백업 켜기 | (기기백업 / 자동백업 / 구글백업) + (켜 / 활성화 / 설정 / 키고 / 키는 / 킬 / 켤) | approved · 직접 진행 (`SET-backup-enable`) | normal |
| 86 | `settings.backup.disable` | 기기 백업 끄기 | (기기백업 / 자동백업 / 구글백업) + (꺼 / 비활성화 / 중지 / 끄고 / 끄는 / 끌) | approved · 직접 진행 (`SET-backup-disable`) | high |
| 87 | `settings.software_update.check` | 소프트웨어 업데이트 확인 | (소프트웨어업데이트 / 시스템업데이트 / 업데이트) + (확인 / 검사 / 찾아) | approved · 직접 진행 (`SET-software_update-check`) | normal |
| 88 | `settings.factory_reset` | 기기 초기화 | (공장초기화 / 기기초기화 / 휴대폰초기화) + (진행 / 해줘 / 시작) | approved · 직접 진행 (`SET-factory_reset`) | high |
| 89 | `settings.device_name.change` | 기기 이름 변경 | (기기이름 / 휴대폰이름 / 디바이스이름) + (바꿔 / 변경 / 설정) | approved · 직접 진행 (`SET-device_name-change`) | normal |
| 90 | `settings.navigation.settings` | 설정 설정 열기 | (설정 앱 / 휴대폰 설정 / 핸드폰 설정 / 안드로이드 설정 / 설정 / settings) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-settings`) | normal |
| 91 | `settings.navigation.display` | 디스플레이 설정 열기 | (디스플레이 / 화면 / Display / Display & touch) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-display`) | normal |
| 92 | `settings.navigation.sound` | 소리 및 진동 설정 열기 | (소리 및 진동 / 소리 / Sound & vibration / Sound) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-sound`) | normal |
| 93 | `settings.navigation.connections` | 연결 설정 열기 | (연결 / 네트워크 및 인터넷 / 연결된 기기 / Connections / Network & internet / Connected devices) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-connections`) | normal |
| 94 | `settings.navigation.accessibility` | 접근성 설정 열기 | (접근성 / Accessibility) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-accessibility`) | normal |
| 95 | `settings.navigation.wifi` | 와이파이 설정 열기 | (와이파이 / Wi-Fi / 인터넷 / Internet) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-wifi`) | normal |
| 96 | `settings.navigation.bluetooth` | 블루투스 설정 열기 | (블루투스 / Bluetooth) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-bluetooth`) | normal |
| 97 | `settings.navigation.font_size` | 글자 크기 설정 열기 | (글자 크기 / 글자 크기와 스타일 / Font size / Font size and style) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-font-size`) | normal |
| 98 | `settings.navigation.screen_timeout` | 화면 자동 꺼짐 시간 설정 열기 | (화면 자동 꺼짐 시간 / Screen timeout / Sleep) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-screen-timeout`) | normal |
| 99 | `settings.navigation.display_size` | 화면 크기 설정 열기 | (화면 크기 / 화면 크게/작게 / Display size / Display size and text) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-display-size`) | normal |
| 100 | `settings.navigation.volume` | 음량 설정 열기 | (음량 / 볼륨 / Volume) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-volume`) | normal |
| 101 | `settings.navigation.ringtone` | 벨소리 설정 열기 | (벨소리 / Ringtone / Phone ringtone) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-ringtone`) | normal |
| 102 | `settings.navigation.notification_sound` | 알림 소리 설정 열기 | (알림 소리 / Notification sound / Default notification sound) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-notification-sound`) | normal |
| 103 | `settings.navigation.do_not_disturb` | 방해 금지 설정 열기 | (방해 금지 / Do not disturb) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-do-not-disturb`) | normal |
| 104 | `settings.navigation.visibility` | 시인성 향상 설정 열기 | (시인성 향상 / 텍스트 및 디스플레이 / Visibility enhancements / Color and motion / Text and display) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-visibility`) | normal |
| 105 | `settings.navigation.hearing` | 청각 보조 설정 열기 | (청각 보조 / Hearing enhancements / Audio adjustment) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-hearing`) | normal |
| 106 | `settings.navigation.interaction` | 입력 및 동작 설정 열기 | (입력 및 동작 / Interaction and dexterity / Timing controls) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-interaction`) | normal |
| 107 | `settings.navigation.talkback` | TalkBack 설정 열기 | (TalkBack / 톡백) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-talkback`) | normal |
| 108 | `settings.navigation.magnification` | 확대 설정 열기 | (확대 / 화면 확대 / Magnification) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-magnification`) | normal |
| 109 | `settings.navigation.captions` | 자막 설정 열기 | (자막 / 자막 설정 / Caption preferences / Captions / Live Caption) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-captions`) | normal |
| 110 | `settings.navigation.eye_comfort` | 편안하게 화면 보기 설정 열기 | (편안하게 화면 보기 / 블루라이트 필터 / Eye comfort shield / Night Light / Night light) + (열어 / 열기 / 이동 / 들어가 / 가줘 / 보여줘 / 보여 줘 / open) | approved (`NAV-eye-comfort`) | normal |
| 111 | `settings.theme.change` | 테마 변경 | (테마 / 갤럭시테마 / theme) + (바꿔 / 바꾸 / 변경 / 설정 / 선택 / 적용) | approved · 직접 진행 (`SET-theme-change`) | normal |

## 정부24 생활 민원

| # | Goal Type | 표시 이름 | 키워드 그룹 | 실행 상태 | 위험도 |
| ---: | --- | --- | --- | --- | --- |
| 112 | `gov24.login` | 정부24 로그인 방법 선택 | (정부24 / 정부이십사 / gov24) + (로그인 / 인증 / 접속) | public_guide · 공개 화면까지만 안내 | normal |
| 113 | `gov24.resident_copy.open` | 정부24 주민등록등본 발급 화면 찾기 | (정부24 / 정부이십사 / gov24) + (주민등록등본 / 주민등록표 등본 / 등본) | public_guide · 공개 화면까지만 안내 | normal |
| 114 | `gov24.resident_extract.open` | 정부24 주민등록초본 발급 화면 찾기 | (정부24 / 정부이십사 / gov24) + (주민등록초본 / 주민등록표 초본 / 초본) | catalog_only | normal |
| 115 | `gov24.move_in.open` | 정부24 전입신고 화면 찾기 | (정부24 / 정부이십사 / gov24) + (전입신고 / 전입 신고) | catalog_only | normal |
| 116 | `gov24.building_register.open` | 정부24 건축물대장 발급·열람 화면 찾기 | (정부24 / 정부이십사 / gov24) + (건축물대장 / 건축물 대장) | catalog_only | normal |
| 117 | `gov24.land_register.open` | 정부24 토지·임야대장 발급·열람 화면 찾기 | (정부24 / 정부이십사 / gov24) + (토지대장 / 임야대장 / 토지 대장) | catalog_only | normal |
| 118 | `gov24.land_use_plan.open` | 정부24 토지이용계획 확인 화면 찾기 | (정부24 / 정부이십사 / gov24) + (토지이용계획 / 토지 이용 계획) | catalog_only | normal |
| 119 | `gov24.local_tax_payment.open` | 정부24 지방세 납세증명 화면 찾기 | (정부24 / 정부이십사 / gov24) + (지방세 납세 / 지방세 완납) | catalog_only | normal |
| 120 | `gov24.local_tax_assessment.open` | 정부24 지방세 세목별 과세증명 화면 찾기 | (정부24 / 정부이십사 / gov24) + (세목별 과세 / 세목별과세) | catalog_only | normal |
| 121 | `gov24.national_tax_payment.open` | 정부24 국세 납세증명 화면 찾기 | (정부24 / 정부이십사 / gov24) + (국세 납세 / 국세 완납) | catalog_only | normal |
| 122 | `gov24.income_certificate.open` | 정부24 소득금액증명 화면 찾기 | (정부24 / 정부이십사 / gov24) + (소득금액증명 / 소득 금액 증명) | catalog_only | normal |
| 123 | `gov24.vehicle_register.open` | 정부24 자동차등록원부 발급·열람 화면 찾기 | (정부24 / 정부이십사 / gov24) + (자동차등록원부 / 자동차 등록 원부) | catalog_only | normal |
| 124 | `gov24.resident_card.reissue.open` | 정부24 주민등록증 재발급 화면 찾기 | (정부24 / 정부이십사 / gov24) + (주민등록증 재발급 / 주민등록증 다시) | catalog_only | normal |
| 125 | `gov24.passport.reissue.open` | 정부24 여권 재발급 화면 찾기 | (정부24 / 정부이십사 / gov24) + (여권 재발급 / 여권 다시) | catalog_only | normal |
| 126 | `gov24.application.history.view` | 정부24 민원 신청 내역 화면 찾기 | (정부24 / 정부이십사 / gov24) + (신청내역 / 신청 내역 / 신청한 민원 / 신청 기록) | catalog_only | normal |
| 127 | `gov24.application.status.view` | 정부24 민원 처리 상태 화면 찾기 | (정부24 / 정부이십사 / gov24) + (처리상태 / 처리 상태 / 진행상태 / 진행 상황) | catalog_only | normal |
| 128 | `gov24.documents.issued.view` | 정부24 발급 내역 화면 찾기 | (정부24 / 정부이십사 / gov24) + (발급내역 / 발급 내역 / 발급 기록) | catalog_only | normal |
| 129 | `gov24.documents.electronic.view` | 정부24 전자문서지갑 화면 찾기 | (정부24 / 정부이십사 / gov24) + (전자문서지갑 / 전자 문서 지갑 / 전자증명서) | catalog_only | normal |
| 130 | `gov24.benefits.view` | 정부24 보조금24 혜택 화면 찾기 | (정부24 / 정부이십사 / gov24) + (보조금24 / 보조금 이십사 / 받을 수 있는 혜택) | catalog_only | normal |
| 131 | `gov24.service.search` | 정부24 민원서비스 검색 화면 찾기 | (정부24 / 정부이십사 / gov24) + (민원 검색 / 서비스 검색 / 민원 찾기) | catalog_only | normal |

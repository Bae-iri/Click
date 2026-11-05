# 접근성 안내 이미지

사용자가 제공한 삼성 설정 화면 원본은 `src/main/res/drawable-nodpi/`에 보관합니다.
파일명을 단계와 내용으로 정리했으며 원본 픽셀은 변경하지 않았습니다.

| 원래 파일 | 리소스 | 안내 순서 |
| --- | --- | --- |
| `1.png` | `permission_01_accessibility.png` | 설치된 앱 선택 |
| `2.png` | `permission_02_installed_apps.png` | 클릭 화면 안내 선택 |
| `3.png` | `permission_03_service_off.png` | 위쪽 스위치 켜기 |
| `3.5.png` | `permission_04_consent.png` | 권한 확인창의 허용 |
| `4.png` | `permission_05_service_on.png` | 사용 중 상태 확인 |

`PermissionGuideActivity`는 5장의 고정 카드를 표시합니다. 상단의 이전·안내 다시 듣기 버튼 아래에 진행률을 두고, 다음 버튼은 하단에 표시합니다.
본문을 좌우로 밀어 같은 단계를 이동할 수 있으며 끝에서 자동으로 설정을 열지 않습니다.
`PermissionScreenshotView`는 읽기 쉬운 영역과 파란 테두리를 정적으로 표시합니다.
사진 확대, 자동 넘김과 반복 애니메이션은 사용하지 않습니다.
마지막 ‘지금 설정하기’ 버튼만 실제 접근성 설정을 엽니다.

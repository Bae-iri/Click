# Android common route improvement test

User-specific profiles and UI behavior uploads have been removed. This document keeps the existing USB setup path. See [shared graph improvement](../Backend/docs/shared-graph-improvement.md) for DB migration, analysis and release procedures.

## USB로 PC 서버 연결

Android Studio에서 Frontend를 열어 Android SDK를 설치한다. 휴대폰에서 개발자 옵션과 USB 디버깅을 켜고 PC 연결을 허용한다. SDK platform-tools의 adb를 PATH에 추가하거나 아래 스크립트에 -Adb로 adb.exe 경로를 전달한다.

첫 번째 PowerShell에서 저장소 루트를 기준으로 실행한다. 기본 DB는 재시작해도 유지되는 Backend/click-dev.db이며 기존 DATABASE_URL이 있으면 그 DB를 사용한다.

```powershell
cd Backend
.\scripts\dev.ps1
```

두 번째 PowerShell에서 실행한다. 스크립트는 서버 준비 상태를 확인하고, USB 포트 연결 후 앱을 빌드·설치·실행한다. USB를 다시 연결하면 포트 연결도 다시 실행해야 한다.

```powershell
cd Frontend
.\scripts\install-usb.ps1
# adb가 PATH에 없다면:
# .\scripts\install-usb.ps1 -Adb 'C:\path\to\Android\Sdk\platform-tools\adb.exe'
```

여러 기기가 연결돼 있으면 -Serial로 테스트 기기를 지정한다. SDK 경로는 Android Studio 또는 커밋하지 않는 Frontend/local.properties의 sdk.dir에 설정한다. 이 빌드는 USB 연결 중에 PC 서버를 사용하는 테스트 APK다.

## Device checks

1. Log in and open the menu. The old personalized-guidance menu must be absent.
2. Open the shared route improvement participation setting. Opting in or out must not interrupt guidance.
3. Complete a supported task and confirm the actual setting changed in the requested direction.
4. Check that runtime evidence links the guide, action and fresh result observation. Waiting and uncorrelated actions must not count as failures.
5. Run Agent 2 analysis against a disposable test database, then test a reviewed manifest with `propose-routes`. Insufficient independent device holdout must yield NEEDS_DATA.
6. Withdraw participation and verify unpublished cross-user analysis is invalidated. Accounts and approved graph versions must remain usable.
7. A route-priority release must affect new sessions while existing sessions stay pinned to their original graph.

Do not change the APK or apply the deletion migration during another person's active device test. Repository changes do not update an installed APK. The Render blueprint uses `develop` with `autoDeployTrigger: checksPass`; confirm the actual service's environment and deployment status separately.

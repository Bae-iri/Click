# AndroidControl로 삼성 설정·쿠팡·정부24 Task Graph 정답을 확보할 수 있는가

검토일: 2026-09-20. [초기 적합성 검토](2026-09-20-android-control-data-review.md)에 이어 앱별 실제 수록 범위를 조사했다. 이번 질문의 대상은 OCR 성능이 아니라 **MVP 앱의 상태·행동·전이 경로를 가진 정답 Task Graph**다.

**결론: AndroidControl을 이 세 앱의 정답 Graph 확보를 위한 주 데이터로 선택하는 것은 적절하지 않다. 삼성 설정은 Pixel 설정의 일부 경로를 설계 참고로 쓸 수 있지만 One UI 정답은 자체 검증해야 한다. 쿠팡과 정부24는 조사한 전체 메타데이터·AX에서 해당 앱 시연을 찾지 못했다.**

## 확인 범위와 한계

1. [공식 splits.json](https://storage.googleapis.com/gresearch/android_control/splits.json)의 episode ID 15,283개를 기준 목록으로 사용했다.
2. 공개 [파싱본](https://huggingface.co/datasets/HarrytheOrange/parsed_AndroidControl)의 `parsed_android_control.jsonl` 전체를 읽었다. 고유 episode ID 15,283개가 공식 목록과 정확히 일치했고 누락·추가·중복 ID가 없었다.
3. 같은 파싱본의 `all_forest_dict.zip` 전체를 조사했다. 관측 99,131개, window 211,949개, node 21,825,664개의 실제 `package_name`을 집계했다. 메타데이터가 참조하는 관측과 ZIP의 관측 사이에 누락·추가가 없었고 파싱 오류도 없었다. 서로 다른 패키지 877개에는 system UI 등의 패키지도 포함되므로 논문의 앱 833개와 같은 지표는 아니다.
4. 두 다운로드 파일의 SHA-256이 배포 저장소의 LFS hash와 일치했다. ZIP을 파일 시스템에 풀거나 외부 코드를 실행하지 않고, class/global/persistent 참조를 차단한 데이터 전용 pickle reader로 읽었다.
5. 공식 GCS 원본 네 shard의 앞부분 총 112 MiB에서 **43개 episode**를 읽어 교차 확인했다. 행동, 단계 지시문, 스크린샷 수, AX 패키지 집합이 파싱본과 모두 일치했다.

**공식 원본 약 49.93 GB 전체를 다시 읽은 결과는 아니다.** 전체 앱 수록 여부는 공식 ID와 대응되는 공개 파싱본 전수 조사이며, 원본 교차 확인은 43개 시연에 한정된다. 이 한계를 포함한 [기계 판독 검증 결과](evidence/android-control-mvp-coverage.json)를 남겼다.

파싱본의 `goal`에는 잘림·escape 변형이 있었다. 교차 확인 43건 중 goal 완전 일치는 0건, 원문 앞부분과 일치는 41건이었다. AX 파싱본에는 원본 `text` 등 일부 속성도 빠져 있다. 따라서 앱 수록 여부 조사에는 사용하되, 추후 Graph 생성 원문으로 채택할 자료는 공식 TFRecord에서 다시 추출해야 한다. 목표 문자열만으로 부재를 판단하지 않고 행동·단계 지시문·패키지를 함께 조사했다.

## 앱별 결과

| MVP 대상 | 실제 확인 결과 | 정답 Task Graph 확보 판단 |
| --- | --- | --- |
| 삼성 설정 앱 | `com.android.settings`가 169개 episode, 663개 관측에서 등장. 공식 수집 환경은 Pixel이며 One UI 설정 시연 근거는 없음 | Pixel 설정의 공통 작업·경로는 후보 설계 참고. 삼성의 메뉴 계층·상태·selector·조건은 실제 Galaxy에서 수집·검수해야 함 |
| 쿠팡 | `com.coupang.mobile` 0 episode. `coupang` 패키지 검색과 쿠팡/Coupang/酷澎 목표·단계·행동 검색도 0 | 쿠팡 상품 탐색·옵션·장바구니·구매 안내의 정답 Graph를 이 데이터에서 확보할 수 없음 |
| 정부24 | `kr.go.minwon.m` 0 episode. 정부24/민원24/Gov24/Government24/Minwon 검색과 관련 패키지 검색도 0 | 문서 선택·발급 안내·인증 전후 분기의 정답 Graph를 이 데이터에서 확보할 수 없음 |

패키지 식별은 앱 배포자의 [쿠팡 Google Play 페이지](https://play.google.com/store/apps/details?id=com.coupang.mobile)와 [정부24 Google Play 페이지](https://play.google.com/store/apps/details?id=kr.go.minwon.m)로 확인했다. Pixel 수집 환경은 [원 논문 §3.1](https://arxiv.org/html/2406.03679v6#S3.SS1)에 명시되어 있다.

`com.android.settings`는 제조사를 판별하는 키가 아니다. 따라서 그 패키지가 169개 episode에 등장한다는 사실을 삼성 설정 시연 169개로 해석하지 않았다. Samsung 관련 패키지로 발견된 것은 `com.samsung.ecomm.global.in` 2개 episode와 `com.sec.penup` 15개 episode이며, One UI 설정 앱의 증거가 아니다.

169건에는 Gmail 계정 제거, 앱 알림·위치 권한 변경 과정에서 설정 앱을 잠시 방문한 시연도 포함된다. **169개 고유 설정 task나 169개 완성 Graph라는 뜻이 아니다.**

## 설정 작업에서 실제 참고할 수 있는 경로

아래는 해당 episode의 단계 지시문을 읽고 요약한 경로다. One UI에서 확인한 경로가 아니다. 반복 스크롤·뒤로 가기는 가독성을 위해 묶었다.

| episode ID | 작업 | 관찰된 경로 요약 |
| --- | --- | --- |
| 45 | 위치 사용 및 Google 앱 위치 권한 | Location → Use location → Google → Allow all the time |
| 272 | 빌드 번호 확인 | Settings → About phone → 아래로 스크롤하여 빌드 번호 확인 |
| 4326 | Wi-Fi 끄기 | Settings → Network & internet → Internet → Wi-Fi toggle |
| 9148 | 밝기·화면 꺼짐·야간 모드 | Settings → Display → Brightness level / Screen timeout / Night Light |
| 10643 | 자동 날짜·시간 켜기 | Settings → System → Date & time → 자동 설정 toggle |

episode 45는 공식 원본에서 경로를 재확인했고 AX에 `Pixel 5a` 모델 표시가 있었다. 나머지 위 경로는 전수 조사한 파싱 메타데이터에서 확인한 사례다.

현재 Click의 구체적인 [MVP-01 목표](../../AI-Agent/goal_catalog/mvp_goals.json)는 시스템 글자 크기 확대다. 전체 메타데이터에 font/text-size/글자/글꼴/폰트 관련 검색 결과는 67개 episode였지만, **설정 패키지 등장 episode와의 교집합은 0개**였다. 설정 관련 169개 목표에서도 시스템 글자 크기 변경 시연을 찾지 못했다. 이는 키워드·패키지 및 목표 검토에 근거한 결과이며 모든 가능한 표현에 대한 수학적인 부재 증명은 아니다.

예를 들어 episode 34는 공식 원본에서도 **Google Play Books 내부 글자 크기** 변경이었다. 이 작업을 휴대폰 시스템 글자 크기 확대 정답으로 재분류하면 잘못된 Graph가 된다. 삼성은 실제 기기의 상태와 함께 [공식 지원 문서의 디스플레이·글자 크기 안내](https://www.samsungsvc.co.kr/solution/615123)를 참고하여 자체 경로를 검수하는 것이 적절하다.

공식 행동 목록에 별도 `drag`가 없다고 해서 슬라이더 조작 시연 전체가 없다는 뜻도 아니다. episode 751·9148에서는 슬라이더를 오른쪽으로 움직이라는 지시가 `scroll: left`로 기록되어 있었다. 따라서 방향 문자열만 보고 우리 `DRAG`/`SCROLL` 전이를 자동 생성하면 안 된다. 화면 변화와 지시문을 함께 확인해야 한다.

## 쿠팡·정부24에 다른 앱 경로를 대신 쓸 수 있는가

쇼핑 앱 사례는 있다. 이번 패키지 집계에서 Amazon Shopping은 611개, Flipkart는 353개 episode에 등장했다. 이는 경로 추출·병합 방법을 개발하는 보조 자료가 될 수 있다. 그러나 Amazon의 검색·장바구니 경로를 쿠팡의 정답으로 바꾸면 쿠팡의 화면·대상·분기 증거가 사라진다. 이 경우 얻는 것은 추상적인 구매 흐름 후보이며 쿠팡 정답 Graph가 아니다.

정부24는 해당 앱의 경로 근거를 찾지 못했다. 다른 앱의 일반적인 검색·폼 입력을 조합해도 실제 정부24의 문서 선택, 인증, 신청 조건, 결과 화면을 검증할 수 없다.

## MVP 데이터 확보 방향

1. **삼성 설정:** 실제 지원 Galaxy/One UI/한국어 환경에서 글자 크기 확대를 먼저 수집한다. 메뉴 화면·AX와 수행 행동을 연결하고 같은 작업의 검색 진입·일반 메뉴 진입 등 지원할 경로를 검수한다. 버전이 다른 경로는 별도 variant로 유지한다.
2. **쿠팡:** README의 상품 구매 안내 범위에서 구체적인 대표 작업을 정하고, 실제 쿠팡 화면으로 검색·상품 선택·옵션·장바구니/주문서 등의 상태와 전이를 수집한다. 어떤 단계까지 지원하는지 정한 뒤 Graph의 완료 상태를 붙인다.
3. **정부24:** 발급 안내할 문서 종류와 로그인/인증 방식부터 특정하고 해당 실제 앱에서 경로를 수집한다. 인증 전후·지원 불가·취소 등 실제로 안내할 분기를 포함한다.

각 사례는 목표·앱/OS 버전·언어·행동 전후 관측·대상·행동·전이 결과·검수 이력을 연결한다. 기존 정책대로 결제·인증 등 사용자가 수행할 단계는 사용자 수행 상태로 모델링한다. 이번 조사에서 실제 계정 조작·구매·서류 신청은 수행하지 않았다.

AndroidControl은 Graph 추출 방법 자체를 개발하는 보조 데이터로 남길 수 있다. **세 MVP 앱의 정답 Graph 구축은 해당 앱의 실제 시연 수집을 중심으로 계획해야 한다.** 초기 검토의 이용 조건 충돌도 해소되지 않았지만, 그 문제를 해결하더라도 이번에 확인한 앱·작업 범위의 불일치는 남는다.

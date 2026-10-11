# AndroidControl의 Click 활용 적합성 재검토

검토일: 2026-09-20. 대상: [공식 AndroidControl 저장소](https://github.com/google-research/google-research/tree/master/android_control), 공개 원본 일부, 현재 Click 코드.

후속 확인: [삼성 설정·쿠팡·정부24의 실제 수록 범위 조사](2026-09-20-android-control-mvp-app-coverage.md)에서 공식 ID와 대응되는 파싱본 15,283개 episode 및 AX 관측 99,131개 전체를 확인했다. 세 MVP 앱의 정답 Graph를 확보하는 주 데이터로는 적절하지 않다고 판단했다. 아래의 4개 시연 검증과 미확인 항목은 첫 검토 시점의 기록이며 앱별 최종 판단은 후속 문서를 따른다.

**판정: 다운로드·파싱은 실제 가능하다. 화면의 안내 대상을 찾는 기능에 보조 데이터로 활용할 수 있지만, Click의 전체 기능을 검증하는 정답 데이터로 바로 사용할 수는 없다. 상업적 활용 조건도 공식 자료 간 충돌이 있어 확인이 필요하다.**

## 실제 파일 확인

공식 GCS에서 인증 없이 목록·split 파일과 첫 shard의 앞 16 MiB를 받았다. 전체 데이터 다운로드, 모델 학습, ML Kit OCR 실행, Click 런타임 연결은 하지 않았다.

- 데이터 shard 20개, 합계 **49,930,232,975 bytes**: 약 49.93 GB / 46.50 GiB. 압축 해제 후 필요한 용량은 별도다.
- 16 MiB 안의 완전한 TFRecord **4개**, 스크린샷/AX 관측 **18개**, 행동 **14개**를 읽었다. 뒤의 불완전한 레코드는 제외했다.
- 완전한 레코드의 길이·본문 CRC32C, 공식 protobuf를 이용한 Example/AX 파싱, PNG 무결성·화면 크기, 관측/행동/지시문의 개수 관계를 확인했다.
- 관측에 포함된 AX node는 합계 6,070개다. 이미지 18장은 모두 1080×2400이었다.
- 실제 split 파일은 train 13,603 / validation 137 / test 1,543이며, 합계 15,283개 ID에 중복이 없었다. 논문 표의 train 13,604와 다르므로 importer는 논문 숫자를 하드코딩하지 말고 배포 파일을 기준으로 해야 한다.
- 이 표본에 설정 앱·글자 크기 목표는 없었다. 첫 shard의 작은 편의 표본이므로 **전체 데이터에 해당 작업이 없다는 의미가 아니다. 전체 글자 크기 작업 수와 한국어·Samsung 화면 비율은 확인하지 않았다.**

원문 텍스트나 이미지를 넣지 않은 [검증 메타데이터](evidence/android-control-sample-summary.json)에 URL, byte range, SHA-256, 필드, 개수, 검사 결과를 남겼다. 이는 파일 구조 검증이며 기능 정확도 측정은 아니다.

## 무엇이 정답으로 제공되는가

공개 형식은 목표 `goal`, 단계 지시문 `step_instructions`, 화면 PNG, AX forest, 화면 크기, JSON 행동을 제공한다. 행동은 관측 i와 i+1 사이에 대응한다. 클릭은 좌표이며 `target_node_id`가 아니다. `drag`는 공식 행동 목록에 없다. [공식 형식·행동 명세](https://github.com/google-research/google-research/tree/master/android_control)

실제 표본의 최상위 필드는 위 명세의 8개뿐이었다. Click의 `state_id`, `transition_id`, `guide_command_id`, `completion_evidence`, 마스킹 영역 라벨은 없었고, 별도 episode 성공 상태 필드도 표본에는 없었다. 논문에서 사용한 종료 라벨을 공개 파일의 필드라고 가정해서는 안 된다.

좌표를 AX node로 연결하는 것도 변환 규칙이 필요하다. 표본의 클릭 4건에 대해 ‘좌표를 포함하는 visible + clickable/long-clickable node’를 찾으면 후보 수가 각각 1, 3, 4, 0개였다. 이 휴리스틱 결과가 곧 잘못된 정답이나 AX 누락을 뜻하지는 않지만, node 선택이 항상 일대일로 결정되지 않음을 보여 준다. 부모·자식 중첩, 클릭 영역, window를 확인해 검수해야 한다.

## Click 기능별 판단

아래는 공개 스키마와 현재 구현을 비교한 설계 판단이다.

| Click 기능 | 활용 가능성 | 추가로 필요한 작업/데이터 |
| --- | --- | --- |
| AX 정규화·화면의 안내 대상 찾기 | 높음: text, resource ID, class, bounds, 행동 좌표 활용 | forest/window를 우리 노드 계약으로 변환, 역할 매핑, 클릭 대상 검수 |
| 현재 화면에서 다음 안내 선택 | 부분적: 목표·행동 시퀀스 활용 | 지원 goal만 선별, 우리 state/transition 라벨 추가, 동등한 대안 경로 허용 |
| AX 부족 시 Vision/OCR 메뉴 위치 찾기 | 부분적: 동일 관측의 이미지와 AX를 비교할 수 있음 | 동일 ML Kit OCR 실행 환경, 화면 글자/영역 수동 정답, 실제 AX 누락 사례 |
| 글자 크기 확대 완료 판정 | 현재 요구를 직접 충족하지 못함 | 동일 slider의 조작 전후 AX 값, 연결된 실제 사용자 조작 이벤트, 변화 없음/오조작 사례 |
| 개인정보 마스킹·누락 탐지 | 이미지 입력 사례로만 활용 | PII 종류·영역 정답, AX 민감 필드 누락, OCR 오인식, 마스킹 실패 시 결과 차단 테스트 |
| 매회 Vision 동의·만료·재사용 차단 | 정답 데이터 없음 | Click FE/BE 통합 테스트, 실제 단말 동의·취소·화면 변경 시나리오 |
| 한국어·Samsung 설정·고령자 안내 | 해당 품질을 보증하지 않음 | 목표 단말·언어별 수집과 사용자 안내 이해도 검증 |
| 실패 복구·재계획 | 부족 | 잘못 누름, 미반응, 뒤로 가기, 팝업, 지연 등 Click 자체 실패 시연 |

현재 [MVP-01 graph](../../AI-Agent/src/ai_agent/runtime/tasks/mvp_01_font_size_increase.py)는 글자 크기 확대와 정해진 상태·경로를 사용한다. 특히 S2는 한국어 `디스플레이`를 필수로 요구하므로 영어 Settings 화면을 변환만 해 넣어도 전체 graph가 동작한다고 볼 수 없다. 단순 번역은 실제 한국어 화면의 검증을 대체하지 않는다.

[완료 검증 코드](../../AI-Agent/src/ai_agent/runtime/verification.py)는 안내와 연결된 사용자 행동, 동일 대상·window, 이전/현재 값을 요구한다. 공개 [AX node protobuf](https://github.com/google-deepmind/android_env/blob/main/android_env/proto/a11y/android_accessibility_node_info.proto)에는 구조화된 RangeInfo 현재값·최소값·최대값 필드가 없다. 텍스트에 수치가 보일 수는 있지만 이를 우리 완료 증거로 자동 승격하면 안 된다.

[MVP-01 약한 라벨 생성기](../../AI-Agent/src/ai_agent/graph_proposal/labeling/mvp01.py) 역시 직접적인 `accessibility_range` 증거가 없으면 완료를 unknown으로 둔다. AndroidControl 시연의 마지막 화면을 임의로 성공 처리하거나 조작 시간·range 값을 만들어 이 제한을 우회하면 안 된다.

현재 [TrainingDatasetBuilder](../../AI-Agent/src/ai_agent/graph_proposal/datasets/dataset_builder.py)는 빈 samples를 반환하는 뼈대다. **2026-10-10 정정:** [AndroidControl importer](../../AI-Agent/src/ai_agent/graph_proposal/datasets/android_control.py)는 존재하며 goal/step_instruction/action_type을 변환한다. screenshot·AX·좌표와 실제 완료 gold를 변환하거나 모델 학습을 수행하는 구현은 아니다. 공식 split 호환성은 [후속 감사](./evidence/2026-10-10-public-evaluation-contracts.json)에서 확인했다. 현행 [Vision 경로](../ai-agent-vision-fallback.md)는 기기 내 OCR과 설정 메뉴 허용 목록을 사용하므로 이 데이터로 학습해야 실행되는 구조도 아니다. 일반 앱 데이터를 적용하려고 현행 개인정보 제한을 완화할 이유는 없다.

## 활용한다면 권장하는 방식

외부 데이터는 FE → BE → AI1 운영 요청에 넣는 입력이 아니라 **오프라인 변환·평가 자산**으로 다룬다.

1. 이용 조건 확인 후 원본 버전·hash·공식 split을 고정한다. 설정 관련 목표와 실제 package를 함께 확인해 지원 작업을 선별하고 수량을 조사한다.
2. `(현재 관측, goal, 과거 행동)`을 입력, `다음 행동/검수된 대상 영역`을 참조 정답으로 만든다. 다음 화면·현재 정답 행동을 모델 입력에 넣지 않는다. 단계 지시문까지 주는 대상 찾기 평가와 goal만 주는 다음 안내 평가는 구분한다.
3. AX의 class/속성/좌표를 우리 ScreenSnapshot 계약으로 변환한다. 생성한 observation/node ID는 오프라인 식별자라고 명시하고 실제 사용자 이벤트 ID·동의 증거로 사용하지 않는다. 원본 행동, 자동 파생 라벨, 사람이 검수한 정답의 출처를 구분한다.
4. Vision 평가는 ‘AX 정상’, ‘실제 AX 대상 누락’, ‘인위적으로 AX 제거’ 집합을 나눠 수행한다. AX를 지운 실험만으로 실제 fallback 품질을 주장하지 않는다. 기존 tree는 평가용 정답 후보로 분리하고 fallback 입력에 유출하지 않는다.
5. 이미지에서 우리 FE와 같은 OCR·마스킹을 거친 결과만으로 대상 위치 정확도, 인식 불가 비율, 마스킹 후 target 유지율을 잰다. AX text/content description은 보이지 않는 글자도 포함하므로 완전한 OCR 정답이 아니다. 개인정보 누락률은 별도로 만든 PII 영역 정답으로 평가한다.
6. episode 단위로 split을 유지하고 test로 허용 목록·규칙을 튜닝하지 않는다. 공식 test의 하위 집합끼리는 중복될 수 있으므로 합산 시 중복을 제거한다. 한국어·Samsung 실기기 평가 집합을 따로 유지한다.
7. Click의 오프라인 graph 개선 후보에만 반영하고 자체 회귀·실기기 검증 후 채택한다. 인간 시연 행동을 자동 실행 권한이나 Vision 동의로 취급하지 않는다.

논문의 실험 전처리는 클릭 위치에 연결할 AX 요소가 없는 일부 단계를 제외한다. **우리 목적은 AX 부족 대응이므로 그런 사례를 제거한 가공본만 선택하면 핵심 평가 대상이 빠질 수 있다.** 또한 논문의 단계 행동 정확도를 Click의 실제 완료율로 해석하면 안 된다. [원 논문 D.1·D.3](https://arxiv.org/html/2406.03679v6)

후속 연구도 AndroidControl의 모호한 지시와 사실 오류를 보고했다. 이는 원본을 전부 사용할 수 없다는 뜻이 아니라, 제품의 최종 정답으로 채택할 부분은 검수해야 한다는 근거다. [AndroidControl-Curated 연구, 2025](https://arxiv.org/abs/2510.18488)

## 이용 조건에서 해결되지 않은 부분

공식 자료의 표기가 일치하지 않는다.

| 공식 자료 | 기재 내용 |
| --- | --- |
| [AndroidControl README의 License](https://github.com/google-research/google-research/tree/master/android_control#license) | Apache 2.0 문구 |
| [google-research 루트 README](https://github.com/google-research/google-research/blob/master/README.md) | 데이터셋 CC BY 4.0, 소스 Apache 2.0 |
| [저자들이 제출한 NeurIPS 보충자료, p.4 §1.1.6](https://papers.nips.cc/paper_files/paper/2024/file/a79f3ef3b445fd4659f44648f7ea8ffd-Supplemental-Datasets_and_Benchmarks_Track.pdf) | 비상업적 용도로만 사용한다는 조건과 Apache 2.0이 함께 기재됨 |

현재 확인한 자료만으로 어떤 조건이 최종적으로 적용되는지 확정하지 않는다. 공개 다운로드 가능성을 상업적 학습·제품 활용·재배포 허용의 증거로 삼지 않는다. 해당 범위는 배포자에게 조건을 확인한 뒤 결정해야 한다. 이번에는 외부 문의를 보내거나 제품 학습에 사용하지 않았다.

## 현재 우선순위

Click의 MVP에는 **한국어 목표 단말의 글자 크기 조절 시연 + 실제 값 변화/미변화 + AX 누락 + 마스킹·동의 실패 사례**가 우선이다. AndroidControl은 이용 조건을 해결한 뒤 일반적인 화면 대상 찾기와 외부 화면에 대한 평가를 보강하는 보조 데이터로 채택하는 것이 적절하다. 글자 크기 작업의 충분한 수량이나 실기기 완료 판정을 이 데이터가 이미 해결한다고 주장하지 않는다.

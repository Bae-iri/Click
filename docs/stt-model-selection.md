# STT 모델 선택과 평가 이력

2026-10-27 사용자 요청으로 현재 구현을 **Whisper large-v3 + faster-whisper 자체 서버**로 전환했다. worker·Backend 모델 계약·Android 업로드 검증을 함께 변경했으며 [서버 설정](../Backend/inference/README.md)을 따른다. 실제 가중치 추론·배포·한국어 품질 평가는 별도 검증 단계다.

사용자 확정 범위는 **원음의 기기 내부 또는 우리 서버 처리까지 허용**이다. 외부 사업자의 STT 추론 API 전송은 이 범위에 포함하지 않는다. 아래 후보 비교는 2026-10-11 검토 이력이며 현재 운영 모델 선택과 성능 실측 결과를 구분한다.

## 1. 현재 입력 경로

| 구간 | 이번 변경 | 확인 상태 |
| --- | --- | --- |
| 메인 오버레이와 튜토리얼 | PCM WAV → 인증된 Backend → 자체 Whisper worker | 일반 Android 원격 인식과 외부 STT fallback 제거 |
| 구버전 서버 연결 | 업로드 전에 자체 STT provider와 모델 계약 확인 | 외부 STT 계약이면 업로드 차단 |
| Backend | `SELF_HOSTED_STT_URL`과 token으로 지정한 우리 서버만 호출 | 설정이 없으면 503, 외부 API로 재시도하지 않음 |
| Whisper worker | CTranslate2 로컬 가중치·한국어 전사·VAD, 메모리 내 처리 | 실제 가중치·GPU 추론은 미실행 |

[실행 방법과 배포 순서](../Backend/inference/README.md)에 서버 설정·제한·검증 단계를 정리했다. 기존 배포의 OpenAI 경로를 새 코드에 남겨두지 않았지만, 운영 서버가 이번 변경으로 배포됐다는 의미는 아니다. 프록시·APM·운영 네트워크 경계와 모델 품질은 실제 서버에서 검증해야 한다.

## 2. 선정 방식

개인정보는 가중치 점수가 아니라 **필수 통과 조건**이다. 조건을 통과한 모델끼리 한국어 품질을 먼저 비교하고, 그 다음 지연·메모리·비용을 비교한다. 자체 서버 모델의 한국어 성능을 측정하기 전에는 최종 우승 모델을 확정하지 않는다.

필수 조건:

- 추론은 기기 또는 우리가 통제하는 서버에서 수행. 원음·전사문을 외부 STT, LLM 후처리, 원격 데모, 오류 수집기에 전달하지 않음.
- 모델은 배포 준비 때 다운로드해 revision·SHA-256을 고정. 실제 음성 처리 중 모델 저장소 접속·텔레메트리·임의 외부 egress를 차단한 상태에서도 동작해야 함.
- 원음은 요청 처리에 필요한 동안만 유지. 정상·오류·취소·시간 초과 후 임시파일 정리, 원음/전사문 body 로그·APM·crash dump 제외를 검증. 메모리 참조 해제를 즉시 물리적 소거로 표현하지 않음.
- 인증·전송 암호화·서버 접근 통제·요청 크기/시간 제한 적용. 입력 발화에 타인 정보가 섞인 사례도 점검.
- 일반 서비스 처리를 학습·평가 수집 동의로 재사용하지 않음. 평가용 원음은 별도 동의·보존기간·삭제 경로가 있는 제한 저장소에만 보관.
- 실패 시 재시도/텍스트 입력을 제공. 외부 API로 자동 전환하지 않음. 전사된 텍스트의 이후 저장·분류 경계도 별도로 검사.

연결 코드에서 외부 STT 경로를 제거했다. 위 개인정보 조건의 전체 통과 여부는 모델 서버 배포, 운영 로그·egress 검증까지 완료한 뒤 판단한다.

## 3. 후보와 우선 검증 대상

아래는 공개된 공식 가중치와 실행 방식을 확인한 후보 목록이다. **우리 한국어 발화에서 측정한 순위가 아니다.** 서버 GPU·메모리와 동시 사용자 수는 아직 확정되지 않았다.

| 구분 | 모델·실행 방식 | 선정 이유 | 확인할 한계·조건 |
| --- | --- | --- | --- |
| 1차 한국어 품질 비교 | **Qwen3-ASR-1.7B**, 우리 서버 `qwen-asr`/Transformers 또는 vLLM | 공식 가중치, 한국어 지원, offline·streaming, Apache-2.0 | 한국어 짧은 명령·고령 화자·부정어를 자체 검증. 1.7B 실행 자원과 첫 응답 지연 측정. DashScope 경로는 사용하지 않음 |
| 1차 기준 모델 | **Whisper large-v3**, 우리 서버 `faster-whisper`/CTranslate2 | 공개 다국어 모델을 자체 운영하는 비교 기준, MIT | 무음·잡음에서 없는 문장 생성, 방향·수치 오류, 지연 측정. 한국어 지정·transcribe 모드 사용 |
| 효율 비교 | **Whisper large-v3-turbo** 및 **Qwen3-ASR-0.6B**, 우리 서버 | 같은 평가셋에서 품질을 유지하면서 자원을 줄일 수 있는지 검증 | 한국어 품질 통과 후 선택. 공개 속도 수치를 우리 서버 성능으로 전용하지 않음 |
| CPU·온디바이스 비교 | **Korean Zipformer**, `sherpa-onnx`의 offline 2024-06-24 / streaming 2024-06-16 | 한국어 전용 공개 모델, CPU 실행·INT8 경로 제공 | 가중치·변환본·데이터 이용조건을 각각 확인. 모델별 품질·모바일 메모리/배터리는 미측정 |
| 예비 후보 | **SenseVoiceSmall**, 우리 서버 또는 검증한 로컬 실행기 | 한국어 지원 공개 가중치 | 소스 MIT와 가중치의 별도 모델 라이선스를 혼동하지 않음. 불필요한 감정·화자 속성은 수집하지 않음 |

공식 근거: [Qwen3-ASR 모델 카드](https://huggingface.co/Qwen/Qwen3-ASR-1.7B), [실행 저장소](https://github.com/QwenLM/Qwen3-ASR), [Whisper large-v3](https://huggingface.co/openai/whisper-large-v3), [turbo](https://huggingface.co/openai/whisper-large-v3-turbo), [faster-whisper](https://github.com/SYSTRAN/faster-whisper), [Korean Zipformer offline](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/zipformer-transducer-models.html#sherpa-onnx-zipformer-korean-2024-06-24-korean), [streaming 모델 목록](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/index.html), [SenseVoice 공식 카드](https://huggingface.co/FunAudioLLM/SenseVoiceSmall), [가중치 라이선스 안내](https://github.com/QwenAudio/SenseVoice#license).

온디바이스 제품화가 필요하면 Whisper multilingual small/base + `whisper.cpp`도 자원 비교 대상으로 둘 수 있다. 영어 전용 `.en` 모델은 제외한다. 기기별 품질·발열·배터리 실측 없이 서버 후보보다 우수하다고 판정하지 않는다. [whisper.cpp](https://github.com/ggml-org/whisper.cpp)

**2026-10-11 비교 계획:** 1차 비교 대상을 Qwen3-ASR-1.7B와 Whisper large-v3로 정한다. 동일 한국어 gold에서 비교한 뒤, turbo/0.6B가 품질 기준을 통과하면 효율 후보로 승격한다. CPU 또는 원음 무전송 요구에는 Korean Zipformer를 별도 검증한다. 새 모델이라는 이유나 서로 다른 benchmark의 점수만으로 한국어 1위를 선언하지 않는다.

### 3.1 Qwen3-ASR 대 Whisper: 장점·단점과 선택 조건

여기서 Qwen-3는 범용 텍스트 Qwen3가 아니라 **Qwen3-ASR**를 뜻한다. Whisper도 외부 API가 아니라 공개 가중치를 우리 서버에서 실행하는 경우다.

| 기준 | Qwen3-ASR-1.7B / 0.6B | Whisper large-v3 / large-v3-turbo | Click에서의 판단 |
| --- | --- | --- | --- |
| 개인정보 | 가중치 다운로드 후 자체 추론 가능 | 가중치 다운로드 후 자체 추론 가능 | 두 모델 모두 후보 자격이 있다. 제작사 국적·브랜드로 개인정보 우열을 정하지 않고 외부 전송·로그·fallback을 검사 |
| 한국어 품질 | 아래 한국어 논문에서 1.7B가 large-v3보다 낮은 오류율. 우선 실험할 근거가 있음 | 공개 비교 기준으로 적합. 한국어 전반에서 Qwen보다 나쁘다는 근거는 아님 | 동일 공개 한국어 test와 실제 명령에서 재현해야 최종 선택 가능 |
| 규모와 효율 | 1.7B 품질 후보, 0.6B 효율 후보 | large-v3 약 1.55B, turbo 약 0.81B | 파라미터 수만으로 GPU 메모리·초당 처리량·비용을 비교하지 않음 |
| 스트리밍 | 공식 toolkit에 streaming 경로 제공. 현재 문서상 vLLM 경로이며 batch·timestamp 제한 존재 | 기본 모델 위에 청크·VAD·확정 규칙을 추가하는 구현이 필요 | 현재 앱은 녹음 종료 뒤 WAV 업로드라 모델 교체만으로 실시간 전사가 되지 않음 |
| 자체 서버 운영 | Transformers/vLLM 기반으로 구성. 새로운 실행 환경의 의존성·동시성·취소를 검증해야 함 | faster-whisper의 CTranslate2·양자화·batch/VAD 경로 활용 가능 | 운영 재현성과 경량화 선택지는 Whisper를 기준선으로 두는 이유 |
| CPU·모바일 | 공식 주 경로는 GPU 기반 toolkit. 우리 Android 실행·배터리 미검증 | whisper.cpp에 CPU/Metal/Android 예제와 양자화 경로 존재 | 서버 GPU가 없다면 Whisper 계열의 실행기 비교를 먼저 할 실무적 근거가 있음. large 모델의 휴대폰 적합성을 보장하지 않음 |
| 오류 양상 | 자기회귀 전사이므로 누락·반복·부정어 변경·배경 발화 오인식을 측정해야 함 | 공식 카드가 없는 발화 생성·반복 및 언어/화자별 편차를 명시 | Qwen에 같은 경고 문구가 없다고 환각이 없다고 판정하지 않음 |
| 시간표시 | 별도 ForcedAligner 사용 시 추가 모델·연산 발생 | segment timestamp 및 실행기별 word timestamp 경로 존재 | 짧은 명령 인식에는 정밀 alignment를 기본 활성화할 이유가 적음 |
| 라이선스 | 공식 배포 Apache-2.0 | 공식 Whisper 코드·가중치 MIT | 배포에 쓰는 변환본·실행기 버전까지 별도 기록 |
| 미확인 영역 | 한국어 고령 화자·긴 쉼·취소/부정 명령·우리 서버 p95 | 같은 영역, 특히 turbo로 바꿀 때 한국어 회귀 | 어느 쪽도 현재 제품 품질 PASS가 아님 |

근거: [Qwen toolkit](https://github.com/QwenLM/Qwen3-ASR), [Qwen 기술보고서](https://arxiv.org/html/2601.21337v2), [Whisper 공식 모델 표](https://github.com/openai/whisper#available-models-and-languages), [Whisper 한계](https://huggingface.co/openai/whisper-large-v3#performance-and-limitations), [faster-whisper](https://github.com/SYSTRAN/faster-whisper), [whisper.cpp](https://github.com/ggml-org/whisper.cpp), [turbo 모델 카드](https://huggingface.co/openai/whisper-large-v3-turbo).

### 3.2 외부 한국어 비교 수치의 의미 재검증

2026-09-30 한국음향학회 논문의 §5.4는 동일 KsponSpeech 음성·참조 전사·정규화로 비교한 **공백 제거 CER**를 보고한다. 낮을수록 좋다.

| 평가셋 | Qwen3-ASR-1.7B | Whisper large-v3 | 절대 차이 |
| --- | --- | --- | --- |
| KsponSpeech eval_clean | 12.76% | 13.89% | Qwen이 1.13%p 낮음 |
| KsponSpeech eval_other | 12.60% | 13.62% | Qwen이 1.02%p 낮음 |

이는 외부 연구자의 결과이며 이번 작업의 직접 측정값이 아니다. 두 eval 셋에서 최적 decoding 설정을 골랐으므로, 별도 개발셋에서 설정을 고정한 뒤 처음 보는 test에 적용한 결과와 구분해야 한다. 한국어 전체·고령자 명령 우위나 통계적 유의성을 이 표만으로 선언하지 않는다. 실행기·정밀도·배치 방식도 달라 속도 우위 근거로 전용하지 않는다. [논문 본문 §4.2·5.4](https://www.jask.or.kr/articles/xml/wJay/)

Qwen 공식 다국어 평균도 한국어 단독 점수가 아니다. 기술보고서는 언어에 따라 CER/WER를 쓰고 언어별 값을 평균하므로, 이를 한국어 WER로 바꿔 적지 않는다. [평가 방법 §3](https://arxiv.org/html/2601.21337v2)

**2026-10-11 검토 당시 권고:** 한국어 품질 후보는 Qwen3-ASR-1.7B를 우선 시험하고 Whisper large-v3를 동일 조건 기준선으로 유지한다. 최종 결정은 [공개 데이터 중심 평가 절차](./ai-quality-evaluation.md)의 개발/test 분리를 따른다. 배포비 절감 후보 turbo/0.6B는 별도 모델로 비교한다.

### 3.3 공정한 성능·비용 비교 실험

1. 공통 트랙: 같은 원음·발화 경계·한국어 고정·전사 모드·정규화, 외부 LLM 교정 없음. 모델별 설정은 같은 개발셋에서 같은 탐색 예산으로 선택한다. test별 최적 설정을 보고하지 않는다.
2. 배포 트랙: 동일 서버에서 각 실행기의 권장 최적화를 적용하되 양자화·VAD·batch·동시성을 명시한다. 공통 트랙과 점수를 합치지 않는다.
3. 짧은 명령은 동시성 1에서 시작해 예상 운영 동시성을 추가한다. 첫 token 시간, 최종 전사 시간, RTF(추론 시간/음성 길이), peak 메모리, timeout/OOM·취소 성공을 분리한다. 긴 음성·고동시성 처리량을 짧은 한 요청의 응답 시간으로 해석하지 않는다.
4. 비용은 우리 인프라 요금·상시 대기·실제 사용률을 포함한 음성 1시간당 비용으로 계산한다. 품질이 통과하지 않은 저렴한 모델을 비용만으로 승격하지 않는다. GPU 사양·요금이 없어 현재 비용 순위는 미정이다.

## 4. 한국어 평가

상세 정의·정답 검수·분할·승격 기준은 [AI 품질·정답 데이터 기준](./ai-quality-evaluation.md)을 따른다.

- 동일 발화에 대해 원문 보존 CER, 정규화 CER, 어절 WER, 핵심 의미 슬롯 정확도를 함께 보고한다.
- 방향(크게/작게), 부정(켜지 마/꺼줘), 취소·중지·재개, 앱/범위(휴대폰/카카오톡), 수치·단위를 별도 평가한다. 같은 뜻으로 보이도록 오류를 후처리로 지우지 않는다.
- 고령 화자, 지역 발음, 느린 발화·긴 쉼, 작은 목소리, 주변 TV/TTS, 무음, 잡음, 한영 혼용을 포함한다. 억양·말더듬을 특정 진단으로 추정하지 않는다.
- 무음 환각률과 사용자 아닌 배경 발화가 명령으로 채택되는 비율을 별도로 기록한다.
- 버튼→녹음 준비, 발화 끝→최종 전사, 전송·대기·추론 지연을 분리해 p50/p95를 보고한다. 현재 16초 녹음 상한·1.8초 종료 무음 판정도 함께 평가한다.
- 16 kHz mono 입력, VAD, 한국어 설정, 용어 힌트, 양자화, decoder 설정, 모델 revision을 manifest에 고정한다. STT만의 비교와 실제 FE 확인 흐름의 end-to-end 비교를 구분한다.

## 5. 전환 완료 기준과 남은 작업

1. Backend의 STT 호출을 자체 서버 adapter로 대체하고 모델 준비 실패 시 명시적 오류를 반환한다.
2. 메인/튜토리얼 모두 같은 개인정보 정책을 적용한다. 튜토리얼은 검증된 on-device API 또는 우리 서버를 사용하고 일반 시스템 STT·제3자 fallback 경로를 정리한다.
3. 녹음 취소 후 네트워크 전송·대기 요청·콜백까지 종료되는지 확인한다. 현재 callback 무시만으로 이미 전송된 원음을 회수했다고 표현하지 않는다.
4. 외부 egress 차단, 로그 점검, 임시파일 정리, timeout·동시요청·무음 테스트를 통과시킨다.
5. 실제 한국어 gold 비교 보고서에 근거해 모델을 고정하고 서버 자원을 결정한다. 현재 서버에 GPU가 있다고 가정하지 않는다.

이 작업에서 완료한 것은 코드 경로 감사, 후보 선정, 평가 기준 제정이다. 모델 가중치 다운로드·한국어 음성 benchmark·서비스 모델 변경·배포는 아직 수행하지 않았다.

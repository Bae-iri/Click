# Whisper 지연 개선과 실측

## 현재 경로와 확인한 대기

앱 녹음 → Backend의 private STT 계약 확인 → WAV 업로드 → Backend → 자체 Whisper worker → 전사 결과.

worker는 faster-whisper/CTranslate2의 large-v3를 한 번 로드하고 요청마다 재사용한다. 한국어 지정, VAD, timestamp 비활성화, 자체 서버 경계도 이미 적용돼 있다. 병목 위치는 실제 서버에서 측정해야 한다.

## 이번 코드 변경

1. **발화 종료 무음 대기 1.8초 → 1초**. 설정상 800ms 단축이다. AudioRecord 버퍼·스케줄링에 따라 실제 대기는 다르다. 긴 문장이나 천천히 말하다 1초 이상 쉬는 경우에는 끝이 잘릴 수 있으므로 한국어 발화 검증이 필요하다.
2. **발화별 계약 확인을 녹음 중에 병행**. 녹음 후에는 같은 발화의 확인 결과를 기다려 사용한다. 발화 간 캐시를 공유하지 않는다. `provider=self_hosted`, `model=openai/whisper-large-v3` 검증이 실패하거나 취소되면 음성을 업로드하지 않는다.
3. **beam size 5 → 1 고정**(greedy). 짧은 명령의 디코딩 검색량을 줄이기 위한 변경이며, 한국어 정확도 향상을 주장하지 않는다. 가중치·모델 ID·provider는 바꾸지 않는다.
4. **구간 시간만 관측**. 앱 `ClickSTT` 로그에 recording_ms, contract_wait_ms, request_ms를 기록한다. worker의 `Server-Timing`에 stt_decode/stt_inference 밀리초를 제공한다. 음성·전사·파일명·토큰은 해당 측정 로그에 넣지 않는다.

앱 변경은 새 APK 설치, 추론 변경은 **별도 Whisper worker의 코드 반영·재시작**이 필요하다. Backend API만 재시작해서는 worker 설정이 바뀌지 않는다. `/health`의 device로 현재 실행 설정을 확인할 수 있다.

```dotenv
# Whisper worker 환경. 기존 모델 경로와 인증 설정은 유지한다.
# NVIDIA GPU가 준비된 서버라면:
WHISPER_DEVICE=cuda
WHISPER_COMPUTE_TYPE=float16
# CPU에서 실행해야 한다면 기존 cpu/int8 조합을 유지하고 실측한다.
```

## 무엇을 비교할지

- 서버가 CPU large-v3이면 GPU 실행 또는 turbo 모델 비교가 우선 후보다. beam 변경만으로 무거운 encoder 비용이 없어지는 것은 아니다.
- faster-whisper는 GPU float16/int8_float16, CPU int8 조합과 beam 설정에 따른 속도·메모리 차이를 설명한다. [공식 문서](https://github.com/SYSTRAN/faster-whisper)
- Whisper large-v3-turbo는 별도의 최적화 모델이다. 실제 한국어 명령 정확도와 지연을 비교한 뒤 선택해야 한다. [공식 모델 카드](https://huggingface.co/openai/whisper-large-v3-turbo)
- 현재 앱과 Backend는 large-v3 모델 ID를 엄격히 확인한다. turbo로 바꿀 때에는 승인한 가중치와 모델 ID 계약을 함께 바꿔야 한다. 가중치만 바꾸고 large-v3라고 응답하게 해서는 안 된다.
- streaming은 녹음과 추론을 더 많이 겹칠 수 있지만 현재 완성 WAV 요청과는 다른 프로토콜이 필요하다. 부분 인식 결과로 작업을 실행하지 않고 최종 발화를 확인하는 구조를 유지해야 한다.

다른 장비·긴 오디오의 공개 벤치마크를 이 앱의 한국어 응답 시간으로 환산하지 않는다.

## 측정 명령

자체 worker URL/token을 운영 서버의 비공개 환경파일에 설정한다. 아래 파일은 사용자 녹음이 아닌 저장소의 합성 한국어 발화다.

```sh
cd Backend
uv run python scripts/benchmark_stt.py \
  --env-file .env \
  --wav ../Frontend/app/src/androidTest/assets/speech/korean-font-size.wav \
  --expected-text "글자 크기 키워 주세요." \
  --runs 5 --warmups 1
```

숫자만 출력한다: 오디오 길이, upload_to_result p50/p95, worker 추론 p50, exact_matches. 녹음 종료 대기는 이 측정에 포함되지 않는다. 첫 요청 warmup은 통계에서 제외한다. 문장 일치는 공백만 무시하므로 문장부호 차이도 불일치로 계산한다.

한 문장 반복 측정은 언어 품질 평가가 아니다. “키워줘/줄여줘”, “켜줘/꺼줘”, “하지 마”, 짧은 대답, 느린 발화, 잡음 조건을 포함한 여러 발화에서 정확도와 발화 잘림을 확인해야 한다.

## 검증 상태

Android 전체 테스트 262개, STT 관련 Backend 테스트 61개와 디버그 APK 빌드가 통과했다. 계약 확인 선행·취소·미검증 서버의 업로드 차단, beam 1 고정, 수치 측정의 민감정보 비출력을 검증했다. 이 환경에는 실제 STT URL/token이 없어 가중치 추론·한국어 품질·개선 시간은 측정하지 않았다.

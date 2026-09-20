# AndroidControl 참조 데이터 준비 (AI-Agent 2)

[Google Research AndroidControl](https://github.com/google-research/google-research/blob/master/android_control/README.md)의 `images/episode_1.png`는 README 예시 이미지다. 학습 원본은 [별도 GCS 버킷](https://console.cloud.google.com/storage/browser/gresearch/android_control)의 GZIP TFRecord 파일이다. 각 episode에 목표, 단계 지시문, 행동 JSON, 화면 이미지, 접근성 트리가 있다.

원본 TFRecord와 공식 split 파일을 내려받은 후 다음을 실행한다. TensorFlow는 데이터 변환 때만 필요하다. 현재 프로젝트 기본 의존성에는 포함하지 않았다.

```powershell
cd AI-Agent
python -m pip install tensorflow
$env:PYTHONPATH = "src"
python -m ai_agent.graph_proposal.datasets.android_control --output data/android_control/prepared --official-splits data/android_control/splits.json "data/android_control/raw/android_control*"
```

공식 split 파일은 `{"train":[episode_id,...],"validation":[...],"test":[...]}` 형태로 준비한다. 공식 split을 변환할 수 없다면 `--official-splits`를 생략한다. 이 경우 episode ID의 SHA256 해시로 80/10/10 분할한다. 같은 episode의 모든 step은 같은 집합에 속한다. `test`는 최종 평가용으로 보관하고 모델 학습과 선택에는 쓰지 않는다.

이 변환 결과는 AI-Agent(2)의 **오프라인 참조 데이터**다. 목표·단계 지시문·관찰된 행동 종류를 episode 순서대로 담으며, graph 후보 분석과 평가 자료를 준비할 때 사용할 수 있다. 실제 사용자 실행 기록의 성공·실패 label은 포함하지 않는다. 화면 이미지, 접근성 트리, 좌표도 현재 변환하지 않으므로 화면 판별 모델 학습이나 AI-Agent(1)의 실시간 안내에 사용할 수 없다. 원본 데이터와 변환 결과는 Git에서 제외한다.

AI-Agent(1)의 화면 판단·안내·온라인 검증과 모델 가중치 학습은 이 작업 범위에 포함하지 않는다. AI-Agent(2)는 데이터 분리, lineage, 라벨링, graph 후보와 독립 평가를 담당한다. 기존 `models/model_trainer.py`는 후속 확장을 위한 골격으로 남아 있으며 현재 학습을 수행하지 않는다.

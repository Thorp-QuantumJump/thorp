# worker — Backtest Worker

Kafka `backtest.jobs` 토픽에서 Job을 받아 백테스트를 실행하고 결과를 PostgreSQL에 저장한다.
같은 코드를 여러 개 실행해(Consumer Group) 분산 처리한다.

```bash
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt -r requirements-dev.txt
python -m worker.main
```

- lint/format: `ruff check src tests` / `ruff format src tests`
- test: `pytest`

## baseline 러너

Kafka/DB 없이 동일한 엔진으로 Job을 순차 실행해 소요시간을 측정한다.
분산 처리 speedup 비교의 기준선(대조군)이다.

```bash
# 로컬 배선 확인 (합성 데이터, 공식 수치 아님)
OMP_NUM_THREADS=1 python -m worker.baseline --synthetic --jobs 5 --repeat 3 --profile

# 서버 공식 측정
infra/scripts/run-baseline.sh
```

측정 조건과 결과 기록은 [docs/specs/baseline-measurement.md](../docs/specs/baseline-measurement.md),
계산 정의는 [docs/specs/backtest-spec.md](../docs/specs/backtest-spec.md)를 따른다.

## 구성

| 파일 | 역할 |
|---|---|
| `main.py` | Kafka consumer 엔트리포인트 |
| `baseline.py` | 단일 프로세스 baseline 러너 (측정용) |
| `engine.py` | 체결 시뮬레이션 + 성과지표 산출 |
| `strategies/` | 전략(신호 생성만 담당) |
| `data.py` | Parquet 시세 로딩 / 합성 데이터 생성 |

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

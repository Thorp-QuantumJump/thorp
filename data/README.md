# data — KRX 데이터 파이프라인

KRX 일봉 데이터를 수집·정제해 `raw/`(원본), `processed/`(정제, Parquet) 에 저장한다.
데이터 파일은 커밋하지 않는다(.gitignore). Parquet 파티셔닝을 유지해 추후 Spark/Hadoop
도입 시 저장 포맷을 그대로 재사용한다.

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt -r requirements-dev.txt
python scripts/ingest_krx.py --start 2021-01-01 --end 2026-08-26
```

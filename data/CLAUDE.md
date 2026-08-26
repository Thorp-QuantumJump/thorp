# CLAUDE.md — data (DATA 파트)

Python 3.11 + pykrx/pandas. KRX 일봉 수집·정제 파이프라인.

## 검증 명령 (작업 후 반드시 실행)

```bash
ruff check src scripts && pytest
```

## 구조 규칙

- `src/data_pipeline/` 재사용 로직, `scripts/` 실행 진입점(argparse), `notebooks/` 탐색용(로직 두지 말 것)
- 저장: `raw/`(원본) → `processed/`(정제, **Parquet, 자산/연도 파티션**) — 데이터 파일은 커밋 금지(.gitignore)
- Parquet 파티션 구조는 추후 Spark 도입 시 그대로 읽는 전제 — 임의 변경 금지

## 규칙

- 컬럼 표준: `date, open, high, low, close, volume` (소문자, pykrx 한글 컬럼은 수집 단계에서 변환)
- 수집 스크립트는 재실행 안전(멱등)하게: 기간 재수집 시 중복 없이 덮어쓰기
- API 키는 .env(`KRX_API_KEY`)로만, 코드 하드코딩 금지

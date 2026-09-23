# CLAUDE.md — data (DATA 파트)

Python 3.11 + requests/pandas. **KRX OPEN API**(ETF 일별매매정보·KOSPI 시리즈·종목기본정보,
2010-01-04 이후) 일봉 수집·정제 파이프라인. pykrx·yfinance처럼 원칙 X가 열거하지 않은 원천은
이용 조건을 문서로 입증할 수 없으므로 쓰지 않는다(research.md 결정 1).

## 검증 명령 (작업 후 반드시 실행)

```bash
ruff check src scripts tests && pytest
```

가상환경은 `data/.venv`이며 이 파트 전용이다(`worker/.venv`를 빌려 쓰지 않는다). 처음 준비할 때는
`uv venv --python 3.11 .venv` 뒤에 `uv pip install -r requirements.txt -r requirements-dev.txt`를
실행한다. PATH의 `python`은 Microsoft Store 스텁이라 잡히지 않으므로 `.venv/Scripts`를 앞에 붙인다.

## 구조 규칙

- `src/data_pipeline/` 재사용 로직, `scripts/` 실행 진입점(argparse), `notebooks/` 탐색용(로직 두지 말 것)
- 저장: `raw/`(원천 응답 원본) → `processed/`(정제, **Parquet, 자산/연도 파티션**) →
  **PostgreSQL**(서비스 저장소) — 데이터 파일은 커밋 금지(.gitignore)
- 적재는 `raw/ → processed/ → PostgreSQL` 한 방향뿐이고, Backend·Worker가 읽는 곳은 PostgreSQL이다.
  DB 적재는 자료 버전 발행의 마지막 단계다 (research.md 결정 8)
- Parquet 파티션 구조는 추후 Spark 도입 시 그대로 읽는 전제 — 임의 변경 금지

## 규칙

- 컬럼 표준: `date, open, high, low, close, volume, value` (소문자, KRX OPEN API 한글 응답 필드는
  수집 단계에서 변환). `value`는 거래대금(원)이며 FR-015의 유동성(최근 3개월 일평균 거래대금)에 쓴다
  — `volume`은 거래량(주식 수)이라 대체할 수 없다 (research.md 결정 6)
- 수집 스크립트는 재실행 안전(멱등)하게: 기간 재수집 시 중복 없이 덮어쓰기
- 접속 정보는 .env(`KRX_API_KEY`, `DATABASE_URL`)로만, 코드 하드코딩 금지 — 이름·형식은 `.env.example` 참고

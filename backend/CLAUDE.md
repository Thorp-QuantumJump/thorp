# CLAUDE.md — backend (BACK 파트)

Spring Boot 3.5 / Java 21 / Gradle. 백테스트 Job 생성·상태·결과 API. **백테스트 계산은 하지 않는다** — Job을 Kafka(`backtest.jobs`)에 넣으면 Worker가 처리.

## 검증 명령 (작업 후 반드시 실행)

```bash
./gradlew spotlessApply build
```

## 패키지 규칙 (`com.quantumjump.api`)

- 도메인별 패키지(`backtest/`, ...) 안에 controller·service·repository·dto를 함께 둔다 (계층별 상위 폴더 X)
- `config/` 공통 설정, `common/` 공통 예외·응답 포맷

## 규칙

- DTO는 record 우선, 요청 DTO에 `@Valid` 검증 필수
- 포맷은 Spotless(google-java-format AOSP)가 강제 — CI에서 spotlessCheck 실패 시 빌드 실패
- API JSON은 camelCase, 비동기 계약 준수: `POST /backtests` → 즉시 `{backtestId, status: QUEUED}` 반환
- 상태 흐름: `QUEUED → RUNNING → COMPLETED | FAILED` (Worker가 갱신, backend는 조회 제공)
- Job 메시지 스키마 변경 시 `worker/src/worker/models.py`와 반드시 동기화

## 도메인 용어

Job(백테스트 작업 단위), Strategy(전략), BacktestResult(수익률·MDD·Sharpe·거래 횟수)

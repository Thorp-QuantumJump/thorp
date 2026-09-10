# Research: 백테스트 실행 (Phase 0)

**Date**: 2026-09-10 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

Technical Context의 미확정 항목과 설계 선택을 결정으로 정리한다. 팀 결정 항목(PD-03·04·06·07)은 값을 정하지 않고 "값이 들어올 자리"만 정한다. 형식: Decision / Rationale / Alternatives.

## R-01 기준 엔진과 전략 구현 방식

- **Decision**: NautilusTrader 1.219.0을 유지한다. 다섯 전략을 Nautilus `Strategy` 하나(`PortfolioRebalanceExecution`)로 실행하고, 목표 비중 계산은 `worker/src/worker/allocation/` 아래 순수 함수(`compute_weights(history, params, rules) -> AllocationResult`)로 분리한다. 리밸런싱 판단일 bar-close에 목표 비중을 받아 정수 수량 주문을 만들고, 체결은 다음 거래일 시가 QuoteTick(현행 방식)로 한다.
- **Rationale**: constitution "계산·데이터 규칙"이 Nautilus를 기준 엔진으로 정했다. 체결·현금·수수료 회계를 Nautilus에 맡기고 배분 규칙만 순수 함수로 두면 수기 대조(SC-003)와 규칙 버전 교체(PD-03·04)가 쉽다.
- **Alternatives**: pandas 벡터화 엔진(빠르지만 constitution 위반, 별도 엔진은 Nautilus와 대조 후에만 허용) / 전략마다 Nautilus Strategy 분리(체결 코드 5중 복제).

## R-02 분석 요청과 실행의 분리

- **Decision**: `backtest_request`(사용자 식별자 `BT-…`, 요청자, 취소 시각, 결과 출처)와 `backtest_run`(동일 버전 키, 상태, 진행, 결과)을 다대일로 둔다. 기존 `backtest_job`은 V3 마이그레이션에서 `backtest_run`으로 개명·확장하고 기존 행마다 request 1개를 생성한다. `backtest_result`·`backtest_trade`의 FK는 run을 가리킨다. 동일 버전 중복 방지는 부분 유니크 인덱스 `UNIQUE (dedup_key) WHERE status IN ('QUEUED','RUNNING','COMPLETED')`로 한다.
- **Rationale**: Clarify Q2·Q3. 완료 결과만 재사용하고 실패·취소 실행은 같은 키로 새 실행을 허용해야 하므로 전체 유니크가 아니라 부분 유니크가 맞다.
- **Alternatives**: 단일 테이블 self-reference(plan Complexity Tracking 참고) / 애플리케이션 락으로 중복 방지(경합 시 두 실행이 생김).

## R-03 동일 버전 키

- **Decision**: FR-003 항목을 canonical JSON(키 사전순 정렬, 소수는 plain string, null 키 제거)으로 직렬화해 SHA-256 hex 64자를 `dedup_key`로 저장한다. 포함 항목: strategyId, rulesVersion, parameters, sector→ETF 대응(대응표 버전 + 정렬된 (sectorId, instrumentId, targetWeight) 목록), dataVersion, evaluationSplitVersion, costProfileVersion, engineVersion(`nautilus_trader` 버전 + 워커 엔진 버전 문자열), scenario, 분석 기간·시작 금액. 요청자·executionType은 제외한다.
- **Rationale**: constitution "실행·캐시" 캐시 키 항목과 일치. Jackson `ORDER_MAP_ENTRIES_BY_KEYS` + 기존 `messageObjectMapper`(BigDecimal plain string)로 구현 가능해 새 라이브러리가 필요 없다.
- **Alternatives**: DB에서 컬럼 조합 유니크(JSONB 파라미터 비교 불안정) / UUIDv5(결국 canonical 문자열이 필요).

## R-04 취소 채널

- **Decision**: fanout exchange `backtest.control`을 추가한다. 워커는 시작 시 exclusive·auto-delete 큐를 바인딩하고 별도 스레드에서 `{type: CANCEL, runId}`를 받아 `cancel_requested` 집합에 넣는다. 전략은 `on_bar`(거래일마다 호출)에서 집합을 확인해 자기 run이면 즉시 정지하고, 소비자는 `FINISHED(status=CANCELLED, partial=true, lastTradingDate)`와 부분 EngineOutput을 발행한 뒤 ACK한다. 대기 중 취소는 백엔드가 run을 CANCELLED로 바꾸는 것으로 끝내고, 그 run의 STARTED가 나중에 도착하면 백엔드가 CANCEL 제어 메시지를 보낸다.
- **Rationale**: Clarify Q1·Q3. 취소 확인 지점을 거래일 단위로 두면 완료 범위가 "처리한 마지막 거래일"과 정확히 일치한다(Q4). 큐 재정렬이나 메시지 삭제는 RabbitMQ에서 불가능하므로 소비 시점 확인이 유일한 실용적 방법이다.
- **Alternatives**: 상태만 바꾸고 워커는 끝까지 계산(자원 낭비, 부분 결과 시점 불명) / 워커가 DB 플래그 폴링(워커 DB 접근 금지 계약 위반).

## R-05 진행 정도 저장과 조회

- **Decision**: 백엔드가 PROGRESS 이벤트(20거래일 배치, 현행)를 받아 `backtest_run.progress_stage / progress_last_trading_date / progress_completed_days / progress_total_days`에 저장한다. 조회는 기존 `GET /backtests/{id}` 폴링으로 한다. 단계 값은 `PREPARING_DATA`(STARTED 수신) → `COMPUTING`(첫 PROGRESS) → `SAVING`(FINISHED 처리 중) → 종결. SSE는 이번 범위에서 만들지 않고 `LoggingBacktestProgressNotifier`는 삭제한다.
- **Rationale**: Clarify Q4. 20거래일 배치 저장은 실행당 수백 번 UPDATE 이하라 부담이 없고, 폴링 API가 이미 있다. SSE는 사용성 관찰에서 필요가 확인되면 추가한다.
- **Alternatives**: SSE 즉시 도입(프론트 변경 필요, 005 범위) / 진행 미저장(다른 화면에서 돌아왔을 때 SC-001 위반).

## R-06 시스템 장애 1회 자동 재시도

- **Decision**: `backtest.jobs`를 quorum queue(`x-queue-type=quorum`, `x-delivery-limit=1`, 기존 DLX 유지)로 선언한다. 워커 프로세스가 죽어 unacked 메시지가 재전달되면 한 번만 재실행되고, 두 번째도 끊기면 브로커가 DLQ로 보낸다. 백엔드에 `backtest.jobs.dlq` 리스너를 추가해 run을 `FAILED(failureCode=SYSTEM_FAILURE)`로 종결한다. STARTED 이벤트의 새 executionId를 받으면 `attempt`를 1 올리고, 측정 기록의 `autoRetried = attempt > 1`로 남긴다. 워커가 스스로 판정한 결정적 오류(INVALID_REQUEST·자료 부족·제약 위반)는 FINISHED(FAILED)로 응답하고 ACK하므로 재시도되지 않는다.
- **Rationale**: Clarify Q5. 브로커가 재시도 횟수를 세므로 워커·백엔드에 카운터 경합이 없다. 기존 DLX·DLQ 토폴로지를 그대로 쓴다.
- **Alternatives**: `redelivered` 플래그로 워커 판단(2회째와 3회째 구분 불가) / 백엔드가 STARTED 횟수로 CANCEL 발행(경합, 계산 낭비).
- **주의**: classic → quorum 전환은 인자 변경이라 기존 큐를 삭제해야 한다. 개발 환경은 `rabbitmqctl delete_queue backtest.jobs`(quickstart), 운영은 배포 절차에 넣는다. 미ACK 메시지가 없을 때만 삭제한다.

## R-07 비용 조건 버전

- **Decision**: `cost_profile(version PK, commission_bps, spread_bps, slippage_bps, tax_bps, escalation JSONB, provisional BOOL, note)` 테이블을 둔다. PD-07 확정 전에는 현행 데모 값(수수료 ZERO, 슬리피지 5bps, 세금·호가차 0)을 `PROVISIONAL-DEMO-1`, `provisional=true`로 등록하고 요청이 버전을 지정하지 않으면 이 값을 쓴다. 결과 `versions.costProfile`에 버전과 provisional 여부를 담아 005가 "임시 비용" 한 문장을 표시할 수 있게 한다.
- **Rationale**: spec Assumptions("확정 전 임시값을 쓰고 임시임을 표시"). 값을 새로 정하지 않고 이미 코드에 있는 값을 재사용하므로 AI가 결정 항목을 확정하는 것이 아니다.
- **Alternatives**: 비용 미확정 시 실행 거부(모든 전략이 막혀 개발이 멈춤).

## R-08 전략 규칙 등록표와 미확정 규칙 처리

- **Decision**: `strategy_rules(strategy_id, rules_version, spec JSONB, status, provisional BOOL, decision_ref)` 테이블을 둔다. `FIXED_WEIGHT`, `EQUAL_WEIGHT`는 spec FR-021·022의 문장 그대로 `REGISTERED`로 넣는다. `INVERSE_VOLATILITY`는 FR-023의 관측 63거래일은 spec이 정한 값이지만, 0 변동성 처리(`EXCLUDE_THEN_ALLOCATE`)·비중 상한·투자 비중 합계는 spec Edge Case가 "PD-02·PD-03 계열 결정 항목"으로 둔 값이므로 `REGISTERED`이되 **`provisional=true`, `decision_ref=PD-02`**로 등록한다(R-07 비용 처리와 같은 방식). 결과 `versions.rules.provisional`로 노출해 005가 임시 규칙임을 표시한다. `SECTOR_MOMENTUM`(PD-03), `MINIMUM_VARIANCE`(PD-04)는 `PENDING_DECISION` 행만 두고 요청 시 백엔드가 422 `RULES_NOT_REGISTERED`로 거부한다. 확정되면 행을 `REGISTERED`로 바꾸고 `spec`을 채운다. 워커의 `rules_registry.py`는 EngineInput에 담긴 규칙 명세만 신뢰하고 자체 기본값을 두지 않는다.
- **Rationale**: FR-010(낮은 수익으로 위장 금지)과 constitution "AI 작업 방식". 미확정 규칙을 임의 구현하면 나중에 결과 버전이 갈라진다. 역변동성의 0 변동성 규칙을 `provisional` 없이 등록하면 AI가 PD-02 계열 값을 확정한 것이 되므로(analyze C1) 임시 표시를 붙인다.
- **Alternatives**: 출발안을 임의로 코드에 넣기(금지) / 두 전략을 spec에서 제외(범위 축소는 팀 결정).
- **역변동성 0 변동성 규칙**: spec Edge Case의 예시 중 "그 섹터 제외 후 나머지 배분"을 임시 등록 값(`provisional=true`)으로 쓴다. PD-02가 확정되면 그 값으로 행을 갱신하고 `provisional=false`로 바꾼다.

## R-09 시장 자료 입력 경로

- **Decision**: 워커 `DataProvider`에 `PostgresMarketDataProvider`를 추가하고 [contracts/market-data-input.md](contracts/market-data-input.md)의 테이블·컬럼을 읽는다. 체결에는 원시 OHLC, 신호·성과에는 조정 종가, 분배금 이벤트는 지급일 현금 반영, 상장·폐지일로 가용 기간을 자른다. 001이 자료를 넣기 전까지는 같은 스키마의 CSV 픽스처(`worker/examples/`)로 테스트한다. 현행 yfinance 경로는 실행 경로에서 제거하고 개발 스크립트로만 남긴다.
- **Rationale**: 원칙 X(개발·검증 원천만 허용, 재배포 금지)와 001과의 경계. 워커의 `validate_prices()` fail-closed 검증은 그대로 재사용한다.
- **Alternatives**: 워커가 Parquet 직접 읽기(001이 저장 형식을 정하기 전이라 미정; provider 인터페이스로 열어둔다).

## R-10 실행 측정 기록과 원가

- **Decision**: 백엔드가 종결 이벤트마다 `backtest_run_metric`(run_id, execution_type, status, queued_at, started_at, finished_at, wait_ms, run_ms, peak_rss_mb, cpu_seconds, auto_retried, result_bytes)을 기록한다. 자원 사용량은 워커가 FINISHED `diagnostics.resource`에 실어 보낸다(`resource.getrusage`). 재조회는 `backtest_reuse_event(run_id, request_id, kind JOINED|REUSED, at)`로 따로 기록한다. 집계는 SQL 뷰 `v_run_metric_summary`(유형별 `percentile_cont(0.5/0.95)` 대기·소요, 실패율, 자동 재시도율)와 `GET /internal/execution-metrics`로 제공한다. 원가는 cpu_seconds·peak_rss_mb·result_bytes 원단위로만 기록하고 금액 환산 단가는 정하지 않는다.
- **Rationale**: 원칙 IX와 US4. 뷰 하나면 운영자가 볼 수 있고 대시보드는 필요가 확인되면 만든다.
- **Alternatives**: Micrometer 히스토그램(프로세스 재시작 시 소실, 실행 유형별 원가 기록 불가) / 별도 시계열 DB(미측정 병목에 인프라 추가).

## R-11 결과 산출 항목 확장

- **Decision**: EngineOutput에 `weightsSeries`(리밸런싱일별 목표·실제 비중, `weightsSource: USER|COMPUTED`), `costs`(commission·spread·slippage·tax·total), `liquidation`(markToMarket·liquidated 두 결과), `analytics`(recoveryDays·turnover·correlationMatrix·riskContribution·lossContribution·weightError), `exceptions[]`(시점·종목·규칙·처리), `versions{}`를 추가한다. 계산은 `performance.py`를 확장한다. 상관관계·위험 기여는 평가 기간 일별 수익률의 표본 공분산으로 계산하고 방법을 `analytics.method`에 적는다(PD-04 공분산 방법과는 별개 표기).
- **Rationale**: FR-017·027·029·030과 005가 그대로 표시할 항목 목록.
- **Alternatives**: 백엔드에서 파생 지표 계산(원칙 "Backend는 계산하지 않는다" 위반).

## R-12 요청자와 권한

- **Decision**: `backtest_request.requester_id`는 `users.id` FK로 필수다. 취소·재시도는 요청자 본인만 할 수 있고 타인 요청은 404로 숨긴다. 계산 기록(`backtest_run`·result)에는 요청자를 넣지 않는다.
- **Rationale**: Clarify Q2·Q3 뒤 남은 저영향 항목. 저장소에 `users`·`email_verifications`가 있어 인증이 전제다. 원칙 VIII "개인 식별정보는 계산 기록과 분리".
- **Alternatives**: 익명 요청 허용(취소 권한 판정 불가). 인증·006 spec에서 익명을 허용하기로 하면 컬럼을 nullable로 완화한다.

## R-13 API 규약 선택 (constitution "넣지 않은 영역")

- **Decision**: 기존 `ApiResponse` 봉투·202/200/409 패턴을 유지한다. 신규: `POST /backtests/{id}/cancel`(202, 종결 상태면 409 `NOT_CANCELLABLE`), `POST /backtests/{id}/retry`(202 새 backtestId, FAILED·CANCELLED에서만, 아니면 409 `NOT_RETRYABLE`), `GET /backtests/{id}/weights`. `POST /backtests`의 202 본문에 `resultSource: NEW|JOINED|REUSED`와 `effectiveStart`를 추가한다. 오류 코드 추가: `RULES_NOT_REGISTERED`(422), `INSUFFICIENT_HISTORY`(422), `EXECUTION_LIMIT`(429), `NOT_CANCELLABLE`/`NOT_RETRYABLE`(409). 상태 문자열은 `CANCELLED`.
- **Rationale**: 기존 계약과의 일관성. 세부는 [contracts/rest-api.md](contracts/rest-api.md).

## R-14 결정성 보장

- **Decision**: 시드 42 고정(현행), 종목·날짜 정렬 고정, numpy·numba·BLAS 단일 스레드(운영 compose에 이미 설정, 개발 compose에도 추가), 부동소수 누적 순서 고정. CI에 "같은 EngineInput 두 번 실행 → EngineOutput JSON 완전 일치" 테스트를 둔다.
- **Rationale**: SC-002. 재사용의 정당성은 재계산이 같다는 사실에서 온다.

## R-15 공통 워밍업과 실행 가능 기간

- **Decision**: 백엔드가 요청 시 001의 공통 평가 기간(가장 늦은 상장일 이후)과 선택 전략의 최대 관측 길이(+최근 제외 구간)를 합해 `effectiveStart`를 계산해 run에 고정하고 202 응답에 돌려준다. 가용 기간이 관측 길이보다 짧으면 422 `INSUFFICIENT_HISTORY`. 상한 합계가 투자 비중을 채울 수 없는 설정은 400 `INVALID_INPUT`으로 실행 전에 거부한다.
- **Rationale**: FR-018, Edge Case, 원칙 IV "공통 평가 구간이 줄면 실행 전에 알린다". 날짜 산술은 계산이 아니라 검증이므로 백엔드에서 해도 원칙 1에 어긋나지 않는다.

## R-16 테스트·검증 기준 선택 (constitution "넣지 않은 영역")

- **Decision**: Task 완료 조건 2의 "테스트"는 backend `./gradlew spotlessApply build`, worker `ruff check src tests && ruff format --check src tests && pytest`로 한다. 계약 변경은 양쪽 픽스처 테스트가 같은 `examples/*.json`을 읽어 통과해야 한다. 수기 대조(SC-003)는 3개 ETF·24개월 CSV 픽스처에 대해 스프레드시트 계산 결과를 `worker/tests/fixtures/manual_reconciliation.json`으로 고정하고 pytest가 허용 오차(PD-12 확정 전 임시 0, 즉 정확 일치) 안에서 비교한다.
- **Rationale**: 현행 검증 명령을 그대로 쓴다. 허용 오차 0은 정수 수량·원 단위 회계에서 가능하고 PD-12가 정해지면 바꾼다.

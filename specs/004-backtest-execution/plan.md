# Implementation Plan: 백테스트 실행

**Branch**: `004-backtest-execution` (spec 디렉터리 식별자. 구현 브랜치는 Jira Task마다 `{type}({position})/{JIRA}-{desc}`) | **Date**: 2026-09-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/004-backtest-execution/spec.md` (Clarifications 2026-09-10 5건 반영판)

## Summary

'분석하기' 이후의 실행 관리(즉시 접수·상태·진행 정도·취소·재시도·동일 버전 재사용·합류·측정)와 다섯 배분 전략의 공통 거래·평가 규칙, 재현 가능한 결과 산출을 구현한다. 기존 DEMO 파이프라인(Spring Backend → RabbitMQ → Nautilus Worker → PostgreSQL 이벤트 저장)을 유지하고 확장한다. 핵심 변경은 (1) 분석 요청과 실행을 다대일로 분리하고 동일 버전 키로 실행을 공유, (2) 워커에 취소 제어 채널과 다종목 포트폴리오 리밸런싱 전략·배분 모듈 추가, (3) 비용 조건·전략 규칙을 버전 등록표로 관리하되 팀 미확정 규칙(PD-03·04)은 실행을 거부, (4) 실행 유형별 측정 기록을 먼저 만들어 실행 한도(PD-06)를 실측으로 정할 근거를 확보하는 것이다. 세부 결정은 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Backend Java 21 (Spring Boot 3.5.4, Gradle) / Worker Python 3.11. 프론트엔드 변경은 이 spec 범위가 아니다(결과 표시는 005).

**Primary Dependencies**: Backend: Spring Web·Data JPA·AMQP·Flyway, Jackson, ArchUnit. Worker: `nautilus_trader==1.219.0`(constitution이 정한 기준 엔진), pandas, numpy, pydantic v2, pika. 최소분산 솔버 라이브러리는 PD-04 확정 시 결정한다(후보 scipy SLSQP). 새 의존성은 그 전에 추가하지 않는다.

**Storage**: PostgreSQL 16 (스칼라 조회 열만 컬럼, 소수·시계열은 JSONB — backend/CLAUDE.md 규칙 유지), RabbitMQ 3.13 (`backtest.jobs`를 quorum queue로 전환, 제어용 fanout exchange 추가). Parquet 친화 저장은 001 자료 계층의 책임이며 004는 읽기만 한다.

**Testing**: Backend JUnit 5 + Testcontainers(PostgreSQL, RabbitMQ) + ArchUnit, `./gradlew spotlessApply build`. Worker pytest + ruff, `ruff check src tests && ruff format --check src tests && pytest`. 메시지 계약 픽스처(`docs/specs/backtest-messaging/examples/*.json`)를 양쪽 테스트가 공유하는 방식을 유지하고 schemaVersion 2 픽스처를 추가한다.

**Target Platform**: Linux 컨테이너(docker compose). 운영 워커는 4 vCPU에서 최대 2 replica, 1 CPU·2.5 GB 제한, 수치 라이브러리 단일 스레드.

**Project Type**: web-service (REST API 백엔드 + 메시지 소비 워커). 모노레포 `backend/`, `worker/`, `docs/specs/` 갱신.

**Performance Goals**: PD-06(실측 후 고정). 이 spec은 목표치를 정하지 않고 측정 장치(실행 유형별 대기 포함 p50/p95·동시성·메모리·실패·자동 재시도·원가)를 먼저 만든다. **한도 값과 한도 장치를 분리한다**(R-19, plan 리뷰 4번): 값은 PD-06이 정하지만, 장치(설정 가능한 사용자별·전체 대기 상한과 실행 시간 상한, 초과 시 429 `EXECUTION_LIMIT`·실행 정지)는 T014에서 만들고, 동시 실행 상한은 워커 replica × prefetch로 배치가 강제한다(T002, R-19). 값이 정해지기 전에는 local 프로필 임시값으로만 동작하고 결과·응답에 임시임을 표시한다. 워커 replica 2·prefetch 1은 동시 실행만 제한하고 대기 누적·실행 시간은 제한하지 못하므로 그것만으로는 FR-006의 "무제한 실행 금지"를 충족하지 않는다.

**Constraints**: Backend는 계산하지 않는다. 동일 버전 재계산은 100% 동일해야 한다(시드 고정, 정렬 고정, 단일 스레드 수치 연산). 판단은 bar-close 자료, 체결은 다음 거래일 시가(현행 엔진 규칙 유지). 워커는 DB에 쓰지 않고 이벤트로만 상태를 전달한다(현행 계약 유지).

**Scale/Scope**: 섹터-ETF 대응표 크기(수십 종목) × 일봉 2010년 이후(≈4,000거래일) × 전략 5종. 단일 실행은 수 초~수십 초로 예상하지만 미측정이며, 측정 기록이 쌓이기 전에는 처리량을 제시하지 않는다(원칙 IX).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙/규칙 | 판정 | 근거 |
|---|---|---|
| II 서버·인프라 설정을 사용자 입력으로 요구하지 않음 | PASS | API 요청에 큐·워커·엔진 설정 항목이 없다. 엔진·비용·자료 버전은 서버가 채운다. |
| III 진단이지 추천 아님 | PASS | 004는 결과 산출만 하며 승자·추천 비중을 만들지 않는다. 자동 배분 비중은 `weightsSource=COMPUTED`로 사용자 입력과 구분한다(FR-026). |
| IV 같은 조건에서만 비교 | PASS | 공통 평가 기간은 서버가 자료에서 산출하고, 공통 워밍업은 비교 묶음(003·007)이 `conditions.warmupTradingDays`로 넘긴 값으로 고정하고, 그 값이 선택 전략 요구값보다 작으면 올리지 않고 거부한다(R-15). 워밍업·비용 버전을 실행 키에 넣는다(R-03). |
| V 당시 정보만 사용 | PASS | 신호는 판단일까지의 조정 자료, 체결은 다음 거래일 원시 시가(현행 Nautilus 경계 유지). 상장 전 합성·미래 채움 없음. |
| VI 없는 결과를 만들지 않음 | PASS | 취소·실패·제약 위반·자료 부족을 별도 상태·코드로 기록. 미등록 규칙(PD-03·04)의 전략은 실행을 거부하고 사유를 돌려준다. 0 대체 없음. |
| VII 탐색·검증 구분 | PASS | 평가 분할 버전을 실행 키에 포함해 잠금 구간 결과가 다른 버전으로 남는다. 검증 절차 자체는 007 소관. |
| VIII 재현 기록 | PASS | 실행마다 전략·파라미터·대응표·자료·비용·엔진·규칙·분할 버전을 `versions` JSONB로 남기고, 캐시 키는 constitution "실행·캐시" 항목을 모두 포함한다. |
| IX 실측으로 한도 결정 · FR-006 무제한 실행 금지 | **조건부(PD-06 대기)** | 단일 서버 작업 큐 유지, 분산 미도입. 측정 테이블·집계 뷰를 먼저 만든다(T012). 재조회와 새 계산을 구분 기록한다. 한도 장치는 T014가 만들되 값은 PD-06 전까지 비어 있으므로 팀이 값을 넣기 전에는 FR-006이 미충족이다. 사용자 공개 전 확인 항목(R-19). |
| X 자료 이용 조건 | PASS(조건부) | 실행 경로의 가격 원천은 001이 확보한 자료만 쓴다. 현행 yfinance 경로는 개발 도구로만 남기고 실행 경로에서 제외한다. |
| 계산·데이터 규칙 "Nautilus 기준 엔진" | PASS | Nautilus 유지. 배분 규칙은 순수 함수 모듈로 분리하되 체결·회계는 Nautilus가 한다. |
| AI 작업 방식 "실행 전 결정 임의 확정 금지" | PASS | PD-03·04·06·07 값을 정하지 않는다. 등록표에 `PENDING_DECISION`으로 두고 확정 시 행만 추가한다. 비용 임시값은 현행 데모 값을 `provisional=true`로 재사용하며 결과에 표시한다. |
| Git·Jira 규칙 | 참고 | tasks.md의 작업 단위를 `[BE]/[DE]/[INFRA]/[DOCS] 작업 내용` 형식, 1 Task = 1 Branch = 1 MR로 맞춘다. |
| "넣지 않은 영역"(API 규약·DB 명명·테스트 기준) | 기록 | 이 영역에서 내린 선택은 research.md R-13·R-16에 근거를 남긴다. |

**Post-design re-check (Phase 1 후)**: data-model·contracts를 검토한 결과 위 판정 변동 없음. 추가 확인: 요청 테이블에 개인 식별정보는 `requester_id`(FK)만 두고 계산 기록(`backtest_run`)에는 요청자를 넣지 않아 원칙 VIII "개인 식별정보는 계산 기록과 분리"를 만족한다.

## Project Structure

### Documentation (this feature)

```text
specs/004-backtest-execution/
├── plan.md              # 이 문서
├── research.md          # Phase 0: 결정 R-01~R-16
├── data-model.md        # Phase 1: 테이블·상태 전이·검증 규칙
├── quickstart.md        # Phase 1: 검증 시나리오
├── contracts/
│   ├── rest-api.md          # Backend ↔ Frontend (docs/specs/backtest-api 갱신 원본)
│   ├── messaging.md         # Backend ↔ Worker schemaVersion 2 (docs/specs/backtest-messaging 갱신 원본)
│   └── market-data-input.md # 004가 001 자료 계층에 요구하는 입력 계약
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
backend/src/main/java/com/ssafy/thorp/backtest/
├── presentation/controller/BacktestController.java        # cancel·retry·weights 추가
├── presentation/request/BacktestJobRequest.java            # 섹터 배분·전략 설정·조건 구조로 개편
├── presentation/response/…                                 # CANCELLED·progress·resultSource·versions
├── application/service/BacktestService.java                # 동일 버전 조회 → 합류/재사용/신규 분기
├── application/service/BacktestEventService.java           # PROGRESS 저장, CANCELLED 처리, attempt
├── application/service/DedupKeyCalculator.java             # 신규: canonical JSON → SHA-256
├── application/service/ExecutionMetricRecorder.java        # 신규: 측정 기록
├── application/port/message/…                              # EngineInput v2, ControlMessage
├── domain/model/{BacktestRequest,BacktestRun,RunStatus,…}  # Job → Run/Request 분리
├── infrastructure/persistence/…                            # JPA 엔티티·매퍼·리포지토리
├── infrastructure/messaging/BacktestRabbitConfig.java      # quorum queue, backtest.control
├── infrastructure/messaging/BacktestDeadLetterListener.java# 신규: DLQ → FAILED(SYSTEM_FAILURE)
└── …/operations/ExecutionMetricsController.java            # 신규: GET /internal/execution-metrics
backend/src/main/resources/db/migration/V3__backtest_run_request.sql  # 신규 마이그레이션

worker/src/worker/
├── consumer.py                 # 제어 채널 구독, 취소 플래그, FINISHED(CANCELLED) 발행
├── rabbitmq.py                 # control exchange exclusive queue 구독 스레드
├── nautilus_engine.py          # PortfolioRebalanceExecution(다종목 월말 리밸런싱, 취소 확인)
├── allocation/                 # 신규: fixed.py equal.py inverse_vol.py momentum.py min_variance.py
├── rules_registry.py           # 신규: 등록 규칙 버전 로드, PENDING이면 INVALID_REQUEST
├── costs.py                    # 신규: 비용 프로필 적용(수수료·호가차·슬리피지·세금)
├── corporate_actions.py        # 신규: 분배금 현금 반영(중복 금지)
├── market_data.py              # PostgresMarketDataProvider 추가(001 계약)
├── performance.py              # 비용 합계·회복기간·회전율·상관·위험/손실 기여·청산 결과
└── contracts/                  # EngineInput/EngineOutput/Events v2, ControlMessage

docs/specs/backtest-api/, docs/specs/backtest-messaging/   # contracts/ 확정 후 갱신 + examples v2
docs/adr/0005-backtest-request-run-split.md               # 신규 ADR(요청/실행 분리·quorum queue·제어 채널)
```

**Structure Decision**: 기존 모노레포 배치와 ADR-0001 계층 구조를 유지한다. 새 개념(요청/실행 분리, 배분 모듈, 등록표)은 기존 패키지 안에 추가하고 새 프로젝트를 만들지 않는다. 프론트엔드 파일은 건드리지 않는다.

## Complexity Tracking

Constitution 위반은 없다. 단순한 대안을 버린 선택 세 가지만 기록한다.

| 선택 | 왜 필요한가 | 더 단순한 대안을 버린 이유 |
|---|---|---|
| `backtest_job` 하나를 `backtest_request` + `backtest_run` 둘로 분리 | Clarify Q2·Q3: 요청과 실행이 다대일이고 결과는 요청자와 독립 저장 | 한 테이블에 self-reference로 합류를 표현하면 취소·상태 전파 규칙이 행마다 갈라져 SC-006 검증이 어렵다 |
| 작업 발행을 커밋 후로 옮기고 `backtest_run.published_at`으로 재발행(outbox-lite) | plan 리뷰 1번: 트랜잭션 안 발행은 워커 응답이 커밋보다 먼저 도착해 버려질 수 있고, 커밋 뒤 발행 실패는 QUEUED로 멈춘 실행을 남긴다 | 별도 outbox 테이블 + 릴레이는 테이블·폴러가 하나 더 늘고, run 행이 이미 발행 대상과 1:1이라 열 하나로 같은 보장을 얻는다(R-18) |
| 워커 제어용 fanout exchange 추가 | Clarify Q1: 실행 중 취소가 계산을 실제로 멈춰야 함(원칙 IX 자원) | 백엔드가 상태만 CANCELLED로 바꾸고 워커는 끝까지 계산하는 방식은 대기열 자원을 낭비하고 부분 결과 산출 시점을 정할 수 없다 |

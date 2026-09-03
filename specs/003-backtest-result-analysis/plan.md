# Implementation Plan: 백테스트 결과 조회 API (US1·US2)

**Branch**: `feat(be)/47-backtest-result-api` | **Date**: 2026-09-03 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/003-backtest-result-analysis/spec.md`
+ 구현 설계 문서 `implplan/backtest-result-api.md`(저장소 외부, §2 설계 결정을 이 plan에 반영)

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

성공 완료된 백테스트 하나의 **핵심 지표(US1)** 와 **자산 가치 시계열(US2)** 을 조회하는 백엔드 API를
만든다. 기존 `backtest` 도메인에 `backtest_result` 저장 모델을 추가하고,
`GET /backtests/{id}` 응답에 `config`·`summary`를 얹고, `GET /backtests/{id}/timeseries`를 신설한다.
백엔드는 지표를 계산하지 않는다(헌장 원칙 I). 값은 Worker가 「지표 계산 규약 v1.0」(research.md §2)에 따라
산출해 `backtest_result`에 저장하고 백엔드는 저장·조회만 담당한다. Worker의 결과 저장 경로가 붙기 전까지는
dev 프로파일 시더가 예시 전략 3개의 결과를 채워 프론트 연동을 막지 않는다.

**이번 범위**: US1, US2, `POST /backtests` 요청의 `initialCash` 추가, `backtest_result` 스키마 확정, dev 시더.
**범위 밖(계약만 정의)**: US3 거래 내역(`/trades`), US4 지표 정의 열람·계산 불가 이유. 전략 비교(spec 004),
벤치마크(FR-015 금지), 브로커·Publisher 구현, `worker/`·`frontend/` 수정.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5.4, Gradle 8, google-java-format AOSP via Spotless)

**Primary Dependencies**: spring-boot-starter-web, spring-boot-starter-validation, spring-boot-starter-data-jpa
(Hibernate ORM 6.6.x, Spring Boot 3.5.4 관리 버전), Jackson(Boot 기본), ArchUnit 1.3.0(테스트).
새 의존성 추가 없음.

**Storage**: PostgreSQL 16 (`docker-compose.dev.yml`), `spring.jpa.hibernate.ddl-auto=update` (Flyway 미도입).
테스트는 H2(`testRuntimeOnly`) + `@DataJpaTest` 기본 설정(`src/test/resources` 없음)

**Testing**: JUnit 5, AssertJ, Mockito(`@ExtendWith(MockitoExtension)`), `@WebMvcTest`+`@MockitoBean`,
`@DataJpaTest`+`@Import({JpaAuditingConfig, *RepositoryImpl})`, ArchUnit `ArchitectureTest`. `@DisplayName` 한글

**Target Platform**: Linux 컨테이너(JVM 21). 로컬 개발은 Windows/WSL

**Project Type**: web-service (모노레포의 `backend/` 파트만 수정)

**Performance Goals**: SC-006 — 5년 일봉(≈1,250포인트) 결과 조회 p95 3초 이내. 단일 행 조회 + JSON 역직렬화로 충분

**Constraints**:
- 백엔드는 지표를 계산하지 않는다(헌장 I). 저장·조회·형 변환만 한다
- `common/`의 `ApiResponse`·`ErrorCode`·`BusinessException`·`GlobalExceptionHandler`·`BaseEntity` 재사용, 자체 응답 포맷 금지
- ADR-0001 4계층(`presentation/application/domain/infrastructure`) + `ArchitectureTest` 6개 규칙 통과
- `domain/`에 Spring·JPA·Jackson import 금지. 컨트롤러 DTO를 서비스 시그니처에 쓰지 않음
- API JSON camelCase, 비동기 계약(`POST → 202 {backtestId, status: QUEUED}`) 유지
- Job 메시지 계약 변경(`initialCash`)은 `worker/src/worker/models.py` 동기화 대상으로 기록만 한다(헌장 IV, 단계적 변경)

**Scale/Scope**: 엔드포인트 1개 확장 + 1개 신설, 테이블 1개 신설, 컬럼 1개 추가(`backtest_job.initial_cash`),
신규 클래스 약 16개, 기존 테스트 4개 갱신 + 신규 테스트 5개

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. 백테스트 연산은 독립적인 실행 경계 | PASS | 백엔드는 지표·시계열을 계산하지 않고 `backtest_result`에 저장된 값을 조회만 한다. 계산 규약(research.md §2)은 Worker와 공유하는 계약이며 계산 주체는 Worker다. dev 시더는 계산이 아니라 고정 예시값 삽입이다 |
| II. 실행은 요청 생명주기와 독립, 추적 가능 | PASS | 기존 `backtestId`·상태 흐름(`QUEUED → RUNNING → COMPLETED \| FAILED`) 그대로. 결과는 같은 id로 조회하며 COMPLETED 이전에는 `summary: null` / 409로 "아직 없음"을 명시한다 |
| III. 빅데이터 기술은 실측 후 도입 | PASS | 신규 인프라·의존성 없음. 시계열은 JSON 컬럼 1개(5년 일봉 ≈ 1,250포인트)로 충분하며, 정규화·별도 저장소는 필요가 확인될 때 도입 |
| IV. 파트 경계 계약 명시·함께 변경 | PASS | 응답·요청·테이블 계약을 `contracts/`에 문서화(camelCase). `initialCash`는 optional+기본값으로 기존 Worker 계약과 호환되는 단계적 변경. `backtest_result` 스키마는 Worker 저장 티켓의 계약이 된다. 계산 규약은 `docs/specs/` 승격을 후속 티켓으로 기록 |
| V. 결과 신뢰성(비교 가능성·재현성) | PASS | 모든 결과에 `metricDefinitionVersion`을 저장·응답(FR-005). 계산 불가는 `null`로 표현(FR-011, 0·무한대 금지). 도메인 불변식이 시계열 첫 값 = `initialEquity`, 마지막 값 = `finalEquity`를 강제(FR-007·FR-010). 조회는 저장값을 그대로 돌려주므로 반복 조회 시 동일(FR-014) |
| 보안·데이터 제약 | PASS | 비밀값·시장 데이터 파일 추가 없음 |
| 품질 게이트 | PASS | `./gradlew spotlessApply build`(Spotless + 전체 테스트 + ArchUnit) 통과를 Phase별 완료 조건으로 둔다 |

위반 없음 → Complexity Tracking 해당 없음.

**Post-Design 재확인(Phase 1 후)**: data-model·contracts 설계에서 계산 로직이 백엔드에 들어간 곳은 없다
(도메인 불변식은 저장값 간 일관성 검사이며 지표 산출이 아니다). 계약 변경은 모두 하위 호환(응답 필드 추가,
optional 요청 필드). 판정 유지: 전부 PASS.

## Project Structure

### Documentation (this feature)

```text
specs/003-backtest-result-analysis/
├── spec.md              # 기능 스펙 (기존)
├── checklists/requirements.md
├── plan.md              # 이 문서
├── research.md          # Phase 0: JSON 컬럼 호환, 지표 계산 규약 v1.0, 설계 결정
├── data-model.md        # Phase 1: 도메인 모델·VO·backtest_result 스키마·BacktestJob 변경
├── quickstart.md        # Phase 1: 빌드·기동·curl 검증 절차
├── contracts/
│   ├── README.md                       # 계약 색인·공통 규칙(ApiResponse, 타입, 상태 코드)
│   ├── backtests-api.md                # 엔드포인트 계약 전체(US1·US2 구현, US3·US4 예약)
│   ├── backtest-create-request.schema.json
│   ├── backtest-detail.schema.json     # GET /backtests/{id} data
│   ├── backtest-timeseries.schema.json # GET /backtests/{id}/timeseries data
│   ├── examples/                       # 200/202/404/409 응답 예시 JSON
│   └── backtest-result-table.md        # Worker가 쓰는 backtest_result 저장 계약
└── tasks.md             # Phase 2: /speckit-tasks 산출물
```

### Source Code (repository root)

```text
backend/src/main/java/com/ssafy/thorp/
├── backtest/
│   ├── presentation/
│   │   ├── controller/BacktestController.java          # [수정] GET /{id} 응답 확장, GET /{id}/timeseries 추가
│   │   ├── request/BacktestJobRequest.java             # [수정] initialCash(optional, @Positive)
│   │   └── response/
│   │       ├── BacktestJobResponse.java                # [수정] backtestId, status, config, summary(nullable)
│   │       ├── BacktestConfigResponse.java             # [신규]
│   │       ├── BacktestSummaryResponse.java            # [신규]
│   │       ├── BacktestTimeseriesResponse.java         # [신규] backtestId, points[]
│   │       └── EquityPointResponse.java                # [신규]
│   ├── application/
│   │   ├── service/BacktestService.java                # [수정] get()에 summary 조합, getTimeseries() 추가
│   │   ├── command/CreateBacktestCommand.java          # [수정] initialCash
│   │   ├── result/
│   │   │   ├── BacktestJobResult.java                  # [수정] id, status, config, summary
│   │   │   ├── BacktestConfigResult.java               # [신규]
│   │   │   ├── BacktestSummaryResult.java              # [신규]
│   │   │   ├── EquityCurveResult.java                  # [신규] backtestId, points[]
│   │   │   └── EquityPointResult.java                  # [신규]
│   │   ├── repository/
│   │   │   ├── BacktestJobRepository.java              # (유지)
│   │   │   └── BacktestResultRepository.java           # [신규] save, findByBacktestJobId
│   │   └── port/BacktestJobPublisher.java              # (유지)
│   ├── domain/
│   │   ├── model/
│   │   │   ├── BacktestJob.java                        # [수정] initialCash 필드·불변식, create/restore 시그니처
│   │   │   ├── BacktestStatus.java                     # (유지)
│   │   │   └── BacktestResult.java                     # [신규] jobId 1:1, summary, equityCurve, 정합 불변식
│   │   ├── vo/
│   │   │   ├── BacktestPeriod.java                     # (유지)
│   │   │   ├── PerformanceSummary.java                 # [신규] 지표 11필드, nullable = 계산 불가
│   │   │   ├── EquityPoint.java                        # [신규] date, equity, cumulativeReturn, drawdown
│   │   │   └── EquityCurve.java                        # [신규] 비어 있지 않고 날짜 오름차순
│   │   └── exception/
│   │       ├── BacktestNotFoundException.java          # (유지)
│   │       └── BacktestResultNotReadyException.java    # [신규] RESULT_NOT_READY(409)
│   └── infrastructure/
│       ├── persistence/
│       │   ├── BacktestJobJpaEntity.java               # [수정] initial_cash BIGINT NOT NULL DEFAULT 10000000
│       │   ├── BacktestJobMapper.java                  # [수정]
│       │   ├── BacktestJobJpaRepository.java           # (유지)
│       │   ├── BacktestJobRepositoryImpl.java          # (유지)
│       │   ├── BacktestResultJpaEntity.java            # [신규] backtest_result, equity_curve JSON 컬럼
│       │   ├── EquityPointJson.java                    # [신규] JSON 컬럼 원소(record, Jackson 직렬화)
│       │   ├── BacktestResultMapper.java               # [신규] toEntity/toDomain
│       │   ├── BacktestResultJpaRepository.java        # [신규] findByBacktestJobId
│       │   ├── BacktestResultRepositoryImpl.java       # [신규]
│       │   └── DevSampleResultSeeder.java              # [신규] @Profile("dev") ApplicationRunner, 멱등
│       └── messaging/LoggingBacktestJobPublisher.java  # (유지)
└── common/
    └── exception/ErrorCode.java                        # [수정] RESULT_NOT_READY(CONFLICT)

backend/src/test/java/com/ssafy/thorp/
├── ArchitectureTest.java                               # (유지, 통과 필수)
├── backtest/domain/model/BacktestJobTest.java          # [수정] initialCash
├── backtest/domain/model/BacktestResultTest.java       # [신규] 정합 불변식
├── backtest/domain/vo/PerformanceSummaryTest.java      # [신규]
├── backtest/domain/vo/EquityCurveTest.java             # [신규]
├── backtest/application/service/BacktestServiceTest.java   # [수정] summary 조합, getTimeseries 200/404/409
├── backtest/presentation/controller/BacktestControllerTest.java  # [수정] config·summary JSON, timeseries, 409
└── backtest/infrastructure/persistence/
    ├── BacktestJobRepositoryImplTest.java              # [수정] initialCash 라운드트립
    └── BacktestResultRepositoryImplTest.java           # [신규] JSON 컬럼 라운드트립(H2), unique 제약
```

**Structure Decision**: 모노레포 중 `backend/`만 수정한다. 새 도메인을 만들지 않고 기존 `backtest` 도메인의
4계층(ADR-0001) 안에 결과 모델을 추가한다. 시더는 JPA 리포지토리·엔티티를 직접 다루므로
`infrastructure/persistence`에 둔다(별도 `seed` 패키지를 만들지 않아 ADR의 하위 패키지 목록을 바꾸지 않는다).

## Design Decisions (요약, 상세는 research.md)

| 항목 | 결정 |
|---|---|
| 요청 | `initialCash`(Long, KRW) optional, 기본값 10,000,000. 컨트롤러가 기본값을 채워 command로 넘긴다 |
| `GET /backtests/{id}` | `{ backtestId, status, config, summary }`. `summary`는 COMPLETED이고 결과 행이 있을 때만, 그 외 `null` |
| `GET /backtests/{id}/timeseries` | `{ backtestId, points[] }`. 없는 id 404 `RESOURCE_NOT_FOUND`, COMPLETED 아님·결과 행 없음 409 `RESULT_NOT_READY` |
| 값 타입 | 비율 number(0.125=12.5%), `maxDrawdown` ≤ 0, 금액 KRW 정수(Long), 지표 nullable(`null`=계산 불가) |
| 지표 계산 규약 | v1.0 (research.md §2). `metricDefinitionVersion = "1.0"` 저장·응답 |
| 저장 | `backtest_result` 1테이블, `backtest_job_id` unique. summary 컬럼 + `equity_curve` JSON(`@JdbcTypeCode(SqlTypes.JSON)`, PostgreSQL jsonb / H2 json) |
| 계층 | 도메인 `BacktestResult`+VO 3개, JPA 엔티티 분리, 정적 Mapper. 리포지토리 인터페이스는 application, 구현은 infrastructure |
| 에러 | `ErrorCode.RESULT_NOT_READY(CONFLICT)`, `BacktestResultNotReadyException extends BusinessException` |
| 시더 | `@Profile("dev")` `ApplicationRunner`. `backtest_result`에 행이 있으면 전체 skip. 값은 `frontend/src/data/backtestMock.ts` 환산, `maxDrawdown`은 시계열에서 도출(FR-010) |

## Worker 동기화 항목 (이번 브랜치에서 수정하지 않음, 후속 티켓 전달)

1. `worker/src/worker/models.py` `BacktestJob`에 `initial_cash: int = Field(alias="initialCash", default=10_000_000)` 추가
2. `worker/src/worker/models.py` `BacktestResult`를 `contracts/backtest-result-table.md`의 컬럼 집합으로 확장
   (현재 `total_return, mdd, sharpe, trade_count`만 있음)
3. Worker 결과 저장 구현 후 `DevSampleResultSeeder` 삭제
4. 지표 계산 규약 v1.0(research.md §2)을 `docs/specs/`로 승격

## Complexity Tracking

> Constitution Check 위반 없음 — 해당 없음.

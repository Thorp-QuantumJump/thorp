# Data Model: 백테스트 결과 조회 API (US1·US2)

**Date**: 2026-09-03 | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

스펙 Key Entities 중 이번 범위(US1·US2)에 해당하는 것은 **결과 분석(Result Analysis)**, **지표 값(Metric Value)**,
**자산 가치 시점(Equity Point)** 이다. 지표 정의(Metric Definition)는 `metricDefinitionVersion` 문자열로만 참조하고(정의 본문은
research.md §2 → 후속 `docs/specs/`), 거래(Trade)는 US3에서 다룬다.

---

## 1. 도메인 모델 (`com.ssafy.thorp.backtest.domain`) — 순수 Java

### 1.1 `model.BacktestJob` (기존, 수정)

| 필드 | 타입 | 제약 |
|---|---|---|
| `id` | `Long` | 저장 전 null |
| `strategy` | `String` | not blank |
| `period` | `vo.BacktestPeriod` | 기존 불변식(startDate ≤ endDate) |
| `initialCash` | `long` | **신규**. `> 0`. KRW 정수 |
| `status` | `BacktestStatus` | QUEUED → RUNNING → COMPLETED \| FAILED |

- `create(strategy, period, initialCash)` → QUEUED, id null. `initialCash <= 0`이면 `IllegalArgumentException`(→ 400).
- `restore(id, strategy, period, initialCash, status)` → 저장소 복원. 시더는 `restore(null, …, COMPLETED)`로 완료 Job을 만든다.

### 1.2 `model.BacktestResult` (신규)

성공 완료된 Job 하나에 1:1로 대응하는 결과 분석. Job과 생명주기가 달라 별도 애그리거트로 둔다.

| 필드 | 타입 | 제약 |
|---|---|---|
| `id` | `Long` | 저장 전 null |
| `backtestJobId` | `long` | not null. Job 1개당 결과 1개(unique) |
| `summary` | `vo.PerformanceSummary` | not null |
| `equityCurve` | `vo.EquityCurve` | not null |

**불변식** (생성자에서 검증, 위반 시 `IllegalArgumentException`):

- `equityCurve.first().equity() == summary.initialEquity()` (FR-007: 첫 시점 = 초기 자산)
- `equityCurve.last().equity() == summary.finalEquity()` (FR-010: 시계열과 지표의 정합)

지표를 재계산해 비교하지는 않는다(research.md §8). 정합 검사만 한다.

- `create(backtestJobId, summary, equityCurve)` — 불변식 검증, id null
- `restore(id, backtestJobId, summary, equityCurve)` — 저장소 복원. 불변식은 동일하게 검증한다(Worker가 쓴 행도 검증 대상)

### 1.3 `vo.PerformanceSummary` (신규, record)

지표 값 집합. **nullable 필드의 `null`은 "계산 불가"** 를 뜻한다(FR-011). 정의는 research.md §2.

| 필드 | 타입 | nullable | 제약 |
|---|---|---|---|
| `initialEquity` | `long` | X | `> 0` |
| `finalEquity` | `long` | X | `>= 0` |
| `totalReturn` | `Double` | O | — |
| `cagr` | `Double` | O | — |
| `maxDrawdown` | `Double` | O | `<= 0` |
| `volatility` | `Double` | O | `>= 0` |
| `sharpeRatio` | `Double` | O | — |
| `tradeCount` | `int` | X | `>= 0` |
| `winRate` | `Double` | O | `0 <= x <= 1` |
| `profitFactor` | `Double` | O | `>= 0` |
| `metricDefinitionVersion` | `String` | X | not blank |

### 1.4 `vo.EquityPoint` (신규, record)

| 필드 | 타입 | 제약 |
|---|---|---|
| `date` | `LocalDate` | not null |
| `equity` | `long` | `>= 0` |
| `cumulativeReturn` | `double` | — |
| `drawdown` | `double` | `<= 0` |

### 1.5 `vo.EquityCurve` (신규, record wrapping `List<EquityPoint>`)

- 비어 있지 않음
- `date` strictly ascending(중복 날짜 없음)
- 내부 리스트는 불변 복사(`List.copyOf`)
- 편의 메서드: `first()`, `last()`, `size()`, `points()`

### 1.6 `exception.BacktestResultNotReadyException` (신규)

`extends BusinessException(ErrorCode.RESULT_NOT_READY, "백테스트 결과가 아직 준비되지 않았습니다: id=…, status=…")`.
`GlobalExceptionHandler`가 409 + `{ code: "RESULT_NOT_READY" }`로 변환한다.

---

## 2. 애플리케이션 계층 (`application`)

| 타입 | 종류 | 필드 |
|---|---|---|
| `command.CreateBacktestCommand` | record (수정) | `strategy, startDate, endDate, initialCash(long)` |
| `result.BacktestConfigResult` | record (신규) | `strategy, startDate, endDate, initialCash` |
| `result.BacktestSummaryResult` | record (신규) | `PerformanceSummary`와 같은 11필드. `from(PerformanceSummary)` |
| `result.BacktestJobResult` | record (수정) | `id, status, BacktestConfigResult config, BacktestSummaryResult summary(nullable)`. `from(BacktestJob)`(summary null), `from(BacktestJob, BacktestResult)` |
| `result.EquityPointResult` | record (신규) | `date, equity, cumulativeReturn, drawdown` |
| `result.EquityCurveResult` | record (신규) | `backtestId, List<EquityPointResult> points`. `from(BacktestResult)` |
| `repository.BacktestResultRepository` | interface (신규) | `BacktestResult save(BacktestResult)`, `Optional<BacktestResult> findByBacktestJobId(Long)` |

`BacktestService`:

- `create(command)` — 기존 흐름 + `initialCash` 전달
- `get(id)` — Job 없으면 `BacktestNotFoundException`. COMPLETED면 결과 조회 후 `from(job, result)`, 결과 없으면 warn 로그 + `from(job)`.
  그 외 상태는 `from(job)`
- `getTimeseries(id)` — Job 없으면 404. `status != COMPLETED`면 `BacktestResultNotReadyException`. 결과 없으면 같은 예외.
  있으면 `EquityCurveResult.from(result)`
- 조회 메서드는 `@Transactional(readOnly = true)`

---

## 3. 저장 스키마 (`infrastructure/persistence`)

### 3.1 `backtest_job` (기존 테이블, 컬럼 추가)

| 컬럼 | 타입 | 제약 | 비고 |
|---|---|---|---|
| `initial_cash` | `BIGINT` | NOT NULL, DEFAULT 10000000 | **신규**. `@ColumnDefault("10000000")`로 기존 행 호환(research.md §6) |

나머지 컬럼(`id, strategy, start_date, end_date, status, created_at, updated_at`)은 변경 없음.

### 3.2 `backtest_result` (신규 테이블) — Worker와 공유하는 저장 계약

전체 컬럼 정의와 Worker용 INSERT 예시는 [contracts/backtest-result-table.md](./contracts/backtest-result-table.md)에 둔다. 요약:

| 컬럼 | PostgreSQL 타입 | H2 타입(테스트) | NULL | 대응 도메인 필드 |
|---|---|---|---|---|
| `id` | `BIGINT` IDENTITY PK | 동일 | X | `BacktestResult.id` |
| `backtest_job_id` | `BIGINT` UNIQUE | 동일 | X | `backtestJobId` |
| `initial_equity` | `BIGINT` | 동일 | X | `summary.initialEquity` |
| `final_equity` | `BIGINT` | 동일 | X | `summary.finalEquity` |
| `total_return` | `DOUBLE PRECISION` | `DOUBLE` | O | `summary.totalReturn` |
| `cagr` | `DOUBLE PRECISION` | `DOUBLE` | O | `summary.cagr` |
| `max_drawdown` | `DOUBLE PRECISION` | `DOUBLE` | O | `summary.maxDrawdown` |
| `volatility` | `DOUBLE PRECISION` | `DOUBLE` | O | `summary.volatility` |
| `sharpe_ratio` | `DOUBLE PRECISION` | `DOUBLE` | O | `summary.sharpeRatio` |
| `trade_count` | `INTEGER` | 동일 | X | `summary.tradeCount` |
| `win_rate` | `DOUBLE PRECISION` | `DOUBLE` | O | `summary.winRate` |
| `profit_factor` | `DOUBLE PRECISION` | `DOUBLE` | O | `summary.profitFactor` |
| `metric_definition_version` | `VARCHAR(20)` | 동일 | X | `summary.metricDefinitionVersion` |
| `equity_curve` | `JSONB` | `JSON` | X | `equityCurve` (JSON 배열) |
| `created_at`, `updated_at` | `TIMESTAMP` | 동일 | X | `BaseEntity` (Auditing) |

- `backtest_job_id`에 FK 제약은 걸지 않는다(`ddl-auto=update` 단계). Flyway 도입 시 `REFERENCES backtest_job(id)` 추가.
- `equity_curve` 원소 형식(`EquityPointJson`): `{ "date": "2019-01-02", "equity": 10000000, "cumulativeReturn": 0.0, "drawdown": 0.0 }`.
  `date`는 ISO-8601 문자열. 배열은 날짜 오름차순.

### 3.3 JPA 엔티티·매퍼

- `BacktestResultJpaEntity extends BaseEntity`, `@Table(name = "backtest_result", uniqueConstraints = @UniqueConstraint(columnNames = "backtest_job_id"))`
- `@JdbcTypeCode(SqlTypes.JSON) @Column(name = "equity_curve", nullable = false) List<EquityPointJson> equityCurve`
- `EquityPointJson(String date, long equity, double cumulativeReturn, double drawdown)` — record, Jackson 직렬화 대상
- `BacktestResultMapper.toEntity(BacktestResult)` / `toDomain(BacktestResultJpaEntity)` — `date` 문자열 ↔ `LocalDate` 변환 포함
- `BacktestResultJpaRepository extends JpaRepository<BacktestResultJpaEntity, Long>` + `Optional<…> findByBacktestJobId(Long)`
- `BacktestResultRepositoryImpl implements BacktestResultRepository` (`@Repository`)

### 3.4 `DevSampleResultSeeder`

- `@Component @Profile("dev")`, `ApplicationRunner`
- 의존: `BacktestJobRepository`, `BacktestResultRepository`(도메인 불변식을 거치기 위해 application 인터페이스 사용),
  `BacktestResultJpaRepository.count()`(멱등 판정)
- 동작: `count() > 0`이면 로그 후 종료. 아니면 전략 3개에 대해 COMPLETED Job 저장 → 반환된 id로 `BacktestResult.create` → 저장
- 데이터: research.md §7 표. 클래스 Javadoc에 `TODO(worker-result): Worker 결과 저장이 붙으면 이 클래스를 삭제한다` 명시

---

## 4. 상태 전이

이번 기능은 상태 전이를 추가하지 않는다. 기존 `QUEUED → RUNNING → COMPLETED | FAILED`를 그대로 두고, 결과 조회 가능 여부만
상태에 따라 달라진다(research.md §5 표). 상태를 바꾸는 주체는 Worker다(백엔드는 이번에도 갱신 API를 만들지 않는다).

---

## 5. 검증 규칙 ↔ 요구사항 대응

| 규칙 | 위치 | 요구사항 |
|---|---|---|
| 첫 포인트 equity = initialEquity | `BacktestResult` 불변식 | FR-007 |
| 마지막 포인트 equity = finalEquity | `BacktestResult` 불변식 | FR-010, SC-004 |
| 날짜 오름차순·중복 없음 | `EquityCurve` | FR-007 (시간 순서) |
| 지표 null = 계산 불가, 0·무한대 금지 | `PerformanceSummary` nullable + 규약 §2.4 | FR-011, SC-001 |
| `maxDrawdown ≤ 0`, `winRate ∈ [0,1]` | `PerformanceSummary` | FR-013 (단위 명확) |
| `metricDefinitionVersion` 필수 저장·응답 | `PerformanceSummary`, 컬럼 NOT NULL | FR-005 |
| COMPLETED가 아니면 결과 없음 | `BacktestService` | FR-001 |
| `initialCash > 0` | `BacktestJob` | (F2 조건) |

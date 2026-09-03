# Research: 백테스트 결과 조회 API (US1·US2)

**Date**: 2026-09-03 | **Plan**: [plan.md](./plan.md)

Technical Context에 남은 미확정 사항과 설계 결정의 근거를 기록한다. 형식은 결정 / 근거 / 검토한 대안.

---

## 1. JSON 컬럼 매핑 — Hibernate 6 `@JdbcTypeCode(SqlTypes.JSON)`

**Decision**: 자산 시계열은 `backtest_result.equity_curve` 한 컬럼에 JSON 배열로 저장한다.
엔티티 필드는 `@JdbcTypeCode(SqlTypes.JSON) private List<EquityPointJson> equityCurve;` 로 선언한다.
`EquityPointJson`은 `infrastructure/persistence`의 record이며 `date`는 ISO-8601 문자열(`"2019-01-02"`)로 둔다.

**Rationale**:
- Spring Boot 3.5.4가 관리하는 Hibernate ORM 6.6.x는 `SqlTypes.JSON`을 방언별로 매핑한다.
  PostgreSQL 방언은 `jsonb`, H2 방언은 `json` DDL 타입을 생성하고 각각 전용 JdbcType으로 읽고 쓴다.
  직렬화는 클래스패스의 Jackson(`jackson-databind`, Boot 기본)을 `FormatMapper`로 자동 사용한다.
- 테스트 프로파일 설정 파일이 없어 `@DataJpaTest`가 H2 임베디드 + `create-drop`으로 스키마를 만든다.
  H2 2.x는 `JSON` 데이터 타입을 지원하므로 라운드트립 테스트가 별도 설정 없이 가능하다.
- `date`를 `LocalDate`가 아닌 문자열로 두는 이유: Hibernate가 만드는 ObjectMapper에 JavaTimeModule이 등록되는지에
  의존하지 않기 위해서다. 문자열 ↔ `LocalDate` 변환은 `BacktestResultMapper`가 한다.
- 5년 일봉 ≈ 1,250포인트 × 4필드는 약 100KB 수준이며, 결과는 항상 통째로 조회되므로 행 단위 JSON이 조회 패턴에 맞다.

**Verification**: Foundational Phase의 `BacktestResultRepositoryImplTest`(H2)에서 저장 → `flush/clear` → 조회
라운드트립으로 확정한다. 실패하면 아래 후퇴안으로 전환하고 이 절을 갱신한다.

**결과 (2026-09-03 확정)**: H2 라운드트립 **통과**. `BacktestResultRepositoryImplTest` 4건이 `@JdbcTypeCode(SqlTypes.JSON)` +
`List<EquityPointJson>` 매핑으로 저장·조회·unique 위반을 검증한다. 아래 후퇴안은 채택하지 않았고, 이후 필요 시 참고용으로만 남긴다.

**Fallback (미채택, 참고)**: `@Column(columnDefinition = "TEXT") String equityCurveJson` + `BacktestResultMapper` 안에서
Jackson `ObjectMapper`로 수동 직렬화. Jackson 사용은 infrastructure 계층에 한정되므로 ArchUnit 규칙에 영향이 없다.

**Alternatives considered**:
- 정규화 테이블(`backtest_equity_point`, 행 = 거래일): 시계열을 일부만 읽는 요구가 없고, 5년 × 전략 수만큼 행이
  늘어 INSERT 비용만 증가한다. US3 거래 내역처럼 필터·정렬이 필요한 데이터에서 다시 검토한다.
- `@Convert(AttributeConverter)`로 String 컬럼에 직렬화: 동작하지만 PostgreSQL `jsonb`의 이점(유효성 검사, 향후 JSON 연산)을 버린다.
- Parquet 등 파일 저장: 헌장 III에 따라 실측 전 도입하지 않는다. Worker 내부 연산 격리로 충분하다.

---

## 2. 지표 계산 규약 v1.0 (`metricDefinitionVersion = "1.0"`)

**Decision**: `설계/NautilusTrader 설계.md`의 「지표 계산 규약」 절을 v1.0으로 채택하고, 스펙 FR-002가 요구하는
`profitFactor`를 추가한다. 이 규약은 **값을 만드는 Worker와 공유하는 계약**이다. 백엔드는 계산하지 않고 저장·조회만 한다.
후속 티켓에서 `docs/specs/`로 승격한다(스펙 Assumptions: 지표 정의 문서는 `docs/specs/`에 둔다).

### 2.1 기준 시계열

- 기준은 백테스트 기간 내 **모든 거래일**의 **종가 기준 총 자산 가치 `equity[t]`** (현금 + 보유 종목 평가액, KRW 정수).
- `dailyReturn[t] = equity[t] / equity[t-1] - 1`, 첫 거래일은 `0`.
- 거래가 없는 날에도 포인트를 생성한다(FR-007).
- 첫 포인트의 `equity`는 `initialEquity`(= 요청 `initialCash`)와 같아야 한다(FR-007).

### 2.2 지표 정의

| 지표 | 계산 | 단위 | 우수 방향 | 계산 불가(null) 조건 |
|---|---|---|---|---|
| `totalReturn` | `finalEquity / initialEquity - 1` | 비율 | 높을수록 | `initialEquity <= 0` |
| `cagr` | `(finalEquity / initialEquity) ^ (365 / 달력 경과일수) - 1`. 경과일수 = `endDate - startDate`(달력 일). 1년 미만도 그대로 연환산 | 비율 | 높을수록 | `initialEquity <= 0`, 경과일수 0 |
| `maxDrawdown` | `min(drawdown[t])`, `drawdown[t] = equity[t] / max(equity[0..t]) - 1`. **0 또는 음수** | 비율 | 0에 가까울수록 | 없음(하락이 없으면 정당한 값 0) |
| `maxDrawdownTroughDate` | `drawdown`이 최솟값인 거래일(여러 개면 첫 번째) | 날짜 | — | `maxDrawdown`이 0 또는 null → null("구간 없음") |
| `maxDrawdownPeakDate` | 저점 이전 구간에서 `equity == max(equity[0..trough])`인 마지막 거래일 | 날짜 | — | 같음 |
| `maxDrawdownRecoveryDate` | 저점 이후 `equity[t] >= equity[peak]`가 처음 성립하는 거래일 | 날짜 | — | 같음. 기간 종료까지 미회복이면 null(정당한 상태 "미회복", 스펙 Edge Case) |
| `volatility` | 일별 수익률의 표본표준편차(ddof = 1) × √252 | 비율 | 낮을수록 | 관측치(거래일) 2개 미만 |
| `sharpeRatio` | `(mean(dailyReturn) × 252 - riskFreeRate) / volatility`, `riskFreeRate = 0.0` | 배수 | 높을수록 | `volatility`가 null 또는 0 |
| `tradeCount` | 청산(CLOSED) 거래 수. 미청산 포지션 제외 | 횟수 | 우수 방향 없음 | 없음(0은 정당한 값) |
| `winRate` | `실현손익 > 0 인 청산 거래 수 / 청산 거래 수` | 비율 | 높을수록 | `tradeCount = 0` |
| `profitFactor` | `이익 거래 실현이익 합 / 손실 거래 실현손실 절대값 합` | 배수 | 높을수록 | `tradeCount = 0`, 손실 거래 없음(무한대 금지), 이익 거래 없음(0 금지, FR-011) |

시계열 포인트 필드:

| 필드 | 계산 |
|---|---|
| `equity` | 종가 기준 총 자산(KRW 정수) |
| `cumulativeReturn` | `equity[t] / equity[0] - 1` |
| `drawdown` | `equity[t] / max(equity[0..t]) - 1` (신고점에서 0) |

### 2.3 고정 상수

```
return frequency     : daily (거래일)
annualization factor : 252
risk-free rate       : 0.0
volatility ddof      : 1
CAGR 기간            : 달력 경과일 / 365
MDD 부호             : 0 또는 음수
통화                 : KRW, 금액은 정수
```

### 2.4 계산 불가 표현

계산이 정의되지 않으면 `0`·무한대가 아니라 **`null`** 을 저장·응답한다(FR-011). 이번 범위에서는 이유 필드를 두지
않는다. 이유(`unavailableReason`)는 US4에서 필드 추가(하위 호환)로 다룬다.

### 2.5 버전 관리

- 상수·계산식·계산 불가 조건이 하나라도 바뀌면 버전을 올린다(`"1.1"`, `"2.0"`).
- 이미 저장된 결과는 재계산하지 않고 원래 버전으로 응답한다(FR-005). 따라서 버전은 결과 행에 저장한다.

**Alternatives considered**:
- `riskFreeRate`를 국고채 금리로: 데이터 확보·기준일 관리가 필요해 MVP 범위를 넘는다. 버전 관리로 바꿀 수 있게 남긴다.
- 1년 미만 기간의 CAGR을 null로: 원 설계 문서 결정(값을 내려주고 프론트가 연환산임을 표시)을 따른다. 스펙 Edge Case가
  "두 방식을 혼용하지 말라"고 하므로 이 규약이 "값 제공" 한 가지로 고정한다.

---

## 3. 응답 값 타입

**Decision**: 비율·배수는 JSON number(`0.125` = 12.5%), 금액은 KRW 정수(JSON integer, Java `Long`), 횟수는 integer.
지표 필드는 nullable. 벤치마크 필드는 두지 않는다(FR-015).

**Rationale**: 원화는 소수가 없고 표시 전용이라 문자열 decimal의 정밀도 이점이 없다. 프론트 차트 라이브러리가 number를
직접 소비한다. 프론트 목데이터의 `'+124.8%'` 같은 문자열 포맷팅은 프론트 책임으로 넘긴다.

**Alternatives considered**: 원 설계 문서의 `decimal/string` 표기 — USD 소수 통화·다통화 전제였으므로 KRW 단일 통화 MVP에는
과하다. 다통화가 필요해지면 `currency` 필드 추가와 함께 재검토한다.

---

## 4. 저장 구조 — `backtest_result` 1테이블, `backtest_job`과 1:1

**Decision**: `backtest_result(id PK, backtest_job_id UNIQUE NOT NULL, summary 컬럼들, equity_curve JSON, created_at, updated_at)`.
`backtest_job_id`는 단순 `Long` 컬럼 + unique 제약으로 두고, JPA 연관(`@OneToOne`)은 걸지 않는다.

**Rationale**:
- 결과는 Job과 생명주기가 다르다(Job 생성 시점에는 없고 Worker가 나중에 쓴다). 별도 테이블이 자연스럽다.
- Worker(Python)가 이 테이블에 직접 INSERT할 예정이므로 JPA 연관 객체 없이 컬럼 계약만 있으면 된다.
- FK 제약은 `ddl-auto=update` 단계에서 강제하지 않고, Flyway 도입 시 마이그레이션에 추가한다(data-model.md에 명시).
- 요약 지표를 JSON이 아닌 개별 컬럼으로 두는 이유: 전략 비교(spec 004)에서 지표별 정렬·필터가 필요해질 가능성이 높다.

**Alternatives considered**:
- `backtest_job`에 결과 컬럼 추가: 상태 갱신과 결과 저장이 한 행에 섞여 Worker 쓰기 경합·NULL 컬럼 다수 발생.
- summary도 JSON 한 컬럼: 비교 기능에서 지표 컬럼 인덱스를 못 쓴다.

---

## 5. `GET /backtests/{id}`·`/timeseries` 상태별 동작

**Decision**:

| 상태 | `GET /backtests/{id}` | `GET /backtests/{id}/timeseries` |
|---|---|---|
| 없는 id | 404 `RESOURCE_NOT_FOUND` (기존) | 404 `RESOURCE_NOT_FOUND` |
| QUEUED / RUNNING / FAILED | 200, `summary: null` | 409 `RESULT_NOT_READY` |
| COMPLETED, 결과 행 있음 | 200, `summary` 채움 | 200, `points[]` |
| COMPLETED, 결과 행 없음(비정상) | 200, `summary: null` | 409 `RESULT_NOT_READY` |

**Rationale**: 상태 조회는 폴링용이므로 어떤 상태든 200이어야 한다(기존 비동기 계약). 시계열은 차트 전용이라 "아직 없음"을
상태 코드로 구분해 주는 편이 프론트 분기가 단순하다. 409는 "리소스의 현재 상태와 충돌"이라는 의미에 맞고, 404와 구별되어
프론트가 재시도(폴링)와 오류를 다르게 처리할 수 있다. COMPLETED인데 결과 행이 없는 경우는 Worker 저장 실패 등 비정상이지만
클라이언트 입장에서는 "아직 없음"과 같으므로 같은 코드로 처리하고 서버 로그에 warn을 남긴다.

**Alternatives considered**: 시계열도 200 + 빈 배열 — "포인트 0개"와 "아직 없음"을 구분할 수 없어 FR-011 취지에 어긋난다.
425 Too Early — 의미가 TLS early data에 특화되어 있어 부적절.

---

## 6. `initialCash` 추가와 기존 데이터·Worker 호환

**Decision**:
- 요청 `initialCash`는 optional(`Long`, `@Positive`), 미지정 시 컨트롤러가 `10_000_000`을 채워 command로 넘긴다.
- `BacktestJob` 도메인에 `initialCash`(> 0 불변식) 추가, `create`/`restore` 시그니처 변경.
- `backtest_job.initial_cash BIGINT NOT NULL`에 `@ColumnDefault("10000000")`을 붙인다.

**Rationale**: `ddl-auto=update`는 기존 행이 있는 테이블에 `NOT NULL` 컬럼을 추가할 때 DEFAULT가 없으면 PostgreSQL에서
실패한다. `@ColumnDefault`가 DDL에 `default 10000000`을 넣어 dev DB의 기존 행이 그대로 살아남는다. Worker `models.py`에는
`initialCash`가 없지만 백엔드가 항상 값을 채워 발행하므로 Worker는 알 수 없는 필드를 무시하면 호환된다(pydantic 기본 동작).
Worker 쪽 필드 추가는 plan.md「Worker 동기화 항목」으로 넘긴다.

**Alternatives considered**: 요청에서 `initialCash`를 필수로 — 프론트가 아직 보내지 않으므로 기존 요청이 깨진다.

---

## 7. dev 시더 데이터

**Decision**: `DevSampleResultSeeder`(`@Profile("dev")`, `ApplicationRunner`)가 기동 시 `backtest_result`에 행이 하나도 없을 때만
예시 전략 3개(`trend`, `dualMomentum`, `rsiMeanReversion`)의 COMPLETED Job + 결과를 삽입한다. 값은 `frontend/src/data/backtestMock.ts`에서
환산한다.

| 항목 | 환산 |
|---|---|
| Job | `strategy` = 목데이터 id, 기간 2019-01-01 ~ 2023-12-31, `initialCash` 10,000,000, 상태 COMPLETED |
| 시계열 | `rawPoints` 20개(분기). `equity = 지수 × 100,000`(지수 100 → 10,000,000), `cumulativeReturn = 지수/100 − 1`, `drawdown = 지수/직전최고 − 1` |
| `initialEquity` / `finalEquity` | 첫·마지막 포인트 equity (trend 22,480,000 / dualMomentum 18,460,000 / rsiMeanReversion 16,240,000) |
| `totalReturn` | 목데이터 문자열 환산: 1.248 / 0.846 / 0.624 (= 마지막 포인트에서 계산한 값과 일치) |
| `cagr`, `volatility`, `sharpeRatio`, `tradeCount` | 목데이터 값 환산: 0.184·0.124·1.42·156 / 0.132·0.142·1.05·89 / 0.108·0.181·0.78·234 |
| `maxDrawdown` | **시계열에서 도출**: −0.1261 (119→104) / −0.1171 (111→98) / −0.0795 (151→139) |
| 낙폭 구간 3필드 | **시계열에서 도출**: trend 고점 2020-01-01 · 저점 2020-04-01 · 회복 2020-07-01 / dualMomentum 2020-01-01 · 2020-04-01 · 2020-07-01 / rsiMeanReversion 고점 2021-10-01 · 저점 2022-04-01 · 회복 2022-07-01 |
| `winRate`, `profitFactor` | `null`(목데이터에 없음 = 계산 불가) |
| `metricDefinitionVersion` | `"1.0"` |

**Rationale**:
- 스펙 FR-010·SC-004는 시계열에서 도출한 총 수익률·최대 낙폭이 지표 값과 일치해야 한다고 요구한다. 목데이터의 `mdd`
  (−9.1% / −12.1% / −15.8%)는 분기 시계열과 모순되므로(예: trend는 2020-01→2020-04 구간에서 −12.6% 하락) 시계열 도출값을 쓴다.
  프론트 화면의 MDD 숫자는 목데이터와 달라진다. 이 점은 contracts 공유 시 프론트 담당자에게 알린다.
- 시계열이 분기 20포인트인 것은 자리표시자다. FR-007(모든 거래일)은 Worker가 실제 결과를 저장할 때 충족된다. 시더는 Worker
  저장 경로가 붙으면 삭제한다(클래스 Javadoc TODO, MR 리뷰 포인트).
- 멱등 기준을 "결과 행 존재 여부"로 두는 이유: Job만 있는 상태(사용자가 POST로 만든 QUEUED Job)에서도 시더가 예시 결과를
  채워 주어야 프론트 연동이 막히지 않고, 결과가 하나라도 있으면 이미 시딩된 것으로 본다.

**Alternatives considered**: 시계열을 목데이터 MDD에 맞게 조작 — 데이터를 지어내는 정도가 커지고 프론트 차트 곡선이 바뀐다.
`data.sql`로 삽입 — 도메인 불변식을 거치지 않아 잘못된 예시 데이터가 들어갈 수 있다. ApplicationRunner가 도메인 모델을 거쳐
저장하면 시드 데이터도 불변식(첫 값 = initialEquity 등) 검증을 받는다.

---

## 8. 계층 배치 세부

**Decision**:
- 도메인 불변식은 **저장값 사이의 일관성**만 검사한다: `equityCurve` 비어 있지 않음, 날짜 strictly ascending, 첫 포인트 `equity`
  = `summary.initialEquity`, 마지막 포인트 `equity` = `summary.finalEquity`, `maxDrawdown ≤ 0`, `winRate ∈ [0, 1]`,
  `tradeCount ≥ 0`, `profitFactor ≥ 0`, `metricDefinitionVersion` 비어 있지 않음, 낙폭 구간 날짜 순서(`peak ≤ trough < recovery`)와
  `maxDrawdown`과의 동반 규칙(음수면 고점·저점 필수, 0·null이면 전부 null). 지표를 다시 계산해 비교하지는 않는다
  (부동소수 오차·헌장 I).

**낙폭 구간 필드를 이번에 넣는 이유(2026-09-03 결정)**: 스펙 FR-008·US2 시나리오 4가 요구하는 항목인데 원 설계 문서의 summary
목록에서 빠져 있었다(`/speckit-analyze` C1). 프론트가 `points[].drawdown`에서 도출하게 하는 대안도 있었지만, Worker가 drawdown 배열을
만들 때 세 날짜를 함께 얻을 수 있고 나중에 붙이면 테이블·Worker 계약을 두 번 바꿔야 하므로 지금 계약에 포함한다.
- `BacktestService.get`은 Job을 읽고 COMPLETED면 `BacktestResultRepository.findByBacktestJobId`로 summary를 조합한다.
  `getTimeseries`는 Job 404 → 상태 검사 409 → 결과 조회 409 → `EquityCurveResult` 순서로 처리한다.
- 서비스 조회 메서드에 `@Transactional(readOnly = true)`를 붙인다(`open-in-view: false`이므로 JSON 컬럼 역직렬화가 트랜잭션
  안에서 끝나게).
- `application/result`와 `presentation/response`는 별도 record로 두고 컨트롤러가 변환한다(ADR-0001 DTO 경계).

**Alternatives considered**: `BacktestJob` 도메인이 `BacktestResult`를 포함(애그리거트 하나) — Job 조회마다 결과 JSON을 읽게 되어
폴링 비용이 커진다. 분리하고 서비스에서 조합한다.

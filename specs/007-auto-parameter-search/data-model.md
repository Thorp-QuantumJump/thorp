# Phase 1 Data Model: 설정 조합 자동 비교

**Date**: 2026-09-10 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

spec의 Key Entities를 저장 구조로 옮긴다. 저장 규칙은 `V2__backtest_result.sql`의 규약을 그대로
따른다 — 질의(필터·정렬·조인)에 쓰는 값만 컬럼, 표시용 값은 JSONB, **decimal은 예외 없이 JSONB**
(NUMERIC은 scale을 임의로 정해야 하고 그 자릿수를 넘는 값을 조용히 반올림한다).

## 1. 엔티티 관계

```text
users (기존, 인증 도입 후 연결)
  └─ 0..N param_search            owner_user_id NULL 허용 (R4)

param_search 1 ──── N search_candidate
     │                     └─ 0..1 backtest_job (기존)  후보 1건 = Job 1건 (R6)
     └─ 0..1 backtest_job (기존)  동일 비중 기준 (FR-013d)

backtest_job 1 ──── 0..1 backtest_result (기존)  성과 숫자의 원본
```

007은 성과 숫자를 자기 테이블에 복제하지 않는다. 후보의 수익·하락 폭·거래비용은 `backtest_result`
가 원본이고, 007이 저장하는 것은 **묶음 맥락에서만 의미가 있는 값**(그리드 좌표, 상태와 사유,
안정성, 선정 여부와 이유)이다. 복제하면 004가 결과를 재저장할 때 두 값이 갈린다.

## 2. `param_search` (탐색 묶음)

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK | SEQUENCE. `backtest_job`과 같은 방식 |
| `search_id` | VARCHAR(32) UNIQUE | 외부 노출 식별자. 사용자에게 입력을 요구하지 않는다(원칙 VIII) |
| `owner_user_id` | BIGINT NULL | 인증 도입 전 NULL (R4) |
| `strategy_ids` | JSONB NOT NULL | 고정한 전략군 |
| `instrument_ids` | JSONB NOT NULL | 고정한 섹터 후보 집합 |
| `registry_version` | VARCHAR(64) NOT NULL | 조회한 등록표 버전. **접수 시점에 고정한다**(FR-028). 임시 구현은 `PROVISIONAL-*` (R1) |
| `search_space` | JSONB NOT NULL | 전략별 축과 **실제 사용한 탐색 후보값**. 아래 §2.1 (FR-020, FR-028) |
| `search_method` | VARCHAR(32) NOT NULL | 현재 `FULL_GRID` 고정 (FR-005) |
| `random_seed` | INTEGER NULL | 현재 항상 NULL. 무작위 표본 도입 시 채운다(FR-020) |
| `selection_rule` | JSONB NOT NULL | 접수 시점 스냅샷. 아래 §5 |
| `evaluation_split` | JSONB NULL | 미등록이면 NULL → 전 후보 '미검증' (R5) |
| `limits` | JSONB NOT NULL | 적용 한도 스냅샷(후보 수 상한·발행 창·실행시간 상한)과 임시값 표시 |
| `reference_versions` | JSONB NOT NULL | 시작 시점의 자료·등록표·비용·엔진 버전 (FR-012) |
| `execution_plan` | JSONB NOT NULL | 접수 시점에 고정한 실행 조건. 아래 §2.2 (2026-09-11 추가, FR-007·FR-012) |
| `baseline_job_id` | BIGINT NULL FK → `backtest_job(id)` | 동일 비중 기준 (FR-013d) |
| `status` | VARCHAR(20) NOT NULL | §3 상태 |
| `stop_reason` | VARCHAR(32) NULL | `CANCELLED` `CANDIDATE_LIMIT` `TIME_LIMIT` `FAILURE_LIMIT` `NO_VALID_CANDIDATE` `STRATEGY_NOT_REGISTERED` `STRATEGY_NOT_EXECUTABLE` (뒤 둘은 2026-09-11 추가, FR-026) |
| `counts` | JSONB NOT NULL | 예상·유효·완료·실패·제외·미실행 (FR-008) |
| `selection` | JSONB NULL | 선정 결과 스냅샷. 완료 후 1회 계산해 저장 |
| `data_version_changed` | BOOLEAN NOT NULL | 계산 중 자료 갱신 발생 표시 (FR-012) |
| `started_at` / `finished_at` | TIMESTAMP WITH TIME ZONE NULL | 실행시간 상한 판정과 실행 측정(FR-023) |
| `created_at` / `updated_at` | TIMESTAMP NOT NULL | `BaseEntity` 규약 |

인덱스: `search_id` UNIQUE, `(owner_user_id, created_at DESC)` — 006 목록 조회 대비.

**`counts`를 컬럼이 아니라 JSONB로 두는 이유**: 6개 값이 함께 갱신되고 개별 필터·정렬 대상이 아니다.
표시용 묶음이므로 V2 규약의 "표시용 값은 JSONB"에 해당한다.

### 2.1 `search_space` 구조 (2026-09-11 개정, FR-027·FR-028)

한 묶음이 전략 여러 개를 담을 수 있고 전략마다 축 구성이 다르므로 **전략별로 축을 따로 저장한다.**
초판의 평면 구조(`축 → 값`)는 전략이 둘 이상이면 축 이름이 충돌한다.

```json
{
  "sector-momentum": {
    "strategyName": "섹터 모멘텀",
    "axes": [
      { "name": "lookbackDays", "label": "관측 기간", "unit": "거래일", "values": [20, 60, 120] },
      { "name": "topN", "label": "선정 섹터 수", "unit": null, "values": [2, 3, 4] }
    ]
  }
}
```

- **축을 배열로 저장하는 이유**: `jsonb`는 객체 키 순서를 보존하지 않는다(정렬해 저장한다). 축을
  객체 키로 두면 순서가 흔들린다. 배열은 순서를 지키고, **각 축 `values` 배열의 순서가
  `grid_coordinates` 인덱스의 뜻**이다(R7, FR-029).
- 값은 등록표가 돌려준 후보값을 **그대로** 담는다. 007이 정렬·보간·단위 변환을 하지 않는다(FR-025).
- `label`·`unit`·`strategyName`을 함께 남기는 이유: 등록표 버전이 올라가 축 이름이나 표시 문구가
  바뀐 뒤에도 그 묶음이 무엇을 탐색했는지 읽을 수 있어야 한다(FR-020, 원칙 VIII).
- **전체 조합 수는 전략별 (축별 값 개수의 곱)의 합**이다. 전략 하나뿐일 때 초판 계산과 같다.
- 등록표에 축이 없는 전략(파라미터 없음)은 `axes: []`이고 조합 1건이다.

### 2.2 `execution_plan` 구조 (2026-09-11 추가, FR-007·FR-012)

후보와 기준을 조립하는 데 필요한 값 전부다. **V3에는 이 자리가 없었다** — 전략·탐색 공간·선정 규칙·참조
버전만 있고 공통 구간·금액·비용 조건·대응표 버전·공통 워밍업을 담을 곳이 없었다.

```json
{
  "universe": [{ "sectorId": "SEMICONDUCTOR", "instrumentId": "091160.XKRX" }],
  "mappingVersion": "SECTOR-ETF-2026-09",
  "start": "2019-08-30", "end": "2026-08-31",
  "initialCash": "10000000.00",
  "costProfileVersion": "v1",
  "warmupTradingDays": 252,
  "registryVersion": "PROVISIONAL-20260911",
  "rulesVersionByStrategy": { "EQUAL_WEIGHT": "1" },
  "baselineStrategyId": "EQUAL_WEIGHT",
  "baselineParameters": {}
}
```

- **왜 컬럼인가**: 후보는 발행 창 단위로 며칠에 걸쳐 나갈 수 있고, 재발행·재시도는 기동 뒤에도 같은
  조건을 다시 조립해야 한다. 발행할 때마다 현재 설정을 읽으면 한 묶음 안의 후보들이 서로 다른 조건으로
  계산되어 나란히 놓은 숫자가 비교 가능한 값이 아니게 된다(원칙 IV).
- **금액은 문자열**이다. JSON 숫자로 두면 읽을 때 배정밀도로 돌아와 자릿수를 잃는다(V2 규약과 같다).
- **값이 없는 항목은 키를 넣지 않는다.** 저장 ObjectMapper가 null 키를 생략하므로 넣어 두면 저장 전후의
  값이 달라진다. 읽는 쪽은 "키 없음 == null"로 본다(메시지 JSON 규약과 같다).
- `rulesVersionByStrategy`는 004가 준 값이다. 007이 버전을 고르면 004 등록표와 갈린다.
- `baselineStrategyId`는 등록표가 기준 전략 역할로 지정한 값이며 없으면 키가 없다 — 그때는 기준 실행을
  만들지 않고 `drawdownFilterApplied = false`다(FR-013d·FR-025).

## 3. `param_search.status` 상태 전이

```text
QUEUED ──▶ RUNNING ──▶ COMPLETED          모든 유효 후보가 완료 또는 실패로 끝남
                   ├─▶ PARTIAL            취소·한도 도달로 미실행 후보가 남음
                   └─▶ FAILED             접수 후 실행 자체가 불가 (기준 발행 실패 등)
QUEUED ──▶ REJECTED                       접수 시점 거부 (상한 초과·유효 조합 0건·미등록 전략·실행 미지원 전략)
```

- `REJECTED`는 저장하되 후보를 만들지 않는다. 상한 초과(FR-005b), 유효 조합 전무(Edge Case),
  미등록 전략과 실행 미지원 전략(FR-026)이 여기 들어가며 `stop_reason`에 사유가 남는다. 등록표
  조회 실패는 여기 들어가지 않는다 — 일시적 장애라 묶음을 만들지 않고 503만 돌려준다(research R9).
- `PARTIAL`은 부분 결과임과 완료 범위를 표시하는 근거다(FR-009).
- `COMPLETED`와 `PARTIAL` 둘 다 선정 계산을 수행한다. 취소된 묶음도 완료된 후보로 비교할 수 있다.

## 4. `search_candidate` (탐색 후보)

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK | SEQUENCE, `allocationSize` 50 (후보가 묶음당 수백~천 건) |
| `param_search_id` | BIGINT NOT NULL FK | |
| `candidate_no` | INTEGER NOT NULL | 묶음 내 순번. `(param_search_id, candidate_no)` UNIQUE |
| `strategy_id` | VARCHAR(64) NOT NULL | |
| `parameters` | JSONB NOT NULL | 파라미터 조합 하나 |
| `grid_coordinates` | JSONB NOT NULL | 축 이름 → `search_space`의 값 인덱스. 이웃 판정의 근거 (R7). 좌표의 뜻은 `strategy_id` 안에서만 유효하다 — 이웃은 같은 전략 안에서만 찾는다 |
| `backtest_job_id` | BIGINT NULL FK → `backtest_job(id)` | 발행 전·제외 후보는 NULL |
| `status` | VARCHAR(20) NOT NULL | §4.1 |
| `reason_code` | VARCHAR(64) NULL | 제외·실패 사유 코드 |
| `reason_detail` | TEXT NULL | 사람이 읽는 사유 |
| `stability` | JSONB NULL | 안정성 값·이웃 수·`calculable` 여부 (FR-013a·FR-013b) |
| `retry_count` | INTEGER NOT NULL | 재시도 횟수. 기본 0 (FR-024) |
| `period_performance` | JSONB NULL | 구간별(내부 개발·외부 평가·잠금) 성과 자리. 현재 NULL (R5) |
| `validation_label` | VARCHAR(24) NOT NULL | `SEARCH_RESULT` `HELD_OUT_RESULT` `UNVALIDATED`. 현재 전부 `UNVALIDATED` |
| `selected` | BOOLEAN NOT NULL | 표시 후보 여부 |
| `selection_reason` | JSONB NULL | 선정·탈락 사유(어느 필터에서 탈락했는지, 동률 여부) |
| `created_at` / `updated_at` | TIMESTAMP NOT NULL | |

인덱스:
- `(param_search_id, candidate_no)` UNIQUE
- `(param_search_id, status)` — 집계와 다음 발행 대상 조회
- `(param_search_id, selected)` — 표시 후보 조회
- `backtest_job_id` (NULL 제외 부분 인덱스) — 결과 이벤트가 어느 후보의 것인지 찾는 경로.
  **UNIQUE가 아니다**: 004가 같은 조건의 요청을 살아 있는 실행에 합류시키거나 완료 결과를 재사용하므로
  (spec 004 R-02) 실행 하나에 후보가 여럿 걸린다 — 서로 다른 묶음의 것일 수도 있다. 한때 UNIQUE였고,
  그래서 같은 조건을 두 번 접수하는 것만으로 접수가 500이었다(S15P21E205-221)

### 4.1 `search_candidate.status` 상태 전이

```text
PENDING ──▶ DISPATCHED ──▶ COMPLETED      결과 이벤트 status=COMPLETED
                       └─▶ FAILED         결과 이벤트 status=FAILED 또는 자료 부족
PENDING ──▶ SKIPPED                       취소·한도 도달로 발행되지 않음 ('미실행')
FAILED  ──▶ DISPATCHED                    사용자 요청 재시도. retry_count 증가 (FR-024)
(생성 시) ─▶ EXCLUDED                     유효성 위반으로 계산 전 제외 (FR-006)
```

- `EXCLUDED`는 생성 시점에 확정되고 `backtest_job_id`가 없다. 계산하지 않은 후보도 사유를
  남긴다(FR-020).
- `FAILED`와 `EXCLUDED`는 낮은 수익으로 표시하지 않는다(FR-011). 성과 값 자리를 0으로 채우지 않는다.
- `SKIPPED`가 spec의 '미실행'이다. 취소 시 큐에 아직 넣지 않은 후보가 여기로 간다(R2).
- **재시도는 `FAILED`에서만 출발한다**(FR-024). `SKIPPED`는 재시도 대상이 아니다 — 취소·한도로 빠진 후보를 되살리면 그 묶음의 완료 범위 표시가 뜻을 잃는다. `retry_count`는 재현 기록의 일부다.

## 5. `selection_rule` 스냅샷 (JSONB)

접수 시점에 고정하고 이후 바꾸지 않는다(FR-013c, 원칙 VII). 결과를 본 뒤 바꾸면 새 묶음이다.

```json
{
  "drawdownFilter": {
    "type": "NOT_WORSE_THAN_BASELINE",
    "baselineStrategyId": "<등록표가 기준 전략 역할로 지정한 ID>",
    "baselineStrategyName": "<표시용. 등록표 값>"
  },
  "stabilityFilter": { "type": "BATCH_PERCENTILE", "keepTopPercent": 50 },
  "sortMetric": "NET_RETURN",
  "displayCount": 5,
  "tieHandling": "SHOW_ALL_TIED",
  "stabilityFormula": {
    "neighbor": "SINGLE_AXIS_ONE_STEP",
    "value": "MEAN_ABS_NET_RETURN_DIFF"
  },
  "provisional": ["displayCount"]
}
```

절대 문턱 숫자가 없다(FR-013). `provisional` 배열은 임시값인 항목을 밝혀 화면 표시에 쓴다.

## 6. 검증 규칙 (요구사항에서 나온 것)

| 규칙 | 근거 | 적용 지점 |
|---|---|---|
| 유효 후보 수 > 후보 수 상한이면 접수 거부, 표본 추출·간격 확대 금지 | FR-005b | `ParamSearchService.create` 전 `GridExpander` 결과 검사 |
| 유효성 위반 조합은 계산 전 `EXCLUDED` + 사유 | FR-006 | `CandidateValidator`가 `ParameterRegistryPort.validate()` 결과를 그대로 쓴다. 판정 규칙을 007에 두지 않는다(FR-025) |
| 등록표에 없는 전략은 조합 생성 전 거부, `status=REJECTED` + `stop_reason=STRATEGY_NOT_REGISTERED` | FR-026 | `SearchTargetResolver` |
| 004가 실행을 지원하지 않는 전략은 조합 생성 전 거부, `stop_reason=STRATEGY_NOT_EXECUTABLE` | FR-026 | 같은 지점. 두 사유를 구분해 남긴다 |
| 등록 정보·지원 목록 조회 실패는 묶음을 저장하지 않고 `503 REGISTRY_UNAVAILABLE`. 캐시·기본값으로 대체 실행 금지 | FR-028, R9 | 접수 진입점 |
| 미리보기 때 본 등록표 버전과 접수 시점 버전이 다르면 `409 REGISTRY_VERSION_CHANGED` | FR-028, R9 | 접수 진입점 |
| 기본 시작값·입력 허용 범위는 등록표 값을 그대로 쓴다 | FR-027 | 007에 기본값 상수를 두지 않는다 |
| 이웃 인덱스의 뜻은 등록표가 준 후보값 순서에서만 온다 | FR-029 | `search_space.axes[].values` 순서 |
| 유효 조합 0건이면 실행하지 않고 어느 제약 때문인지 한 문장 | Edge Case | 같은 지점, `status=REJECTED` |
| 모든 후보와 기준이 같은 섹터 집합·공통 구간·공통 워밍업(`warmupTradingDays`)·금액·비용·참조 버전 | FR-007, FR-013d, 004 R-15 | `CandidateJobLauncher`가 묶음 스냅샷만 보고 조립 |
| 묶음은 시작 시점 참조 버전으로 끝까지 계산 | FR-012 | `reference_versions` 스냅샷 + 갱신 시 `data_version_changed` |
| 이웃 없는 후보는 안정성 `calculable=false`, 0으로 채우지 않고 필터 통과 | FR-013b | `NeighborStabilityCalculator` |
| 문턱 통과 후보 < 5면 그 수만, 0이면 '기준 충족 후보 없음' | Edge Case | `CandidateSelector` |
| 동률은 전부 표시, 임의 순서 금지 | Edge Case, R3 | `CandidateSelector` |
| 기준 백테스트 실패 시 하락 폭 필터 미적용 + 사실 표시 | Edge Case | `CandidateSelector` |
| 기준 전략 역할이 등록표에 지정돼 있지 않으면 하락 폭 필터 미적용 + 사실 표시. 기준 전략을 007이 추정하지 않는다 | FR-013d, FR-025 | 접수 시 스냅샷 고정, `CandidateSelector` |
| 평가 분할 미등록이면 전 후보 `UNVALIDATED` | FR-014, R5 | 후보 생성 시 기본값 |
| 재시도는 시작 시점 스냅샷을 재사용하고 새 묶음을 만들지 않는다 | FR-024 | `ParamSearchService.retryFailed` |
| 재시도 완료 후 선정을 다시 계산하되 선정 규칙은 바꾸지 않는다 | FR-024, FR-013c | `CandidateSelector` 재실행 |

## 7. 기존 테이블 변경

`param_search`에 `execution_plan` 컬럼을 더한다(2026-09-11, §2.2). V3은 이미 병합돼 있으므로 고치지
않고 타임스탬프 마이그레이션으로 덧붙인다 — 기존 행이 있을 수 있어 `NOT NULL DEFAULT '{}'::jsonb`다.

`backtest`는 그대로다. `backtest_job`·`backtest_result`·`backtest_trade`는 그대로 쓴다.

`search_candidate.backtest_job_id`가 `backtest_job(id)`를 참조하는 단방향이며, `backtest` 모듈은
`paramsearch`를 알지 못한다. 묶음에 속한 Job과 단일 실행 Job은 `backtest_job`에서 구분되지 않는데,
이는 의도한 것이다 — 004의 실행 경로가 묶음 개념을 알면 의존 방향이 섞인다. 묶음 소속 여부가
필요한 조회는 `search_candidate`에서 조인한다.

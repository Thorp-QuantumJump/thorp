# Data Model: 경제환경 사례와 전략 비교 연결

**Spec**: [spec.md](spec.md) · **Plan**: [plan.md](plan.md) · **Research**: [research.md](research.md)

저장 규칙은 `backend/CLAUDE.md`를 따른다 — 조회·필터·조인에 쓰는 값만 컬럼, 소수·시계열·구조는
JSONB, 시각은 TIMESTAMPTZ, 거래일은 DATE, 소수는 문자열 decimal. 사용자 식별자는 기록 성격의 표
(`regime_link_search`·`regime_link`)에만 두고 계산 결과(`regime_link_result`)에는 두지 않는다
(원칙 VIII). 011은 **표 넷을 만들고 010·004 표는 읽기만 한다.**

## 1. 전체 그림

```
regime_search_snapshot (010) ──┐
regime_search_case     (010) ──┤ 읽기
backtest_run / _result / _trade (004) ──┤
                                        ▼
regime_link_filter ──< regime_link_search ──< regime_link ──1:1── regime_link_result
   (가용성 필터 키)      (검색 경로별 연결 기록)  (실행×사례×시나리오)    (워커 산출물)
```

- **`regime_link_search`**는 "어느 검색에 어느 비교 묶음을 어느 필터로 붙였나"다. 006이 읽는
  경제환경 탐색 기록의 011 몫이며 검색 경로별 별도 행이다(006 Key Entities).
- **`regime_link`**는 사용자가 실제로 연 (실행 × 사례 × 시나리오) 하나다. 상태 전이의 단위이고
  잘라내기 작업의 단위다.
- **`regime_link_result`**는 워커가 돌려준 값이다. 링크가 COMPLETED일 때만 있다.

## 2. `regime_link_filter` — 가용성 필터

| 컬럼 | 타입 | 규칙 |
|---|---|---|
| `id` | BIGINT PK | SEQUENCE |
| `filter_key` | CHAR(64) NOT NULL UNIQUE | `sha256(canonical JSON(definition))`의 16진. research R4 |
| `definition` | JSONB NOT NULL | `{instruments: [{instrumentId, listedOn}] (정렬), maxWarmupTradingDays, minPostTradingDays: 21, mappingVersion, dataVersion, dataAsOf}` |
| `available_range` | JSONB NOT NULL | `{firstCaseEndMonth, lastCaseEndMonth}` — '선택 ETF로 비교 가능한 기간'(FR-003). 계산 근거는 `definition`이다 |
| `created_at` | TIMESTAMPTZ NOT NULL | |

같은 비교 묶음·같은 자료면 같은 행을 재사용한다(키 일치). 성과와 무관한 값만 들어 있어야 한다 —
`definition`에 실행 결과 필드를 넣지 않는다(FR-003 "성과에 의존하지 않는다").

## 3. `regime_link_search` — 검색 경로별 연결 기록

| 컬럼 | 타입 | 규칙 |
|---|---|---|
| `id` | BIGINT PK | SEQUENCE. 외부 식별자는 `search_id` |
| `search_id` | VARCHAR(32) NOT NULL UNIQUE | `RLS-YYYYMMDD-NNNNNN`. 내부 PK를 노출하지 않는다(ADR-0002 D2) |
| `owner_user_id` | BIGINT NOT NULL | 목록 확정 시 로그인 사용자 기록을 만든다(FR-021·025). 익명은 행을 만들지 않는다 |
| `entry_path` | VARCHAR(24) NOT NULL | `MARKET_FLOW` \| `STRATEGY_RESULT` — 두 경로의 결과를 따로 보존(FR-004) |
| `snapshot_id` | BIGINT → `regime_search_snapshot(id)` ON DELETE SET NULL | 010 revision 고정 |
| `snapshot_key` | VARCHAR(200) NOT NULL | 010 키의 **사본**. 철회로 `snapshot_id`가 NULL이 돼도 무엇을 봤는지 남는다 |
| `mode` / `field_id` / `baseline_month` | VARCHAR(16) / VARCHAR(32) / DATE | 010 검색 식별의 사본(조회용) |
| `base_run_id` | BIGINT NOT NULL → `backtest_run(id)` | 비교 기준 실행 |
| `compare_run_id` | BIGINT → `backtest_run(id)` | 비교 대상 실행. 없으면 NULL. **최대 2개**(Clarifications 09-17 Q1) |
| `filter_id` | BIGINT → `regime_link_filter(id)` | `STRATEGY_RESULT` 경로면 NOT NULL. `MARKET_FLOW`는 필터가 없어 NULL |
| `shown_cases` | JSONB NOT NULL | `[{endMonth, rank, availability: {state, reason?}, linkIds?: [...]}]` — 010 표시 후보 ≤10에 판정을 붙인 그대로. 순위는 010 원래 값 |
| `excluded_count` | SMALLINT NOT NULL | 표시 후보 중 공통 가용 범위 필터로 빠진 수(FR-003) |
| `full_search_shown` | BOOLEAN NOT NULL DEFAULT false | 사용자가 '전체 경제환경 탐색으로 전환'을 눌렀는지 |
| `previous_search_id` | BIGINT → `regime_link_search(id)` | 비교 묶음 변경으로 새 검색이 됐을 때 이전 행(FR-021) |
| `retention` | JSONB | `{ratio, kept: [endMonth], added: [...], removed: [...]}` — **기록 전용, 화면 비표시**(FR-021). 첫 검색이면 NULL |
| `basis` | VARCHAR(24) NOT NULL | `HISTORICAL_REVISION` 고정(FR-015). 당시 재현은 범위 밖 |
| `client_request_id` | VARCHAR(64) NOT NULL | 요청자별 검색 생성 멱등 키. UNIQUE(owner_user_id, client_request_id) |
| `created_at` | TIMESTAMPTZ NOT NULL | |

목록 확정 시 생성하고 결과가 0건이어도 저장한다. previous_search_id는 클라이언트가 같은 탐색 흐름에서 전달한 값만 사용하며 소유자·snapshot·경로를 검증한다. 링크 생성은 기존 search_id를 참조한다(REST §1b).

인덱스: `(owner_user_id, created_at DESC)`, `(base_run_id)`, `(snapshot_key)`.

## 4. `regime_link` — (실행 × 사례 × 시나리오)

| 컬럼 | 타입 | 규칙 |
|---|---|---|
| `id` | BIGINT PK | SEQUENCE |
| `link_id` | VARCHAR(32) NOT NULL UNIQUE | `RL-YYYYMMDD-NNNNNN` |
| `search_id` | BIGINT NOT NULL → `regime_link_search(id)` | |
| `run_id` | BIGINT NOT NULL → `backtest_run(id)` | 잘라내기 입력 실행. 연속 운용이면 비교 묶음의 실행(또는 전체 기간 재계산 실행), 현금 시작이면 `FRESH_CASH_FROM` 실행 |
| `origin_run_id` | BIGINT NOT NULL → `backtest_run(id)` | 비교 묶음의 원래 실행. 전체 기간 재계산·현금 시작으로 `run_id`가 바뀌어도 "어느 전략의 결과인가"는 여기로 |
| `snapshot_key` | VARCHAR(200) NOT NULL | 사례 식별자 앞부분(사본) |
| `end_month` | DATE NOT NULL | 사례 식별자 뒷부분. 010 `regime_search_case.end_month`와 같은 뜻 |
| `scenario` | VARCHAR(16) NOT NULL | `CONTINUOUS`(전체 기간 연속 운용의 구간) \| `FRESH_CASH`(그날 현금 시작) |
| `post_window` | JSONB NOT NULL | `{d0, d21, d63, dataAsOf, completedTradingDays, complete21, complete63}` — d0 = 관측 종료 후 첫 거래일. research R4 달력으로 산정 |
| `availability` | JSONB NOT NULL | `{state: NORMAL\|DATA_INSUFFICIENT\|POST_INCOMPLETE\|OUT_OF_PERIOD, reason?: UNLISTED\|WARMUP_SHORT\|DELISTED, partial?: true, delistedOn?}` — 판정 시점의 저장 값. 재판정하지 않는다 |
| `status` | VARCHAR(16) NOT NULL | `PENDING_RUN` \| `QUEUED` \| `RUNNING` \| `COMPLETED` \| `FAILED`. §7 |
| `failure` | JSONB | `{code, message}`. code ∈ `INPUT_TOO_LARGE` `DATA_INSUFFICIENT` `ENGINE_ERROR` `SYSTEM_FAILURE` `RUN_FAILED`(입력 실행이 실패) |
| `job_message_id` | VARCHAR(64) | 마지막으로 발행한 작업 메시지 id (`RLJ-` + link_id + `-` + attempt) |
| `attempt` | SMALLINT NOT NULL DEFAULT 0 | 재시도 횟수 |
| `requested_by` | BIGINT NOT NULL | 처음 연 사용자. 같은 (실행×사례×시나리오)를 다른 사용자가 열면 **행을 재사용**하고 `regime_link_search`만 각자 |
| `queued_at` / `started_at` / `finished_at` | TIMESTAMPTZ | 측정 기록(R9) |
| `created_at` / `updated_at` | TIMESTAMPTZ NOT NULL | |

제약: `UNIQUE (run_id, snapshot_key, end_month, scenario)` — **같은 단위는 다시 계산하지 않는다**
(FR-010). `search_id`는 처음 만든 검색이며, 다른 검색이 같은 링크를 쓰면 `regime_link_search.shown_cases`
쪽에서 참조한다(링크 행은 하나).

**`availability.state`가 `DATA_INSUFFICIENT`이면 작업을 만들지 않는다.** 행은 만들어 화면이 '자료
부족' 사유를 보이되 `status`는 `COMPLETED`로 두고 `regime_link_result`는 없다 — 계산 실패와 자료
부족을 섞지 않는다(FR-018, Edge Cases).

## 5. `regime_link_result` — 워커 산출물

| 컬럼 | 타입 | 규칙 |
|---|---|---|
| `link_id` | BIGINT PK → `regime_link(id)` | 1:1 |
| `window63` | JSONB NOT NULL | `{tradingDays, return, maxDrawdown, tradeCount, tradesPerMonth, turnover, turnoverBasis: ANNUALIZED_252, costs: {commission, spread, slippage, tax, total}, lossContribution: {sectorId: value}, complete}` — 미완성이면 `tradingDays < 63`, `complete=false` |
| `window21` | JSONB NOT NULL | 같은 모양. 항상 완성(가용 조건이 ≥21) |
| `holdings` | JSONB NOT NULL | `[{date, actual: {instrumentId: w}}]` d0~마지막 완성일 |
| `trades` | JSONB NOT NULL | `[{date, instrumentId, side, quantity, price, cost, reason}]` 구간 안 |
| `kospi` | JSONB NOT NULL | `{window63: {return, maxDrawdown}, window21: {return, maxDrawdown}}` — 두 값만(FR-013). 차이 값 없음 |
| `basis` | JSONB NOT NULL | `{kind: "HISTORICAL_REVISION", dataVersion, datasetId, analyticsMethod}` |
| `input_digest` | CHAR(64) NOT NULL | 메시지에 담긴 입력 사본의 해시. 재실행 결과가 같은 입력에서 나왔음을 확인 |
| `computed_at` | TIMESTAMPTZ NOT NULL | |

수치는 전부 문자열 decimal이다. `lossContribution`은 전체 구간 손익이 0이면 **키를 생략**한다(0으로
채우지 않음, 010 `contribution` 규칙과 같다).

## 6. 값의 소유 경계

| 값 | 소유 | 011이 하는 일 |
|---|---|---|
| 사례 순위·거리·표시 후보·`snapshot_key` | 010 | 읽는다. 다시 매기지 않고 표시 후보 밖에서 뽑지 않는다 |
| 사후 구간 정의(d0·63·21) | 010(PD-08) | 정의를 그대로 쓰고 **날짜로 환산**해 `post_window`에 기록 |
| 백테스트 실행·결과·거래 | 004 | 읽어서 잘라낼 사본을 메시지에 담는다. 새 실행은 004 접수 서비스로 |
| 상장일·거래일 달력·자료 버전 | 001(`MarketMetadataPort`) | 판정에 읽는다 |
| 비교 묶음(기준·비교 대상) | 005 | 공개 토큰으로 받아 실행으로 푼다 |
| 가용성 판정·필터 키·연결 기록·구간 요약 | **011** | 만들고 저장한다 |
| 경제환경 탐색 기록 화면 | 006 | `regime_link_search`·`regime_link`를 읽는다(011은 화면을 만들지 않음) |

## 7. 상태 전이 (`regime_link.status`)

```
                 ┌─(availability=DATA_INSUFFICIENT)──────────────► COMPLETED (결과 없음, 사유만)
 생성 ──────────┤
                 ├─(scenario=CONTINUOUS, 입력 실행 COMPLETED)──► QUEUED ─► RUNNING ─► COMPLETED
                 │                                                  │            └─► FAILED ─(retry)─► QUEUED
                 └─(scenario=FRESH_CASH 또는 전체 기간 재계산 필요)─► PENDING_RUN ─(004 실행 COMPLETED)─► QUEUED …
                                                                        └─(004 실행 FAILED/CANCELLED)─► FAILED(RUN_FAILED)
```

- `PENDING_RUN`은 004 실행을 기다리는 상태다. 화면은 004 진행 상태(`progress`)를 그 자리에 보인다.
- `RUNNING`은 워커 `STARTED` 이벤트로, `COMPLETED`·`FAILED`는 `FINISHED`로 바뀐다. 종결 뒤 늦은
  이벤트는 기록만 하고 무시한다(004 규칙과 같다).
- DLQ 소비: 작업 메시지가 `regime-link.jobs.dlq`에 오면 `FAILED(SYSTEM_FAILURE)`.
- `retry`는 `FAILED`에서만. `attempt`를 올리고 새 `job_message_id`로 재발행. `RUN_FAILED`의 재시도는
  004 `retry`를 호출해 새 실행을 만든 뒤 `PENDING_RUN`으로.

## 8. 보관

| 대상 | 규칙 |
|---|---|
| `regime_link_search`·`regime_link` | 006 기록 정책(PD-13)을 따른다. 011이 보관 기간을 정하지 않는다 |
| `regime_link_result` | 링크와 같이 산다. 링크가 지워지면 CASCADE |
| 입력 실행(`backtest_run`)이 004 규칙으로 정리되면 | `regime_link.run_id`는 FK라 정리가 막힌다 — 004 정리 절차가 링크를 먼저 확인해야 한다. **확인 필요**: 004의 결과 정리 규칙이 아직 없어(005 PD-23은 비로그인 결과만) 지금은 충돌이 없다 |
| 010 snapshot 철회 | `snapshot_id` SET NULL, `snapshot_key` 사본으로 표시(010 FR-035 방식) |

## 9. 마이그레이션 메모

파일 하나(`V{YYYYMMDDHHMMSS}__regime_link.sql`). 표 넷과 인덱스·제약을 만든다. 010 표에 FK를
걸려면 010 M6 마이그레이션이 먼저 적용돼야 한다 — **010 M6 머지 전이면 `snapshot_id` FK를 이
마이그레이션에서 만들지 않고 컬럼만 두며, FK는 010 M6 뒤 별도 마이그레이션으로 덧붙인다**(운영에
적용된 변경은 수정하지 않는다, constitution 스키마 규칙). 되돌릴 수 없는 변경은 없다.

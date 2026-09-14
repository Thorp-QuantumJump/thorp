# Quickstart: 설정 조합 자동 비교 검증 가이드

**Date**: 2026-09-10 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

007이 동작하는지 종단으로 확인하는 절차다. 필드 의미와 응답 모양은
[contracts/param-search-api.md](contracts/param-search-api.md), 저장 구조는
[data-model.md](data-model.md)를 본다 — 여기서 다시 적지 않는다.

## 사전 조건

**004가 아직 없다는 것을 전제로 한 절차다.** 현재 워커는 단일 종목 momentum만 계산하므로 이 가이드는
섹터 1종목으로 확인한다. 004가 배분 전략을 추가하고 실행 지원 목록에 넣으면 `instrumentIds`를 늘려
같은 절차를 반복한다 — **007 코드를 고치지 않는다**(FR-026).

등록표 데이터는 `backend/src/main/resources/registry/parameter-registry-provisional.yml`에 있다.
축·후보값·기본값을 바꿔 보려면 그 파일만 고친다. 007 코드에는 전략·파라미터값이 없다(FR-025).

```bash
# 1. 인프라
docker compose -f docker-compose.dev.yml up -d postgres rabbitmq

# 2. 백엔드 (Flyway V3가 자동 적용된다)
cd backend && ./gradlew bootRun

# 3. 워커 — 후보를 실제로 계산할 주체. 2개 이상 띄워 competing consumers를 확인한다
cd worker && python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -e .
python -m worker.main     # 다른 터미널에서 한 번 더 실행

# 4. 프론트
cd frontend && npm install && npm run dev
```

확인: `docker compose -f docker-compose.dev.yml logs rabbitmq | tail`에 오류가 없고,
`http://localhost:15672`(thorp/thorp)의 `backtest.jobs`에 소비자 2개가 붙어 있다.

## 시나리오 1 — 확인 화면과 한 동작 실행 (US1, SC-001)

```bash
# 실행 전 확인. 후보 숫자를 하나도 넣지 않는다
curl -s -X POST localhost:8080/param-searches/preview \
  -H 'Content-Type: application/json' \
  -d '{"universe":{"instrumentIds":["091160.XKRX"]},"strategy":{"strategyIds":["INVERSE_VOLATILITY"]}}' | jq
```

**기대**: `data.strategies`·`spaceSummary`·`workload`·`limits` 네 덩어리가 채워진다.
`limits.provisional`에 `maxCandidates`가 들어 있고, `validationLabel`은 `UNVALIDATED`다.

```bash
# 같은 본문으로 실행
curl -s -X POST localhost:8080/param-searches \
  -H 'Content-Type: application/json' \
  -d '{"universe":{"instrumentIds":["091160.XKRX"]},"strategy":{"strategyIds":["INVERSE_VOLATILITY"]}}' | jq
```

**기대**: `202`, `{ "searchId": "PS-...", "status": "QUEUED" }`. `space`를 보내지 않았는데 접수됐다 —
이것이 SC-001이다.

```sql
-- 발행 창이 지켜지는지: DISPATCHED가 창 크기를 넘지 않는다 (내부 계약 §4 불변식 1)
SELECT status, count(*) FROM search_candidate
WHERE param_search_id = (SELECT id FROM param_search ORDER BY id DESC LIMIT 1)
GROUP BY status;
```

## 시나리오 2 — 유효성 위반 조합의 사전 제외 (US3, SC-002)

섹터 2개를 고르고 `topN`에 섹터 수보다 큰 값이 든 축을 포함해 실행한다.

```bash
curl -s -X POST localhost:8080/param-searches \
  -H 'Content-Type: application/json' \
  -d '{"universe":{"instrumentIds":["091160.XKRX","305720.XKRX"]},
       "strategy":{"strategyIds":["SECTOR_MOMENTUM"]},
       "space":{"topN":[2,3,4]}}' | jq
```

**기대**: 접수는 성공하고, `topN=3`·`topN=4` 후보가 `EXCLUDED` + `reason_code=TOP_N_EXCEEDS_UNIVERSE`로
남는다. `backtest_job` 행이 만들어지지 **않는다** — 계산 전 제외다.

```bash
curl -s "localhost:8080/param-searches/{searchId}/candidates?status=EXCLUDED" | jq '.data.content'
```

**기대**: 각 항목의 `netReturn`·`maxDrawdown`·`tradingCost`가 모두 `null`이다. **0이 아니다** —
0이면 SC-002·FR-011 위반이다.

> **2026-09-14 현재 이 시나리오는 돌릴 수 없다.** 위 요청은 `422 STRATEGY_NOT_EXECUTABLE` 로 끊긴다.
>
> 이 시나리오에는 **유효성 규칙을 가졌으면서 실행도 가능한 전략**이 필요한데, 임시 등록표에서 그 둘이
> 겹치지 않는다.
>
> | 전략 | 축·조합 | 유효성 규칙 | `strategy_rules` |
> |---|---|---|---|
> | `EQUAL_WEIGHT` | 0축·1조합 | 없음 | `REGISTERED` |
> | `FIXED_WEIGHT` | 0축·1조합 | 없음 | `REGISTERED` |
> | `INVERSE_VOLATILITY` | 1축·4조합 | 없음 | `REGISTERED` |
> | `SECTOR_MOMENTUM` | 2축·9조합 | `TOP_N_EXCEEDS_UNIVERSE` | **`PENDING_DECISION`** |
> | `MINIMUM_VARIANCE` | 2축·9조합 | `MAX_WEIGHT_TOO_LOW_FOR_UNIVERSE` | **`PENDING_DECISION`** |
>
> 규칙을 가진 둘은 계산 규칙이 팀 미확정(PD-03·PD-04)이라 004 가 실행을 지원하지 않고, 그래서
> `SearchTargetResolver` 가 **조합을 만들기도 전에** 거부한다(FR-026). 실행 가능한 셋에는 유효성
> 규칙이 아예 없어 제외될 조합 자체가 없다.
>
> **제외 경로 자체는 구현돼 있고 검증됐다** — `CandidateValidator` 가 등록표 판정을 그대로 쓰고,
> `ParamSearchRejectionIT.noValidCombinationIsRejectedWithTheReason` 이 모든 조합을 거르는 fixture
> 등록표로 그 경로를 확인한다. 막힌 것은 운영 등록표로 **손으로 재현하는 일**이다.
>
> **해소 조건**: PD-03 또는 PD-04 가 확정돼 `strategy_rules` 의 해당 행이 `REGISTERED` 가 되면 위
> 요청이 그대로 돈다. 그때 `space` 는 전략별 중첩이어야 한다 — 위 예시의 평면 `{"topN": [...]}` 는
> `{"SECTOR_MOMENTUM": {"topN": [...]}}` 로 고쳐야 한다(FR-027).

## 시나리오 3 — 상한 초과 거부 (FR-005b)

임시 상한(120)을 넘는 조합을 만든다. 축 값을 늘리거나 테스트 프로파일에서 상한을 낮춰 확인한다.

**기대**: `422 CANDIDATE_LIMIT_EXCEEDED`. `message`에 유효 후보 수·상한·좁히는 방법이 있고,
`param_search` 행이 `status=REJECTED`로 남는다. 후보 행은 생기지 않고, **표본을 뽑아 실행하지 않는다.**

> **2026-09-14 현재 이 시나리오는 돌릴 수 없다.** 상한을 넘길 방법이 없다.
>
> "축 값을 늘려" 넘기는 길이 막혀 있다 — `space` 는 **등록표에 등록된 후보값만** 받는다.
> `allowedRange` 안의 값이라도 등록된 후보값이 아니면 `400 UNREGISTERED_PARAMETER_VALUE` 다(FR-002).
> 범위를 넓히려면 등록표를 고쳐야 하고, 그것은 quickstart 로 할 일이 아니다.
>
> 그래서 만들 수 있는 최대 조합 수가 정해져 있는데, 실행 가능한 전략을 다 합쳐도
> **`INVERSE_VOLATILITY` 4 + `EQUAL_WEIGHT` 1 + `FIXED_WEIGHT` 1 = 6조합**이다. 로컬 상한 24, 운영
> 상한 120 어느 쪽도 넘지 못한다. (`FIXED_WEIGHT` 는 목표 비중을 사용자가 줘야 하는데 007 이 그 값을
> 넘길 방법이 없어 실제로는 후보가 되어도 004 가 거부한다 — 실질 조합은 5다.)
>
> PD-03·PD-04 가 확정돼 두 전략이 실행 가능해져도 **전체가 24조합**이라(9+9+4+1+1) 로컬 상한 24 와
> 같을 뿐 넘지 않는다. 이 시나리오는 어느 경우든 **상한을 일부러 낮춘 프로파일**이 필요하다.
>
> **거부 경로 자체는 구현돼 있고 검증됐다** — `ParamSearchRejectionIT` 가 `max-candidates=2` 로 낮춘
> 프로파일에서 `422 CANDIDATE_LIMIT_EXCEEDED`·`REJECTED` 저장·후보 행 0건·표본 미추출을 확인한다.
>
> **`application-local.yml` 의 `max-candidates: 24` 주석이 "상한 초과 거부를 로컬에서 재현하려고"
> 라고 적고 있으나 사실이 아니다.** 위 계산대로 24 로는 재현되지 않는다. 값을 6 미만으로 낮추거나
> 테스트처럼 별도 프로파일을 쓰는 수밖에 없다.

## 시나리오 4 — 취소와 부분 결과 (US3, SC-003)

후보가 여러 건인 묶음을 실행하고 진행 중에 취소한다.

```bash
curl -s -X POST -H 'X-User-Id: 1' localhost:8080/param-searches/{searchId}/cancel | jq
curl -s localhost:8080/param-searches/{searchId} | jq '{status, stopReason, counts, partialNote}'
```

**기대**: `skipped`·`inFlight`가 반환되고, 잠시 뒤 `status=PARTIAL`, `stopReason=CANCELLED`,
`counts.pending`이 `SKIPPED`로 옮겨져 있으며 `partialNote`에 완료·미실행 수가 문장으로 있다.
**취소 후 워커 로그에 새 후보 실행이 시작되지 않는다**(내부 계약 §4 불변식 2).

## 시나리오 5 — 선정과 미검증 표시 (US2, US4, SC-006)

완료된 묶음을 조회한다.

```bash
curl -s localhost:8080/param-searches/{searchId} | jq '.data.selection, .data.baseline'
curl -s "localhost:8080/param-searches/{searchId}/candidates?selected=true" | jq '.data.content'
```

**기대**

- `baseline.kind = "EQUAL_WEIGHT"`이고 `backtestId`가 있다 — 기준 1건이 같은 조건으로 함께 돌았다(FR-013d).
- 표시 후보는 최대 5개. `selectionReason.passedFilters`에 `DRAWDOWN`·`STABILITY`가 있다.
- 탈락 후보의 `selectionReason.outcome`이 어느 필터에서 탈락했는지 담는다.
- 모든 후보의 `validationLabel`이 `UNVALIDATED`다 — 평가 분할 미등록이므로 정상이다(FR-014).
- 응답 어디에도 순위 번호·추천·우승 표현이 없다(SC-006). 화면에서도 없어야 한다.
- 이웃이 없는 후보는 `stability.calculable=false`, `value=null`이고 안정성 필터를 통과했다(FR-013b).

## 시나리오 5.1 — 실패 후보 재시도 (FR-024, SC-009)

실패 후보가 있는 묶음에서 재시도한다.

```bash
curl -s -X POST localhost:8080/param-searches/{searchId}/retry-failed | jq
```

**기대**: `retried`·`remainingFailed`가 반환되고 `searchId`가 **그대로**다(새 묶음이 생기지 않는다).
재시도가 끝나면 선정이 다시 계산되고 `selection_rule` 스냅샷은 변하지 않는다. `SKIPPED` 후보는
건드리지 않는다. 실패 후보가 없으면 `409 NOTHING_TO_RETRY`.

```sql
SELECT candidate_no, status, retry_count FROM search_candidate
WHERE param_search_id = :id AND retry_count > 0;
```

## 시나리오 6 — 재현 (SC-004)

같은 등록표 버전·같은 선택 범위·같은 자료 버전으로 다시 실행한다.

```bash
# 두 묶음의 후보 파라미터 집합이 같은지
SELECT param_search_id, jsonb_agg(parameters ORDER BY candidate_no)
FROM search_candidate WHERE param_search_id IN (:a, :b) GROUP BY param_search_id;
```

**기대**: 두 묶음의 후보 집합과 그리드 좌표가 완전히 같다. **시드를 입력하지 않았는데 같다** —
전체 그리드는 결정적이라 시드가 필요 없다(FR-005). `random_seed` 컬럼은 두 행 모두 NULL이다.

`searchId`는 다르다. 같은 조건 재실행은 항상 새 묶음이다(FR-013c).

## 시나리오 7 — 전략 비의존성 (US5, SC-010·SC-011·SC-012)

**등록표만 바꿔서 새 전략이 탐색 대상이 되는지** 확인한다. 007 소스는 한 줄도 고치지 않는다.

```bash
# 1. 미리보기의 전략 목록·축·기본값이 모두 등록표에서 오는지 (FR-025, FR-027)
curl -s -X POST localhost:8080/param-searches/preview \
  -H 'Content-Type: application/json' \
  -d '{"universe":{"instrumentIds":["091160.XKRX"]},"strategy":{"strategyIds":["INVERSE_VOLATILITY"]}}' \
  | jq '{registryVersion, strategies, spaceSummary}'

# 2. 자원 파일에 여섯 번째 전략을 추가한다 (코드 수정 없음)
#    backend/src/main/resources/registry/parameter-registry-provisional.yml 에
#    축 개수와 후보 개수가 기존 전략과 다른 전략 하나를 넣고 백엔드를 재기동한다.

# 3. 그 전략으로 미리보기가 동작하고 조합 수가 맞는지 (SC-010, SC-011)
#    전체 조합 수 = 전략별 (축별 후보 개수의 곱)의 합
curl -s -X POST localhost:8080/param-searches/preview \
  -H 'Content-Type: application/json' \
  -d '{"universe":{"instrumentIds":["091160.XKRX"]},"strategy":{"strategyIds":["<새-전략-id>"]}}' \
  | jq '.data.workload'

# 4. 등록표에 없는 전략 → 422 STRATEGY_NOT_REGISTERED (SC-012)
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/param-searches \
  -H 'Content-Type: application/json' \
  -d '{"universe":{"instrumentIds":["091160.XKRX"]},"strategy":{"strategyIds":["no-such-strategy"]},"registryVersion":"PROVISIONAL-20260911"}'

# 5. 등록됐지만 004가 실행을 지원하지 않는 전략 → 422 STRATEGY_NOT_EXECUTABLE (SC-012)
#    위 2단계에서 추가한 전략이 워커에 없으므로 그대로 실행을 시도하면 이 코드가 나온다.

# 6. 미리보기와 다른 registryVersion으로 실행 → 409 REGISTRY_VERSION_CHANGED (FR-028)
curl -s -X POST localhost:8080/param-searches \
  -H 'Content-Type: application/json' \
  -d '{"universe":{"instrumentIds":["091160.XKRX"]},"strategy":{"strategyIds":["INVERSE_VOLATILITY"]},"registryVersion":"PROVISIONAL-1999-01-01"}' | jq '.error'
```

확인:

- 1단계 응답의 전략 이름·축 라벨·단위·기본값이 자원 파일 값과 일치한다. 007 소스에서
  `grep -rn "INVERSE_VOLATILITY" backend/src/main/java/com/ssafy/thorp/paramsearch` 결과가 **0건**이다(SC-010).
- 3단계에서 축 개수·후보 개수가 다른 전략의 조합 수가 기대치와 같다(SC-011).
- 4·5단계가 서로 다른 오류 코드로 거부되고, 묶음이 `REJECTED`로 저장되며 `stop_reason`이 구분된다.
- 6단계는 묶음을 만들지 않는다(`param_search` 행이 늘지 않는다).

## 자동 테스트

```bash
cd backend && ./gradlew spotlessApply && ./gradlew test    # 커밋 전 필수
cd frontend && npm run lint && npm test
cd worker && pytest                                        # 변경 없음 — 회귀 확인용
```

Backend 테스트가 덮어야 하는 것(구현 Task에서 작성):

| 대상 | 확인할 것 |
|---|---|
| `GridExpander` | 조합 수 = 축 값 개수의 곱, 그리드 좌표가 등록표 인덱스와 일치 |
| `CandidateValidator` | 알려진 두 위반이 100% 제외되고 사유 코드가 남는다 (SC-002) |
| `CandidateDispatcher` | `DISPATCHED` 수가 창 크기를 넘지 않는다. 취소 후 발행 0건 |
| `NeighborStabilityCalculator` | 이웃 판정이 1축 ±1만 포함, 이웃 0개면 `calculable=false` |
| `CandidateSelector` | 두 필터 순서, 통과 0개·5개 미만 처리, 동률 전부 표시, 기준 실패 시 필터 미적용 |
| `ParamSearchService` | 상한 초과 시 `REJECTED` 저장 + 후보·Job 미생성 |
| `ParamSearchService.retryFailed` | 새 묶음 미생성, 스냅샷 재사용, `SKIPPED` 제외, 선정 재계산 (SC-009) |
| 재현 | 같은 등록표·범위·자료 버전 재실행 시 후보 집합·좌표 동일, 시드 NULL (SC-004) |
| 기록 완전성 | 완료 묶음이 FR-020 항목 전체를 갖는지 (SC-007) |
| 통합(Testcontainers) | 접수 → 이벤트 수신 → 집계 → 선정까지 상태 전이, 동시 이벤트에서 `counts` 정합 |

## 아직 확인할 수 없는 것

- **배분 전략의 탐색** — 004 미구현이라 실행 지원 목록이 momentum뿐이다. 2종목 이상은 후보 Job이 `FAILED`가 된다.
- **파라미터 등록표의 실제 값** — PD-02 미확정. `registryVersion`이 `PROVISIONAL-*`인 동안의 결과는
  팀 확정 등록표의 결과가 아니다.
- **미사용 검증 결과 표시** — PD-12 미확정. 전 후보가 `UNVALIDATED`이므로 SC-005는 자동 만족이며
  이를 검증 통과로 보고하지 않는다(완료의 구분).
- **실행 한도의 적정성** — PD-06 실측 전. 이 가이드로 얻는 측정값이 PD-06의 입력이다.

---

## 수동 실행 기록 (T125)

**일시** 2026-09-14 | **대상** 묶음 M1~M9 구현 (dev 병합본) | **환경** 로컬 프로파일, 워커 없음

돌린 결과를 그대로 적는다. 통과하지 못한 것은 사유를 함께 남긴다 — "해봤는데 안 됐다"와 "할 수
없었다"는 다르고, 뒤엣것은 무엇이 해소돼야 돌릴 수 있는지가 기록의 몫이다.

| 시나리오 | 결과 | 확인한 것 |
|---|---|---|
| 1 확인 화면과 한 동작 실행 | **통과** | 미리보기가 네 덩어리를 채우고 `limits.provisional` 에 네 항목, `validationLabel=UNVALIDATED`. `space` 없이 접수해 `202`·후보 4건 생성, 발행 창만큼만 `DISPATCHED` (SC-001) |
| 2 유효성 위반 조합의 사전 제외 | **실행 불가** | 유효성 규칙을 가진 전략(`SECTOR_MOMENTUM`·`MINIMUM_VARIANCE`)이 둘 다 `PENDING_DECISION` 이라 `422 STRATEGY_NOT_EXECUTABLE` 로 먼저 끊긴다. 시나리오 2의 주석 참고 |
| 3 상한 초과 거부 | **실행 불가** | 실행 가능한 전략을 다 합쳐 6조합. `space` 가 등록된 후보값만 받아 넓힐 수 없다. 시나리오 3의 주석 참고 |
| 4 취소와 부분 결과 | **통과** | 후보 5건·창 4인 묶음에서 `202 {skipped:1, inFlight:4}`, 응답 시점 `status=RUNNING` 이고 `stopReason=CANCELLED` 가 먼저 기록됨. 발행된 4건이 끝나자 `PARTIAL`, 대기 후보는 `SKIPPED`+사유, 취소 뒤 추가 발행 0건 (SC-003, 불변식 2·4) |
| 5 선정과 미검증 표시 | **통과** | `selection.displayed` 2건, `shortOfDisplayCount=true`, `note="기준을 충족한 후보가 2개입니다"`. 후보마다 `validationLabel=UNVALIDATED`. 워커가 없어 기준 실행이 `QUEUED` 로 남아 `drawdownFilterApplied=false` 이고 `passedFilters` 에 `DRAWDOWN` 이 없다 — 걸지 않은 필터를 통과했다고 적지 않는다 (SC-006, FR-013d) |
| 5.1 실패 후보 재시도 | **통과** | `202 {retried:1, remainingFailed:0}`, 같은 묶음이 다시 열리고 `param_search` 행 수 불변, `retryCount=1`·이전 실패 사유 삭제, `selection` 이 비워졌다가 다시 끝날 때 재계산 (SC-009, FR-024) |
| 6 재현 | **통과** | 같은 등록표 버전·같은 범위의 두 묶음이 후보 집합과 그리드 좌표가 완전히 같고 `searchMethod=FULL_GRID`·`randomSeed=NULL`. 같은 조건 재접수 자체는 004 결함으로 막혀(아래) 두 묶음 대조로 대신했다 (SC-004) |
| 7 전략 비의존성 | **통과** | 007 운영 코드에서 다섯 전략 ID 리터럴이 **전부 0건**. 전략 이름·축 라벨·단위·기본값·후보값이 모두 등록표 조회 결과로만 응답에 실린다 (SC-010) |

### 실행 중 발견한 것

- **같은 조건으로 다시 접수하면 `500`이다.** 004 의 동일 버전 재사용·합류 경로에서 난다(다른 종목이면
  `202`). `BacktestService.joinExisting` 주석이 예고한 지점으로 보인다. 007 범위 밖이라 손대지 않았고
  시나리오 6 을 두 묶음 대조로 대신한 이유다.
- **계약을 어긴 실패 이벤트는 조용히 버려진다.** `FINISHED(FAILED)` 는 `engineOutput.run.error` 가
  필수인데, 성공 예시에서 `status` 만 바꾼 메시지는 검증기가 걸러 DLQ 로 보낸다. 그동안 007 후보는
  `DISPATCHED` 에 머물러 묶음이 닫히지 않는다. 워커가 계약을 지키면 생기지 않지만, 계약 위반이
  007 쪽에서는 "영원히 진행 중"으로 보인다는 점은 남는다.
- **낡은 빌드 산출물로 서버가 뜨면 `GET /param-searches/{id}` 가 `500` 이다**
  (`NoSuchMethodError: ParamSearchResult.validationLabel()`). 코드 결함이 아니라 부분 재컴파일
  상태이며 재시작으로 해소된다. 같은 증상을 다시 만나면 소스부터 의심하지 말고 재시작을 먼저 본다.

### 워커 없이 돌린 것의 뜻

이 기록은 **워커를 띄우지 않고** 만들었다. `daily_price` 가 비어 있어(spec 001 적재 대기) 실제 계산을
할 수 없으므로, 후보 완료는 계약 이벤트(`docs/specs/backtest-messaging/examples/`)를 `backtest.events`
에 직접 발행해 대신했다.

따라서 확인된 것은 **접수·발행·집계·선정·조회의 흐름**이고, 확인되지 않은 것은 **엔진이 낸 숫자**다.
성과 값은 모두 테스트가 넣은 값이므로 이 기록의 수익률·하락 폭을 성능 근거로 인용하면 안 된다.

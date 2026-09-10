# Quickstart: 설정 조합 자동 비교 검증 가이드

**Date**: 2026-09-10 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

007이 동작하는지 종단으로 확인하는 절차다. 필드 의미와 응답 모양은
[contracts/param-search-api.md](contracts/param-search-api.md), 저장 구조는
[data-model.md](data-model.md)를 본다 — 여기서 다시 적지 않는다.

## 사전 조건

**004가 아직 없다는 것을 전제로 한 절차다.** 현재 워커는 단일 종목 momentum만 계산하므로 이 가이드는
섹터 1종목으로 확인한다. 다섯 배분 전략이 붙으면 `instrumentIds`를 늘려 같은 절차를 반복한다.

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
  -d '{"universe":{"instrumentIds":["069500.XKRX"]},"strategy":{"strategyIds":["momentum"]}}' | jq
```

**기대**: `data.strategies`·`spaceSummary`·`workload`·`limits` 네 덩어리가 채워진다.
`limits.provisional`에 `maxCandidates`가 들어 있고, `validationLabel`은 `UNVALIDATED`다.

```bash
# 같은 본문으로 실행
curl -s -X POST localhost:8080/param-searches \
  -H 'Content-Type: application/json' \
  -d '{"universe":{"instrumentIds":["069500.XKRX"]},"strategy":{"strategyIds":["momentum"]}}' | jq
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
  -d '{"universe":{"instrumentIds":["069500.XKRX","091170.XKRX"]},
       "strategy":{"strategyIds":["sector-momentum"]},
       "space":{"topN":[2,3,4]}}' | jq
```

**기대**: 접수는 성공하고, `topN=3`·`topN=4` 후보가 `EXCLUDED` + `reason_code=TOP_N_EXCEEDS_UNIVERSE`로
남는다. `backtest_job` 행이 만들어지지 **않는다** — 계산 전 제외다.

```bash
curl -s "localhost:8080/param-searches/{searchId}/candidates?status=EXCLUDED" | jq '.data.content'
```

**기대**: 각 항목의 `netReturn`·`maxDrawdown`·`tradingCost`가 모두 `null`이다. **0이 아니다** —
0이면 SC-002·FR-011 위반이다.

## 시나리오 3 — 상한 초과 거부 (FR-005b)

임시 상한(1000)을 넘는 조합을 만든다. 축 값을 늘리거나 테스트 프로파일에서 상한을 낮춰 확인한다.

**기대**: `422 CANDIDATE_LIMIT_EXCEEDED`. `message`에 유효 후보 수·상한·좁히는 방법이 있고,
`param_search` 행이 `status=REJECTED`로 남는다. 후보 행은 생기지 않고, **표본을 뽑아 실행하지 않는다.**

## 시나리오 4 — 취소와 부분 결과 (US3, SC-003)

후보가 여러 건인 묶음을 실행하고 진행 중에 취소한다.

```bash
curl -s -X POST localhost:8080/param-searches/{searchId}/cancel | jq
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

- **다섯 배분 전략의 탐색** — 004 미구현. 2종목 이상은 후보 Job이 `FAILED`가 된다.
- **파라미터 등록표의 실제 값** — PD-02 미확정. `registryVersion`이 `PROVISIONAL-*`인 동안의 결과는
  팀 확정 등록표의 결과가 아니다.
- **미사용 검증 결과 표시** — PD-12 미확정. 전 후보가 `UNVALIDATED`이므로 SC-005는 자동 만족이며
  이를 검증 통과로 보고하지 않는다(완료의 구분).
- **실행 한도의 적정성** — PD-06 실측 전. 이 가이드로 얻는 측정값이 PD-06의 입력이다.

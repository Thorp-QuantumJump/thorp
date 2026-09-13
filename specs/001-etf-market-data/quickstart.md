# Quickstart: 대표 ETF 자료 확보 검증

**Plan**: [plan.md](./plan.md) | **Contracts**: [contracts/](./contracts/README.md)

이 기능이 실제로 동작하는지 확인하는 절차다. 구현 코드는 담지 않는다. 각 시나리오는 spec의 Success Criteria 하나 이상을 검증한다.

## 사전 준비

```bash
# 개발 환경 기동 (postgres, rabbitmq, backend, worker)
docker compose -f docker-compose.dev.yml up -d postgres rabbitmq

# 원천 인증 정보. 코드에 넣지 않는다 (data/CLAUDE.md)
cp data/.env.example data/.env   # KRX_API_KEY 를 채운다
```

인증 정보가 없으면 예시 자료 경로로 진행할 수 있다. 시나리오 1이 그 경로를 검증한다.

## 시나리오 1 — 예시 자료로 섹터 목록이 나오고 예시임이 표시된다

**검증 대상**: SC-001, SC-006, FR-002

```bash
cd data
uv run scripts/ingest_krx.py --sample --map-version SEM-SAMPLE-001
```

```bash
curl -s localhost:8080/sectors | jq '{mapVersion, isSample, count: (.sectors | length)}'
curl -s localhost:8080/sectors | jq '[.sectors[] | select(.representativeEtf == null)] | length'
```

**기대 결과**

- `isSample`이 `true`다
- `representativeEtf`가 없는 섹터가 **0개**다 (SC-001)
- 모든 대표 ETF의 `listedOn`과 `benchmarkIndex`가 채워져 있다

`isSample`이 true인데 화면에 예시 표시가 없으면 SC-006 위반이다. 화면 확인은 002 구현 후에 가능하며, 이 단계에서는 응답 필드까지 검증한다.

## 시나리오 2 — 실제 자료를 수집하고 상장 전 값이 없음을 확인한다

**검증 대상**: SC-003, FR-005

```bash
cd data
uv run scripts/ingest_krx.py --map-version SEM-2026-09-10-001 --until 2026-09-07
uv run scripts/verify_listing_boundary.py     # 상장 전 행을 세는 검증 스크립트
```

**기대 결과**

- `상장 전 가격 행: 0건` (SC-003)
- 각 ETF의 첫 행 날짜가 `representative_etf.listed_on`과 같다
- 상장폐지된 ETF가 있으면 마지막 행이 `delisted_on` 이전이다

## 시나리오 3 — 같은 자료 버전을 두 번 읽으면 값이 같다

**검증 대상**: SC-004, FR-013, FR-017

```bash
cd worker
uv run pytest tests/test_repository_provider.py -k reproducible
```

**기대 결과**: 같은 `datasetId`로 두 번 `load`한 결과 프레임이 완전히 같다. 부동소수 비교가 아니라 값 동치로 단언한다. `daily_price`의 PK가 `(dataset_id, instrument_id, date)`이므로 새 버전을 발행해도 옛 버전 행이 남는다.

이어서 자료 버전 불변성을 확인한다.

```bash
psql -h localhost -U thorp -d thorp -c \
  "UPDATE dataset_version SET data_deadline = now() WHERE dataset_id = 'KRX_DAILY_20260907';"
```

**기대 결과**: 갱신이 거부된다. 발행된 버전을 고치는 경로가 없어야 한다(FR-017).

## 시나리오 4 — 공통 평가 기간이 픽스처의 수기 계산과 일치한다

**검증 대상**: SC-007, FR-007, FR-008

산식과 경계 동작은 [contracts/evaluation-window.md](./contracts/evaluation-window.md)가 기준이다.

```bash
cd worker && uv run pytest tests/test_evaluation_window.py
cd ../backend && ./gradlew test --tests '*EvaluationWindowTest'
```

**기대 결과**: 양쪽이 같은 픽스처(`contracts/fixtures/evaluation-window-cases.json`)를 읽어 같은 값을 낸다. 한쪽만 고치면 실패한다.

이어서 HTTP 표면을 확인한다.

```bash
curl -s 'localhost:8080/sectors/evaluation-window?sectors=SEMICONDUCTOR,BATTERY&strategy=SECTOR_MOMENTUM' | jq
```

**기대 결과**

- 상장일이 늦은 쪽이 `bindingCandidate`로 나온다
- `commonStart`가 그 후보의 상장일에서 워밍업 거래일만큼 뒤다
- **잠금 구간이 빠지지 않은 전체 범위**가 나온다 (FR-008)

## 시나리오 5 — 기업행위가 반영되고 중복 차감되지 않는다

**검증 대상**: FR-004, SC-002

```bash
cd data
uv run scripts/reconcile.py --dataset KRX_DAILY_20260907
```

**기대 결과**

- 대조 표본이 상장 첫날·모든 분배금 지급일·모든 분할일로 구성된다. 평범한 거래일은 표본에 없다 (FR-016)
- 표본의 조회값이 공시와 일치하거나, 불일치마다 `cause`와 `resolution`이 기록된다 (SC-002)
- 분배금 지급일 전후의 `adjusted_close` 수익률이 원시 종가 수익률과 분배금만큼 차이 난다

수기 대조도 한 건 해 둔다. 분배금 지급일 하나를 골라 운용사 공시의 주당 분배금과 `corporate_actions`의 `amount`가 같은지 눈으로 확인한다. constitution 「기능·계산 확인」이 요구하는 절차다.

## 시나리오 6 — 거래정지와 자료 부족이 정상 흐름으로 처리된다

**검증 대상**: FR-006, 원칙 VI

```bash
cd worker && uv run pytest tests/test_repository_provider.py -k "suspended or gap"
```

**기대 결과**

- 직전 종가를 쓴 날이 `metadata.carriedForwardDates`에 있다
- 그날 매매 주문이 만들어지지 않는다
- 정지 구간이 `suspension_period`에 시작일·종료일·거래일 수로 기록된다. 확정 전에는 `data_gaps`에 `SUSPENDED_TOO_LONG`을 쓰지 않는다
- 자료 부족 구간이 있어도 예외로 죽지 않고 구간 정보를 담은 정상 결과가 나온다

## 시나리오 7 — 이용 한도에 걸린 부분 갱신이 최신으로 표시되지 않는다

**검증 대상**: FR-014, Edge Cases

```bash
cd data
uv run scripts/ingest_krx.py --map-version SEM-2026-09-10-001 --simulate-quota-exhausted
```

**기대 결과**

- 자료 버전 행의 `complete`가 `false`다
- `failure_reason`과 `last_success_at`이 채워져 있다
- 조회 응답의 `dataDeadline`이 실제로 수집한 마지막 날짜다. 요청한 날짜가 아니다

## 시나리오 8 — 대표 상품 정보에서 근거와 한계를 확인한다

**검증 대상**: FR-011, FR-015, SC-005, User Story 3

지수 방법론 요약과 구성종목은 원천 API가 주지 않아(2026-09-13 확인) 상장일·기업행위와 같이 파일로 받는다. 일평균 거래대금만 방금 적재한 `daily_price`의 거래대금에서 계산한다.

```bash
cd data
uv run scripts/ingest_krx.py --map-version SEM-2026-09-10-001 --until 2026-09-07 \
    --instrument-profiles raw/instrument-profiles.json
```

```bash
curl -s localhost:8080/sectors/SEMICONDUCTOR/instrument-profile | jq
```

**기대 결과**

- 필수 항목이 모두 있다: `benchmarkIndex`·`indexMethodologySummary`·`listedOn`·`holdingsOverlap`·`avgDailyValue3m`·`sourceAttribution`·`survivorshipNote` (FR-015)
- `sourceAttribution`이 `한국거래소 통계정보`다 (FR-011, SC-005)
- 과거 구성 이력이 없으므로 `holdingsOverlap.asOfCurrentComposition`이 `true`다. 화면은 당시 구성을 재현했다고 표현하지 않는다
- `survivorshipNote`에 현재 생존 상품만 고른 한계가 한 문장으로 있다
- **추적 차이 필드가 없다.** 계산 정의가 PD-21로 남아 이번 범위에서 뺐다
- `avgDailyValue3m`이 지수 표기 없는 문자열이다. 거래량과 종가를 곱한 추정이 아니라 `daily_price.value` 기준이다
- `datasetId`·`dataDeadline`이 어느 자료 버전 기준인지 알려 준다. `isSample`은 **그 자료 버전**의 값이며 `GET /sectors`의 `isSample`(대응표 기준)과 근거가 다르다

이어서 없는 상태와 빈 값을 구분하는지 확인한다.

```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/sectors/NO_SUCH_SECTOR/instrument-profile
```

**기대 결과**: 없는 섹터 코드는 `404 SECTOR_NOT_FOUND`, 대표 상품 정보가 아직 적재되지 않았으면 빈 값이 아니라 `409 INSTRUMENT_PROFILE_NOT_READY`다. 원칙 VI가 "없는 결과를 만들어내지 않는다"를 요구하므로 준비 안 됨과 없음을 구분한다.

## 전체 검증 명령

파트별 검증 명령은 각 CLAUDE.md가 정한 것을 그대로 쓴다.

```bash
cd data     && ruff check src scripts && pytest
cd ../worker && ruff check src tests && pytest
cd ../backend && ./gradlew spotlessApply build
```

`backend`의 DB 테스트는 `support/PostgresTestSupport`를 상속하며 Docker가 꺼져 있으면 조용히 skip된다. 결과의 `skipped` 수를 확인한다.

## 확인할 수 없는 항목

| 항목 | 왜 지금 확인할 수 없는가 |
|---|---|
| SC-005 한국거래소 출처 표시 | 화면이 있어야 확인된다. 001은 `sourceAttribution` 필드까지 책임진다 |
| PD-01 확정 후의 실제 공통 기간 | 자료 이용 승인 후에 알 수 있다 |
| PD-19·PD-20 거래정지 처리 방식과 기준 일수 | 실제 정지 사례의 길이 분포를 봐야 한다. 본선이 `suspension_period`에 기록을 쌓아 둔다 |

constitution 「미확인 항목 처리」에 따라 이 항목들은 개발 진행을 막지 않는다. 확인 전에는 예시 자료와 보수적 동작으로 진행하고, 확인되면 판정과 근거를 기록한다.

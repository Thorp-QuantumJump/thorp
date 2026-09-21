# 검증 결과: Spec 008 (2026-09-22)

[quickstart.md](quickstart.md)의 검증 시나리오를 수행한 결과다 (T072).

**이 문서는 통과 주장이 아니라 무엇이 무엇으로 확인됐는지의 기록이다.** 확인하지 못한 항목은
확인하지 못했다고 적는다 — 빈칸을 남기면 다음 사람이 통과한 것으로 읽는다.

## 요약

| | 수 |
|---|---|
| 자동 검증으로 확인 | 19 |
| 운영 실행으로 확인 | 3 |
| **확인하지 못함** | **2** |

## 1. 원값 대조 (SC-007)

지표마다 세 달을 골라 **원천 응답에서 다시 계산한 값**과 파이프라인 값을 견줬다. 허용 오차는
0이다(PD-12 확정 전까지 정확 일치).

- 검증: `data/tests/test_macro_value_crosscheck.py`
- 표본: 2026-09-17 실조회 응답 (`data/tests/fixtures/macro/`)
- 대조 방식: 변환 모듈을 쓰지 않고 시험 파일 안에서 다시 계산한다. 같은 모듈로 다시 계산하면
  그 모듈이 틀렸을 때 둘이 똑같이 틀린다

| 항목 | 결과 |
|---|---|
| 여섯 변환 유형이 모두 표본에 든다 | 확인 |
| 주택가격: 이어 붙인 이음매를 넘는 전월 대비 | 확인 (표 셋을 `housing.link()`로 이은 계열에서 대조) |
| 실업률: 원천 계절조정값을 그대로 둔다 | 확인 |
| 결측 직후 달이 가까운 달로 물러나지 않는다 | 확인 |
| **수출·수입** | **대조 못 함** — 관세청 표본이 2026년 9개월뿐이라 전년동월(t-12)이 없다. 2년치 응답을 받아 표본에 넣으면 대조된다. 무역수지는 변환이 공식 금액이라 같은 표본으로 대조됐다 |

건너뛴 지표는 `SAMPLE_TOO_SHORT`에 이유와 함께 적혀 있고, 그 목록이 늘어나면 시험이 걸린다 —
건너뛰기가 조용히 늘면 대조 범위가 줄어드는데 스위트는 초록으로 남는다.

## 2. 계약·자료 시나리오

| 시나리오 | 확인 방법 |
|---|---|
| 첫 완결 전 (등록표만) | `MacroSnapshotContractTest` — 자료 버전 없이 17개 등록 정보가 나간다 |
| 정상 발행 | `test_macro_ingest_postgres.py` 발행 경로 + 2026-09-21 운영 적재 |
| raw만 존재 / 발행 보류 | `test_macro_publish.py`, `test_macro_ingest_postgres.py` |
| 부분 수집 실패 | `test_macro_ingest_postgres.py` — 기록은 남고 새 버전은 없다 |
| 증가율 분모 경계 (결측·0·계열 시작) | `test_macro_transform.py` |
| 월 기말 휴장 | `test_macro_transform.py` — 실제 국고채 표본 포함 |
| 월 평균 부분 표본 | `test_macro_transform.py` |
| 관세청 연 단위 분할·합계 행 제외 | `test_macro_sources_customs.py`, `test_macro_ingest.py` |
| 주택가격 개편 연결 | `test_macro_housing_link.py` |
| 동시 조회/발행 | `test_macro_publish_atomic.py` |
| 파일/DB 발행 경계 | `test_macro_publish_atomic.py` |
| 매핑/가중·표시·값 규칙 변경 | `test_macro_versioning.py` |
| 권리 확인 대기 / 권리 미확인 | `test_macro_compliance.py`, `MacroSnapshotContractTest` |
| 공표 정밀도 | `test_macro_published_precision.py` — 원천 응답에서 자릿수를 다시 잰다 |
| 전 구간 적재 완료 판정 | `test_macro_ingest.py`, `test_macro_ingest_postgres.py` |
| 009 연동 (요청당 스냅샷 한 번) | `MarketFlowControllerIT` — 008 표에서 읽어 HTTP까지 간다 |
| 010 연동 (같은 버전 고정) | `test_macro_source.py` 계약 시험이 008 구현체에도 돈다 |

## 3. 운영 실행으로 확인한 것 (2026-09-21)

운영 DB에 17개 지표 전 구간을 적재하고 SQL로 확인했다.

| 항목 | 결과 |
|---|---|
| 발행 | `PUBLISHED` / 완성 지표 17 / 표본 아님 / 원본·정제 매니페스트 해시 있음 |
| 관측 | 14,050건, 지표 17개, 구간이 등록표의 계열 시작월과 일치 |
| 소수 저장 | 7,348건 전부 JSONB **문자열** — `number` 0건 |
| 기준월 | 분야 7 + 전체 1 = 8행 |
| 등록표 빈 칸 | 공표 지연·이용 조건 문구 모두 0 |

## 4. 확인하지 못한 것

### 4.1 수출·수입의 원값 대조
표본이 1년치뿐이라 전년동월이 없다. **필요한 것**: 관세청 2년치 응답을 받아
`data/tests/fixtures/macro/customs/`에 넣는다. 그러면 `SAMPLE_TOO_SHORT`에서 두 줄을 지우고
시험이 그대로 대조한다.

### 4.2 증분 수집 보존과 `N` 밖 개정
`증분 수집 보존`·`N 밖 개정`·`과거 개정` 시나리오는 **정기 수집을 여러 달 돌린 뒤에야** 확인할 수
있다. 지금은 전 구간 적재 한 번뿐이라 개정을 한 건도 관측하지 못했다. 이것은 재조회 구간 `N`을
미확인으로 닫아 둔 것과 같은 이유다([registry.REFRESH_MONTHS_BASIS](../../data/src/data_pipeline/macro/registry.py)).

**필요한 것**: 정기 수집을 최소 `N+3`개월 돌린 뒤 `macro_raw_revision`에서 지표별로 `N` 안에서
잡힌 개정과 `N` 밖에서 뒤늦게 발견된 개정을 센다.

## 5. 검증 명령

```bash
cd data && ruff check src scripts tests && THORP_REQUIRE_DOCKER=true pytest
cd backend && THORP_REQUIRE_DOCKER=true ./gradlew spotlessApply build
```

Docker가 꺼져 있으면 DB 시험이 건너뛰어지므로 `THORP_REQUIRE_DOCKER=true`로 돌리고 결과의
`skipped` 수를 확인한다. 빌드가 초록이라는 이유로 DB 검증을 통과했다고 세지 않는다.

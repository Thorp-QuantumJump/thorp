# 대표 ETF 자료 확보 검증 기록 (spec 001, 2026-09-17)

**대상**: [`specs/001-etf-market-data`](../../specs/001-etf-market-data/spec.md) · 티켓 `S15P21E205-188` (T016)

**이 기록이 무엇인가**: spec 001이 확보한 자료가 어디까지 실제로 확인됐는지를 남긴다. constitution 「완료의 정의」가 가르는 세 층(구현 완료 · 사용성 확인 · 효과 입증) 가운데 **첫 층에 대한 기록**이며, 나머지 두 층의 근거로 쓰지 않는다.

**먼저 읽을 것**: 이 기록은 **quickstart 시나리오를 실행하지 않았다.** 무엇을 왜 돌리지 않았는지는 아래 「이 기록이 보증하지 않는 것」에 있다. 실행 절차 자체는 [`quickstart.md`](../../specs/001-etf-market-data/quickstart.md)에 있다.

## 환경

| 항목 | 값 |
|---|---|
| 확인 시각 | 2026-09-17 |
| 저장소 기준 | `dev` `12fc23c` |
| DB | 로컬 `docker compose -f docker-compose.dev.yml` PostgreSQL 16 |
| 대응표 버전 | `SEM-2026-09-16-001` (`is_sample = false`) |
| 자료 버전 | `KRX_DAILY_20260915` (`complete = true`) |

## 적재된 자료

DB를 직접 조회해 센 값이다.

| 표 | 건수 | 비고 |
|---|---|---|
| `daily_price` | 3,872행 | 2024-09-19 ~ 2026-09-15, 484거래일 × 여덟 종목 |
| `daily_index` (`KOSPI`) | 484일 | 거래일 달력의 출처다 |
| `data_gap` | 16건 | |
| `corporate_action` | **0건** | 분배금·분할이 아직 들어가지 않았다 |
| `reconciliation_record` | **0건** | 공시 대조 기록이 없다 |
| `suspension_period` | 0건 | 이 구간에 정지 사례가 없었다 |

**적재 구간이 확보 가능 구간보다 짧다.** PD-01은 여덟 종목이 함께 상장해 있는 날을 2018-09-12부터로 판정했는데([`specs/README.md`](../../specs/README.md) PD-01), 실제 적재는 2024-09-19부터인 2년치다. 공통 평가 기간 조회가 돌려주는 값은 판정된 구간이 아니라 **적재된 구간**을 따른다.

## 검증 명령

세 파트 모두 각 `CLAUDE.md`가 정한 명령을 그대로 실행했다.

- [x] `data`: `ruff check src scripts tests && pytest` — **791건 통과, 실패 0 · 건너뜀 0**
- [x] `worker`: `ruff check src tests scripts && ruff format --check src tests scripts && pytest` — **362건 통과, 실패 0 · 건너뜀 0**
- [x] `backend`: `./gradlew spotlessApply build` — **1,586건 실행, 실패 12 · 건너뜀 3**. 실패 12건은 001 밖의 기존 실패다(아래)

`backend`의 DB 테스트는 Docker가 꺼져 있으면 조용히 건너뛴다. 이번 실행은 `THORP_REQUIRE_DOCKER=true`로 돌려 건너뜀이 실패가 되게 했다. **검증 결과를 읽을 때는 통과 수보다 건너뜀 수를 먼저 본다.**

## 백엔드 검증

**건너뛰어진 3건은 이 spec과 상관없다.** 외부 API를 실제로 호출하는 검사 하나와 뉴스 성능 검사 둘이며, 둘 다 명시적으로 켜야 돌아간다. **001이 쓰는 DB 검사는 모두 실행됐다** — `THORP_REQUIRE_DOCKER=true`로 돌려 Docker 때문의 건너뜀을 실패로 바꿔 두었고 그런 건너뜀은 없었다.

**실패 12건은 001이 만든 것이 아니고 001이 닫을 수도 없다.** `dev`에 이미 있던 실패이며 모두 다른 spec의 검사다.

| 검사 | 건수 | 소속 |
|---|---|---|
| `LearningPublicApiIT` | 9 | spec 013 배우기 |
| `ResultApiIT` | 3 | spec 005 결과 이해·비교 |

**같은 실패가 001이 들어오기 전에도 있었다.** 2026-09-17에 `dev` `33fb854`(001의 평가 기간 조회가 병합되기 전)에서 같은 명령을 돌려 1,566건 중 **같은 이름의 12건이 실패하고 3건이 건너뛰어졌다.** 두 실행의 차이는 001이 더한 검사 20건이고 그 20건은 모두 통과한다.

**이 사실 자체를 그대로 남긴다.** 검증 기록은 "통과했다"만 적는 문서가 아니고, 지금 `dev`의 빌드가 빨간 상태라는 것은 다음 사람이 알아야 할 사실이다. **어느 spec이 닫을지는 사람이 정한다** — 곁가지를 발견할 때마다 티켓을 세우면 스프린트 범위만 넓어진다(`AGENTS.md`).

## 문서 대조

T016이 대조하라고 지목한 두 문서를 001의 결정과 맞춰 읽었다. **001 범위에서 직접 고치지 않고 갱신 대상만 남긴다**(`specs/README.md`가 이미 갱신 대상으로 기록한 문서들이다).

| 문서 | 판정 | 내용 |
|---|---|---|
| [`docs/specs/market-api.md`](../specs/market-api.md) | 어긋나지 않는다 | 주제가 다르다. 그 문서는 거시·시장 지표를 지표당 하나씩 조회하는 API이고, 001은 ETF 일봉·기업행위·섹터 대응표를 파이프라인으로 적재한다. 같은 KRX 원천을 두 경로가 읽지만 읽는 대상과 저장 위치가 달라 충돌하지 않는다 |
| [`docs/specs/market-api.md`](../specs/market-api.md) 「응답」 | **갱신 대상** | 시장 API는 `HttpApiResponse`(`{httpStatus, isSuccess, message, code, result}`)를 쓰고 001의 섹터 조회는 `ApiResponse`(`{success, data, error}`)를 쓴다. 그 문서가 "전체 응답 통일은 별도 마이그레이션"이라고 적어 둔 그 간극이며 001이 정할 것이 아니다 |
| [`docs/adr/0004`](../adr/0004-backtest-commission-model.md) | 어긋나지 않는다 | 거래비용 모델을 다루며 001의 자료 확보와 접점이 없다. 001이 내보내는 값 가운데 비용 계산에 들어가는 것은 없다 |

## 이 기록이 보증하지 않는 것

**quickstart 시나리오 여덟 개를 실행하지 않았다.** 실행하면 로컬 DB에 되돌릴 수 없는 행이 남기 때문이며, 2026-09-17에 그렇게 정했다. 구체적으로 이렇다.

- 시나리오 1은 예시 대응표를 새로 연다. 지금 열려 있는 확정 대응표가 닫히고, 열린 대응표는 최대 하나라 되돌리려면 손으로 다시 열어야 한다
- 시나리오 2·7·8은 새 자료 버전을 발행한다. `dataset_version`과 시계열에는 UPDATE·DELETE를 막는 불변 트리거가 걸려 있어(FR-017) 그 행을 지울 수 없다
- 시나리오 5는 `corporate_action`이 비어 있어 대조할 대상 자체가 없다

따라서 **아래 항목은 이 기록으로 확인되지 않았다.** 자료가 갖춰지고 검증 전용 DB를 쓸 수 있을 때 다시 해야 한다.

| 항목 | 왜 확인되지 않았나 |
|---|---|
| SC-002 공시 대조 | `corporate_action`·`reconciliation_record`가 0건이다. **constitution 「기능·계산 확인」이 요구하는 수기 대조 한 건도 하지 못했다** — 분배금 지급일을 골라 운용사 공시와 맞춰 보려 해도 DB에 비교할 값이 없다. 이 항목은 사람이 공시를 눈으로 확인해야 한다 |
| SC-003 상장 전 자료 없음 | 검증 스크립트를 돌리지 않았다 |
| SC-004 재현성 | 워커의 재현 테스트는 통과했으나(위 `worker` 362건에 포함) 적재된 실자료로 두 번 읽어 비교하지는 않았다 |
| SC-007 공통 평가 기간 | 산식은 공유 픽스처로 양쪽 구현이 검증된다. **실자료 위에서 HTTP 조회를 호출해 보지는 않았다** — 로컬 백엔드 컨테이너가 머지 전 이미지라 그 엔드포인트가 없다 |
| SC-005 출처 표시 | 화면이 있어야 확인된다. 001은 값을 내보내는 데까지다 |
| PD-19·PD-20 거래정지 규칙 | **확정하지 않기로 정했다**(2026-09-17). 이 구간에 정지 사례가 0건이라 길이 분포를 집계할 입력이 없다. `gaps.py`의 `SUSPENDED_TOO_LONG` 기록은 꺼진 채로 두고 `provisionalSuspensionHandling` 표시도 남는다 |
| PD-21 추적 차이 | 계산 정의가 확정되지 않아 범위에서 뺐다 |

**구현 완료를 사용성 확인이나 효과 입증의 근거로 쓰지 않는다.** 001에는 화면이 없어 사용성 확인을 할 대상이 없고, 효과 입증은 전략 선정과 경제환경 검색에 적용되는 절차라 자료를 확보하는 이 spec에는 해당하지 않는다.

## 재현 명령

```bash
# 적재 현황
docker exec thorp-postgres psql -U thorp -d thorp -c "
  SELECT (SELECT count(*) FROM daily_price) AS price_rows,
         (SELECT count(DISTINCT date) FROM daily_index WHERE index_code='KOSPI') AS calendar_days,
         (SELECT count(*) FROM corporate_action) AS corporate_actions,
         (SELECT count(*) FROM reconciliation_record) AS reconciliations;"

# 세 파트 검증
cd data     && ruff check src scripts tests && pytest
cd ../worker && ruff check src tests scripts && ruff format --check src tests scripts && pytest
cd ../backend && THORP_REQUIRE_DOCKER=true ./gradlew spotlessApply build
```

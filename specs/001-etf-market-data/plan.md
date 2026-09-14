# Implementation Plan: 대표 ETF 자료 확보

**Branch**: `docs(be)/120-etf-market-data-spec-clarify` (spec 디렉터리 `001-etf-market-data`) | **Date**: 2026-09-10 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-etf-market-data/spec.md`

## Summary

섹터마다 대표 ETF 하나를 연결한 버전 있는 대응표를 관리하고, 그 ETF의 일봉 원시 가격·기업행위 반영 조정 가격·분배금/분할 이벤트·상장일과 광범위 지수(KOSPI) 일별 자료를 이용 조건 안에서 확보한다. 확보한 자료에는 데이터 마감 시각과 자료 버전을 붙여, 백테스트(004)와 화면(002·003·005)이 "어느 버전으로 계산했는지" 참조할 수 있게 한다.

기술 접근은 세 갈래다. 첫째, 자료 수집·정제는 `data/` 파이프라인이 맡는다. 원천 응답을 `raw/`에 보관하고 정제 결과를 `processed/`의 Parquet 사본과 서비스 저장소(PostgreSQL)에 함께 적재한다. 둘째, Worker는 기존 `DataProvider` Protocol에 서비스 저장소를 읽는 구현을 추가한다. Worker 컨테이너에 공유 볼륨이 없어 Parquet 파일을 읽을 수 없기 때문이다. 셋째, 섹터-ETF 대응표와 대표 상품 정보는 화면이 조회하므로 Backend가 조회 API를 제공한다. Backend는 계산하지 않는다는 루트 원칙을 지켜 가격 시계열은 Backend를 거치지 않고 Worker가 직접 읽는다.

## Technical Context

**Language/Version**: Python 3.11 (`data/`, `worker/`), Java 21 (`backend/`)

**Primary Dependencies**: KRX OPEN API HTTP 클라이언트·pandas·pyarrow·PostgreSQL 드라이버 (`data/`), pandas·pydantic·PostgreSQL 드라이버 (`worker/`), Spring Boot 3.5·Spring Data JPA·Flyway (`backend/`). pykrx는 쓰지 않는다 (research.md 결정 1)

**Storage**: 서비스 읽기 경로는 PostgreSQL 하나다(Flyway `V20260911114500__etf_market_data.sql`와 `V20260913103000__instrument_profile.sql` 이후). 가격·기업행위·지수·자료 부족·정지 구간과 대응표·자료 버전·대조 기록·대표 상품 정보가 모두 여기 있다. `data/raw/`는 원천 응답 원본, `data/processed/`는 Parquet 정제 사본이며 서비스가 읽지 않는다 (research.md 결정 8)

**Testing**: pytest + ruff (`data/`, `worker/`), JUnit 5 + ArchUnit + `PostgresTestSupport` (`backend/`)

**Target Platform**: Linux 컨테이너 (docker-compose.dev.yml의 postgres·rabbitmq·backend·worker 구성)

**Project Type**: 모노레포 웹 서비스. 이 기능은 데이터 파이프라인 + 조회 API 조합이며 화면 요소는 002·003·005가 소비한다

**Performance Goals**: 사용자 요청 경로에 자료 수집을 두지 않으므로 지연 목표는 조회에만 적용된다. 섹터 목록·대표 상품 정보 조회는 체감 지연 없이 반환한다(구체 수치는 원칙 IX에 따라 실측 후 고정하며 이 spec에서 정하지 않는다). 공통 평가 기간 산출은 후보 집합 크기에 선형이어야 한다

**Constraints**: 개발·검증 단계 시세 자료는 비상업 이용·재배포 금지 조건이므로 서비스 제공용으로 쓰지 않고 내보내기도 이용 범위 안으로 제한한다(FR-010). 이미 발행한 자료 버전의 내용은 변경 불가(FR-017). 원천 이용 한도 안에서 수집하며 한도 도달 시 부분 갱신을 최신으로 표시하지 않는다

**Scale/Scope**: 섹터 수는 PD-01 확정 전이므로 미정이나 기획서 §3.3 기준 10~20개 규모로 본다. 대표 ETF 하나당 일봉 약 2,500~4,000행(2010년 이후), 기업행위 이벤트는 연 1~4건. KOSPI 시리즈 일별 자료 1종 이상

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 이 기능에 적용되는 게이트 | 판정 |
|---|---|---|
| I. 중심 약속은 전략 진단 | 001은 전략 진단 흐름(001~005, P1)의 첫 항목이며 우선순위 1이다 | 통과 |
| II. 첫 화면은 단순하게 | 사용자는 섹터 이름만 고르고 자료를 입력하지 않는다(FR-012). 대표 상품 정보는 펼쳐 보는 층에 둔다 | 통과 |
| III. 진단이지 추천이 아니다 | 001은 값을 제공만 하고 유동성·추적 차이를 등급으로 판정하지 않는다. clarify에서 등급 표시안을 기각했다 | 통과 |
| IV. 비교는 같은 조건에서만 | 공통 평가 가능 기간(FR-007)이 후보 집합 전체에 하나의 구간을 산출한다. 후보마다 유리한 기간을 달리 쓰지 않는다 | 통과 |
| V. 당시에 알 수 있던 정보만 | 상장 전 합성 이력·미래 자료 금지(FR-005), 체결은 원시 가격·신호와 성과는 조정 자료(FR-004) | 통과 |
| VI. 없는 결과를 만들지 않는다 | 자료 부족 구간 제외와 사유 기록(FR-006), 임의 보간 금지, 거래정지 시 직전 종가 사용을 기록으로 남긴다 | 통과 |
| VII. 탐색과 검증을 구분 | 001은 평가 분할을 전제하지 않고 전체 범위만 산출한다(FR-008). 분할은 007 소관 | 통과 |
| VIII. 재현할 수 있게 기록 | 자료 버전·데이터 마감 시각 기록(FR-013), 발행된 버전 불변(FR-017), 대응표 버전 보존(FR-003) | 통과 |
| IX. 실행 한도는 실측으로 | 수집은 사용자 요청 경로 밖의 배치다. 분산 처리·Spark를 도입하지 않고 단일 파이프라인으로 시작한다 | 통과 |
| X. 자료는 이용 조건 안에서만 | 개발·검증 단계 한정 사용(FR-010), 출처 표시(FR-011), 인증 정보는 서버 관리(FR-012) | 통과 |

**추가 게이트 (루트 CLAUDE.md 아키텍처 원칙)**

| 게이트 | 판정 |
|---|---|
| Backend는 계산하지 않는다 | 통과. 가격 시계열과 공통 기간 산출은 Backend를 거치지 않는다. Backend는 대응표·메타데이터 조회만 제공한다 |
| 빅데이터 기술을 먼저 선택하지 않는다 | 통과. Spark·Hadoop을 쓰지 않고 pandas로 처리한다. 저장은 Parquet 파티션을 유지해 도입 가능성만 열어둔다 |
| 연산 로직은 Worker 내부에 격리 | Phase 0에서 해소. 공통 평가 기간 산출은 시계열을 훑지 않는 최댓값·최솟값 조합이며, 산식을 계약으로 고정해 양쪽이 같은 픽스처로 검증한다 (research.md 결정 3) |

**미해결 clarify 2건의 게이트 영향**: 둘 다 화면 표시 문구에 관한 것으로 자료 구조와 계산을 바꾸지 않는다. Phase 0에서 재확인했고 설계를 막지 않는다.

### Phase 1 설계 후 재판정

10개 원칙과 3개 추가 게이트를 설계 산출물에 대해 다시 확인했다. 전부 통과이며, 설계 과정에서 다음 세 가지가 더 분명해졌다.

- **원칙 X (이용 조건)**: Phase 0에서 저장소 안의 원천 불일치를 발견했다. 그때 `worker`가 실제로 쓰고 있던 yfinance와 `data/CLAUDE.md`가 지정한 pykrx는 모두 원칙 X가 열거한 원천이 아니다. Phase 0의 계획은 `YFinanceDataProvider` 클래스를 남겨 두고 공급자 기본값 자리에서만 빼는 것이었다. 새 공급자만 추가하고 기본값을 그대로 두면 주입을 잊은 경로가 이용 조건 밖 원천으로 조용히 넘어가기 때문이다 (research.md 결정 1).

  **실제로는 004가 더 멀리 갔다** (2026-09-11, [contracts/market-dataset.md](./contracts/market-dataset.md) 「기존 provider 정리」의 덧붙임). `YFinanceDataProvider`는 기본값 자리에서 빠진 것이 아니라 코드에서 사라졌고, `PortfolioBacktestEngine`은 공급자에 **기본값 자체를 두지 않는다** — 생성자의 필수 인자다(`worker/src/worker/engine_runner.py`의 `PortfolioBacktestEngine.__init__`, `worker/CLAUDE.md` "엔진 생성자에 공급자 기본값을 두지 말 것"). 기본값이 없으므로 "주입을 잊은 경로"가 생길 자리가 없어, 원칙 X의 게이트는 계획보다 더 확실하게 지켜진다.
- **원칙 V (당시 정보)**: 소급 조정 계열의 절대 수준이 미래 분배금에 의존한다는 점을 계약에 제약으로 명시했다. 현재 다섯 전략은 모두 비율 기반이라 안전하다 (contracts/market-dataset.md).
- **원칙 VIII (재현)**: 발행된 자료 버전을 수정하지 않는다는 FR-017을 저장소 수준에서 막고, quickstart 시나리오 3이 `UPDATE`가 거부되는지 확인한다.

## Project Structure

### Documentation (this feature)

```text
specs/001-etf-market-data/
├── plan.md              # 이 파일
├── research.md          # Phase 0 산출물
├── data-model.md        # Phase 1 산출물
├── quickstart.md        # Phase 1 산출물
├── contracts/           # Phase 1 산출물
└── tasks.md             # /speckit-tasks 산출물 (이 명령이 만들지 않는다)
```

### Source Code (repository root)

```text
data/                                    # DATA 파트 — 수집·정제 파이프라인
├── src/data_pipeline/
│   ├── krx.py                           # 기존 스캐폴딩. 실제 수집 구현 대상
│   ├── sector_map.py                    # 섹터-ETF 대응표 로딩·검증
│   ├── corporate_actions.py             # 분배금·분할 이벤트 수집과 조정 가격 산출
│   ├── index.py                         # KOSPI 시리즈 일별 자료
│   ├── dataset_version.py               # 자료 버전 발행과 서비스 저장소 적재
│   └── reconciliation.py                # 공시 대조와 원인 분류 기록
├── scripts/
│   ├── ingest_krx.py                    # 기존 스캐폴딩. 진입점 구현 대상
│   ├── ingest_index.py
│   ├── reconcile.py
│   └── suspension_stats.py              # PD-19·PD-20 결정용 정지 길이 분포 집계
├── raw/                                 # 원천 응답 원본 (커밋 금지)
└── processed/                           # Parquet 정제 사본, 자산/연도 파티션 (커밋 금지)

worker/src/worker/
├── market_data.py                       # 저장소를 읽는 PostgresMarketDataProvider (004가 만들었다)
└── evaluation_window.py                 # 공통 평가 가능 기간 산출

backend/src/main/java/com/ssafy/thorp/
└── sector/                              # 신규 도메인 (4계층, ADR-0001)
    ├── presentation/{controller,response}
    ├── application/{service,result,repository}
    ├── domain/{model,vo}
    └── infrastructure/persistence

backend/src/main/resources/db/migration/
├── V20260911114500__etf_market_data.sql    # 대응표·자료 버전·시계열·대조 기록 스키마 (표 11개)
└── V20260913103000__instrument_profile.sql # 대표 상품 정보 (12번째 표)
```

**마이그레이션 파일명은 연속 번호가 아니라 작성 시각이다.** `V{YYYYMMDDHHMMSS}__설명.sql` 형식을 쓴다(`backend/CLAUDE.md`). 여러 spec이 동시에 진행되면서 서로 다른 브랜치가 `V3`이라는 같은 번호를 함께 적어 두는 충돌이 실제로 났기 때문이다. 실제로 `V3__param_search.sql`은 007이 쓰고 있는 다른 마이그레이션이므로, 001의 스키마를 `V3`으로 부르면 남의 파일을 가리키게 된다. 이미 적용된 `V1`~`V4`는 그대로 두며, Flyway가 버전을 숫자로 정렬하므로 두 방식이 섞여도 순서가 뒤집히지 않는다.

대표 상품 정보가 별도 파일인 까닭은 앞 마이그레이션이 data-model.md의 표 12개 중 그 하나를 빠뜨렸고, 뒤늦게 채웠기 때문이다(tasks.md T013). 이미 적용된 파일은 고치지 않는다.

**Structure Decision**: 세 파트에 걸치는 기능이므로 파트별 경계를 따른다.

- **`data/`**: 자료를 확보하고 정제해 적재하는 모든 로직. `data/CLAUDE.md`의 구조 규칙(`src/data_pipeline/` 재사용 로직, `scripts/` argparse 진입점, `processed/` Parquet 자산/연도 파티션, 소문자 컬럼 표준, 멱등한 재실행)을 그대로 지킨다. 컬럼 표준에는 `value`(거래대금)를 더한다(research.md 결정 6). 서비스 저장소 적재도 이 파이프라인의 출력이며, 대응표와 자료 버전 발행이 같은 경로에 있다.
- **`worker/`**: 서비스 저장소를 읽는 공급자는 **새로 만들지 않는다.** Phase 1은 `RepositoryDataProvider`라는 이름으로 신설할 계획이었으나, 004가 같은 자리를 맡는 `PostgresMarketDataProvider`(`worker/src/worker/market_data.py`)를 먼저 만들었다. 001의 몫은 그 구현이 읽는 SQL이 여기 정의한 스키마와 맞는지 확인하는 것이다. 공급자 주입은 `worker/src/worker/main.py`가 하며 엔진에는 기본값이 없다(위 「Phase 1 설계 후 재판정」 원칙 X). `validate_prices`의 검증 규칙(중복·비단조 날짜 거부, 결측·비유한 값 거부, OHLC 정합성)은 FR-004~FR-006과 이미 일치하므로 재사용한다.
- **`backend/`**: 섹터 목록과 대표 상품 정보는 화면이 조회해야 하므로 `sector/` 도메인을 신설한다. `backend/CLAUDE.md`의 4계층 규칙과 ArchUnit 검증을 따르며, 가격 시계열은 다루지 않는다.

기존 `market/` 도메인은 거시 지표 카드(`/markets/{slug}`)용이며 008이 소유한다. 001의 섹터·ETF는 성격이 다르므로 별도 도메인으로 둔다.

## 미확정 항목을 병렬 작업으로 처리하는 방식

PD-01과 PD-19·PD-20은 실제 자료를 봐야 정할 수 있다. 확정을 기다리지 않고, **본선 구현과 의존 관계가 없는 별개 흐름**으로 둔다. constitution 「미확인 항목 처리」가 "미확인 항목은 개발 진행을 막는 조건이 아니다"라고 정한 방식이다.

```
본선  예시 대응표로 전 구간 구현
      파이프라인 → 저장소 적재 → provider → 조회 API → 검증
      기능이 다 완성되고 대응표 값만 예시다 (isSample = true)

병렬 A  PD-01 확정
        자료 이용 승인 신청
        → 섹터별 ETF 후보 3~5개를 실제로 조회
        → 확보 가능 기간·기업행위 이력 완전성·거래대금을 나란히 비교
        → 대표 ETF 확정 → 예시 대응표를 실제 값으로 교체, isSample = false

병렬 B  PD-19·PD-20 확정
        본선이 쌓아 둔 suspension_period 기록에서 정지 길이 분포 집계
        → 처리 방식 B·C·D 각각이 공통 평가 기간을 얼마나 줄이는지 계산
        → PD-19·PD-20 확정 → FR-006과 계약 갱신
```

**병렬 A의 비교 대상은 ETF 후보들끼리다.** 예시 자료로 낸 수익률은 근거가 못 되므로 "예시 결과와 실제 결과를 비교"하는 일이 아니다. 후보 ETF를 실제 자료로 놓고 "어느 쪽이 자료가 온전한가, 어느 쪽이 거래가 충분한가"를 보고 고른다.

**병렬 B는 본선이 입력을 만들어 준다.** 확정 전 임시 동작이 모든 정지를 기록하도록 되어 있으므로, 분포 조사를 위해 따로 자료를 뒤질 필요가 없다. 이것이 임시 동작을 "정지를 전부 제외"가 아니라 "전부 직전 종가 유지 + 기록"으로 고른 이유다.

## Complexity Tracking

> Constitution Check에서 부분 통과한 항목의 근거다.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 공통 평가 기간 산식이 Worker와 Backend 양쪽에 구현된다 | FR-007의 산출값은 백테스트 실행(004, Worker)에도 필요하고 실행 전 안내(003, 화면)에도 필요하다. 후보 집합 조합이 지수적이라 미리 계산해 둘 수 없다 | Backend에 계산을 몰면 계산 금지 원칙에 해석 논쟁이 남고, Worker에만 두면 안내 문구 하나를 얻으려고 큐를 왕복해야 한다(원칙 IX와 충돌). 중복 구현의 위험은 `contracts/evaluation-window.md`와 공용 픽스처로 막는다. 프로젝트가 브로커 메시지 계약에 이미 쓰는 방식이다 |
| 정제 자료를 Parquet 사본과 서비스 저장소 두 곳에 둔다 | 서비스는 PostgreSQL만 읽지만(Worker에 공유 볼륨이 없다), 원천을 다시 부르지 않고 재정제할 중간 산출물과 추후 Spark가 읽을 형식이 필요하다 | Parquet만 쓰면 Worker가 자료에 접근할 수 없고, PostgreSQL만 쓰면 `data/CLAUDE.md`가 파티션 구조를 고정한 근거를 버린다. 적재를 `raw → processed → DB` 한 방향으로 묶고 발행된 버전을 양쪽 모두 고치지 않아 어긋날 창을 좁힌다 (research.md 결정 7·8) |

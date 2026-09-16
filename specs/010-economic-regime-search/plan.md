# Implementation Plan: 경제환경 검색 (비슷했던 때 보기)

**Branch**: `010-economic-regime-search` (spec 디렉터리 식별자. 구현 Task의 Git 브랜치는 Jira Task마다 `{type}({position})/{JIRA_NUMBER}-{description}`로 따로 만든다.) | **Date**: 2026-09-15 (2026-09-16 개정) | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/010-economic-regime-search/spec.md`

> **2026-09-17 (모델 v2)**: 척도를 **후보마다 그 직전 구간**에서 만든다(FR-005). `era.py`가 없어지고
> `scale.py`가 질의·후보 양쪽에 쓰인다. FR-017 폐지. **기획서 D21 변경이 선행**된다.
>
> **2026-09-16 개정**: spec Clarifications(Session 2026-09-16) 8건을 반영했다. 설계에서 바뀐 것은
> ① **008 경계를 둘로 나눠 각각의 필요에 맞췄다** — Backend의 `MacroIndicatorPort`는 등록표(이름·
> 단위·순서)만, 시계열은 `data/` 배치의 `MacroIndicatorSource`만. 기준월은 배치가 표에 기록,
> ② 재계산 범위를 `t ≥ M` 워터마크로 좁히고 결과가 달라진 것만 새 `revision`으로 저장,
> ③ 전체 유효 후보 목록 보존(011의 가용성 필터 입력), ④ 지표별 비교 시점,
> ⑤ `sector` → `field` 개명이다.

## Summary

지금과 비슷했던 과거 구간을 경제환경 모델 v2로 찾아, 분야별 검색을 기본으로 하고 Thorp 종합 비교를
선택해서 열 수 있게 한다. 거리순 사례를 처음 3개(더 보기 최대 10개) 보이고, 그 결과가 어느 상태인지
(계산 불가 / 가까운 순서만 / 교정된 기준 충족)를 결과 옆에 항상 표시한다.

기술 접근은 **오프라인 사전 계산 + 읽기 전용 조회**다. `data/` 파이프라인이 기준월마다 척도·거리·
순위를 미리 계산해 PostgreSQL에 적재하고, Backend는 그 행을 읽어 HTTP로 내준다. 사용자 요청 처리
중에는 척도도 거리도 계산하지 않는다(FR-030, SC-002a). 이 구조가 재현성(SC-002)을 구조적으로
보장한다 — 같은 식별의 재검색은 같은 행을 읽는 일이라 달라질 여지가 없다.

**008 경계는 둘이고 담는 것이 다르다.** 계산에 쓰는 시계열·발표일·버전은 `data/` 배치의
`MacroIndicatorSource`만 읽고, 화면에 붙일 이름·단위·분야 순서는 Backend의 `MacroIndicatorPort`가
읽는다 — 004의 `MarketMetadataPort`, 007의 `ParameterRegistryPort`와 같은 모양이다. **포트에 시계열은
없다**(Backend는 계산하지 않는다). **기준월 T_g·T_all은 어느 쪽도 실시간으로 읽지 않는다** — 배치가
`regime_baseline_month`에 기록하고 Backend가 그 표를 읽는다. 008이 새 발표분으로 T_g를 올려도 배치가
아직 돌지 않았으면 그 달에는 결과가 없기 때문이다
([contracts/macro-indicator-port.md](contracts/macro-indicator-port.md) §0.2). 008이 미구현이므로
양쪽에 픽스처 구현체를 두고 완성 시 구현체만 교체한다.

## Technical Context

**Language/Version**: Python 3.11 (`data/` 사전 계산 배치 — 계산의 본체), Java 21 (Backend 조회 API), TypeScript 5 / React 19 (Frontend)

**Primary Dependencies**: pandas·NumPy·SciPy(`scipy.stats.norm.ppf` — 정규분포 보정 상수, research R5) / Spring Boot 3.5.4 (Web, Data JPA, Validation), Flyway, PostgreSQL 드라이버, Jackson / 프론트는 기존 의존만. **브로커(RabbitMQ)를 쓰지 않는다** — 동기 조회이고 Job이 아니다

**Storage**: PostgreSQL. Flyway 마이그레이션 신규 1개(`regime_search_snapshot`·`regime_baseline_month`·`regime_search_case`·`regime_search_log`). 사전 계산 중간 산출은 `data/processed/`에 Parquet(자산/연도 파티션 규칙은 `data/CLAUDE.md`)

**Testing**: pytest (`data/` 배치 — 계산 규칙의 본체가 여기 있으므로 시험의 무게중심도 여기다), JUnit 5 + Testcontainers(PostgreSQL) + Spring Boot Test (Backend), Vitest (Frontend)

**Target Platform**: Linux 서버(Docker), 데스크톱 브라우저 우선 한국어 웹

**Project Type**: 모노레포 웹 서비스. 이번 범위는 `data/` 배치 신규 + Backend 신규 도메인(`regime`, 읽기 전용) + Frontend 화면 2개(분야 검색 결과, 사례 상세)

**Performance Goals**: 조회 p95 300ms(research R9 — **측정 목표이지 한도가 아니다**). 사전 계산 배치에는 목표를 두지 않고 소요 시간과 다시 계산한 기준월 수를 기록만 한다. 미측정 처리량을 성능으로 제시하지 않는다(원칙 IX)

**Constraints**: 요청 처리 중 척도·거리 계산 0건 / 절대 문턱 숫자를 만들지 않을 것(PD-09 미확정) / 임의 epsilon·클리핑·지표 몰래 제외 0건(FR-007) / 후보 부족 시 채우지 않을 것(FR-013) / 확률·추천 표현 0건(FR-022) / **010 코드에 지표 ID·지표명·분야 이름 0건**(배치·Backend·화면 전부 — 값은 포트와 저장된 행에서만 온다) / Backend에서 지표 시계열 조회 0건

**Scale/Scope**: 최초 적재 ≈ 기준월 378 × 모드 8 ≈ 3,000 항목, 표시 후보 ≈ 30,000행. 이후 새 발표분 1회에 새로 생기는 행은 **보통 8~24 항목**(결과가 달라진 것만, research R3). Backend 신규 패키지 1개(`regime`), 테이블 4개, 엔드포인트 5개, Frontend 화면 2개

### 해소된 NEEDS CLARIFICATION

Phase 0에서 10건을 해소했고, 2026-09-16에 R1·R3을 개정했다. 상세 근거는 [research.md](research.md).

| 항목 | 결론 |
|---|---|
| 지표 자료 원천 (008 미구현) | 경계 둘 — 배치의 `MacroIndicatorSource`(시계열)와 Backend의 `MacroIndicatorPort`(등록표). 기준월은 배치가 표에 기록. 008 완성 시 구현체만 교체 (R1, 2026-09-16 개정) |
| 사전 계산 위치 | `data/` 파이프라인 배치 → PostgreSQL 적재. Backend는 읽기만 (R2) |
| 사전 계산 단위·범위·저장 | 단위는 기준월 × 모드 8. 범위는 자료가 바뀐 가장 오래된 월 M부터(`t ≥ M`). 저장은 결과가 달라진 것만 새 `revision` (R3, 2026-09-16 개정) |
| 소수 연산·재현성 | double 계산 후 **12자리 반올림해 저장**, 순위·동률 판정은 저장값으로 (R4) |
| 분위수·보정 상수 | IQR은 선형 보간, 상수는 `Φ⁻¹(0.75)`를 실제 계산 (R5) |
| 유사성 문턱 (PD-09 미확정) | 상태 `NEAREST_ONLY` 고정, 문턱 값을 코드에 두지 않음 (R6) |
| 최소 유효 척도 (PD-10, 008 소유) | MAD→IQR→계산 불가 세 단계만 구현 (R7) |
| 비슷한 점·다른 점 문장 | 템플릿 조립. 자유 생성 금지, 양쪽 최대 2개씩 (R8) |
| 목표 응답 시간 | 조회 p95 300ms, 배치는 기록만 (R9) |
| 기존 `market` 도메인 | 건드리지 않는다. 새 도메인 `regime` (R10) |

## Constitution Check

*GATE: Phase 0 이전 통과 필수. Phase 1 이후 재확인.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. 중심 약속은 전략 진단 | PASS | 경제환경 검색은 전략 진단의 입구다. 사례 이후 전략 결과는 011이 붙이고 010은 후보만 정한다 |
| II. 첫 화면은 단순하게, 복잡한 계산은 시스템이 맡는다 | PASS | 사용자는 분야를 고르고 버튼 하나를 누른다. 거리 함수·척도·지표 조합을 고르는 화면을 두지 않는다(FR-005, US1 시나리오 5) |
| III. 진단이지 추천이 아니다 | PASS | '대표 유사국면' 배지·확률 표현을 계약 수준에서 배제했다([contracts/regime-search-api.md](contracts/regime-search-api.md) §6). 거리는 순서일 뿐 점수가 아니다 |
| IV. 비교는 같은 조건에서만 한다 | PASS | **각자의 시대 기준 위치**로 재고 비교 시점은 질의·후보가 같다(FR-001·FR-005). z는 둘 다 "평소 대비 얼마나 벗어났나"라는 같은 뜻의 무차원 값이다 |
| V. 당시에 알 수 있던 정보만 쓴다 | **조건부 PASS** | 최신 개정자료를 쓰는 **역사 탐색**이며 당시 재현이 아니다. 응답의 `basis.kind`가 항상 `HISTORICAL_REVISION`이고 화면이 이를 노출하며, **SC-009가 그 충족을 측정한다**(FR-027) |
| VI. 없는 결과를 만들어내지 않는다 | PASS | 척도 0·60개월 미만·질의 입력 없음·후보 없음을 전부 `NOT_COMPUTABLE`로 내보내고 **200으로** 처리한다. epsilon·클리핑·지표 몰래 제외 없음. 결측 달은 채우지 않고 비교에서 빼되 그 사실을 표기한다(FR-001·FR-031) |
| VII. 탐색과 검증을 구분하고 기준은 결과 전에 고정 | PASS | 문턱 미교정을 `NEAREST_ONLY`로 표시하고 합격·불합격을 단정하지 않는다(R6). 가중·거리식은 결과를 보기 전에 spec이 고정했다(PD-08) |
| VIII. 재현할 수 있게 기록한다 | PASS | 자료·등록표·모델 버전이 식별 키에 들어간다. 결과가 달라지면 새 `revision`이고 이전 행을 덮어쓰지 않는다. 006 기록은 특정 행을 고정하고, 그 행은 보관 대상이다(data-model §7) |
| IX. 실행 한도는 실측으로 정하고 과금과 구분한다 | PASS | 배치 소요 시간·다시 계산한 기준월 수·실패를 기록만 하고 목표로 제시하지 않는다(R9). 결제·과금 단계 없음 |
| X. 자료는 이용 조건 안에서만 쓴다 | PASS | 원천 수집·이용 조건은 008 소유다. 010은 적재된 자료만 읽고 원천을 직접 호출하지 않는다 |

**조건부 PASS 하나(원칙 V)**는 회피가 아니다 — FR-027이 "역사 탐색"과 "당시 재현"을 구분해 화면에
표시하게 하고, **SC-009가 그것을 100% 기준으로 측정한다.** 측정 항목이 없으면 화면·태스크에서
조용히 빠지므로 2026-09-16에 SC-009를 신설했다. Complexity Tracking에 적을 위반이 아니다.

### Phase 1 이후 재확인

| 원칙 | 판정 | 설계가 바꾼 것 |
|---|---|---|
| IV | **재해석** | 모델 v2에서 "같은 조건"의 뜻이 바뀌었다 — 같은 자로 재는 것이 아니라 **각자의 시대 기준 위치로 재는 것**이 같은 조건이다. 수준 지표는 시대에 따라 같은 값의 뜻이 달라서, 한 자로 재면 오히려 조건이 어긋난다(FR-005). 비교 시점은 여전히 질의·후보가 같다 |
| VI | **강화** | `NOT_COMPUTABLE`을 200으로 내보내고 503을 쓰지 않기로 계약에 적었다. 기여도는 거리 0일 때 **키를 생략**한다. 가용성 필터로 순위를 바꾸지 않고 전체 후보 목록을 011에게 그대로 넘긴다 |
| VIII | **강화** | `data_asof`를 008에서 복사해 저장한다. `content_hash`로 같은 내용이 두 이름으로 저장되는 것을 막고, 달라진 때만 `revision`이 올라 "언제 무엇이 바뀌었나"가 행으로 남는다 |
| III | PASS 유지 | API 계약 §6에 "내보내지 않는 것" 표를 두어 유사도·배지·유지율이 응답에 들어갈 자리를 없앴다 |
| V | **강화** | `basis.kind`를 응답 필수 필드로 두고 SC-009로 측정한다. **모델 v2가 한 겹 더 강화했다** — v1은 2012년 후보를 2016~2026 자료로 만든 자로 쟀는데 그것은 그 후보 시점에서 보면 **미래 자료**다. v2의 후보 척도는 `c-122~c-3`이라 **후보 이후 자료를 전혀 쓰지 않는다**(FR-005). 최신 개정값을 쓴다는 점은 그대로이므로 조건부 PASS는 유지된다 |

## Project Structure

### Documentation (this feature)

```text
specs/010-economic-regime-search/
├── plan.md                            # 이 파일
├── research.md                        # Phase 0
├── data-model.md                      # Phase 1
├── quickstart.md                      # Phase 1
├── contracts/
│   ├── regime-search-api.md           # HTTP 계약
│   └── macro-indicator-port.md        # 008 경계 (포트·소스)
├── checklists/requirements.md
└── tasks.md                           # /speckit-tasks 산출물
```

### Source Code (repository root)

```text
data/
├── src/data_pipeline/regime/          # 사전 계산 — 계산 규칙의 본체
│   ├── macro/
│   │   ├── source.py                  # MacroIndicatorSource 프로토콜 (008 경계)
│   │   ├── postgres.py                # 008 완성 후 구현체
│   │   └── fixture/                   # ⚠ [임시] 008 대역 — 008 완성 시 통째 삭제
│   ├── rounding.py                    # 12자리 반올림 — 저장·비교 직전 한 번
│   ├── scale.py                       # `scale_for(지표, 관측 종료월)` — 연속 구간·중앙값·MAD·IQR. **질의와 후보 양쪽**이 같은 함수를 쓴다
│   ├── comparison.py                  # 지표별 비교 시점 판정 (FR-001)
│   ├── distance.py                    # z, E_i, D_g, 종합 거리, 지표·분야 기여
│   ├── candidates.py                  # 12개월 초과·±6개월 건너뛰기·동률·전체 순위·상위 10
│   ├── explain.py                     # 비슷한 점·다른 점 템플릿 조립
│   ├── snapshot.py                    # 기준월 단위 조립, 검색 상태, content_hash
│   ├── watermark.py                   # 재계산 범위 t ≥ M 판정 (FR-030)
│   └── load.py                        # PostgreSQL 적재(revision·기준월·보관 정리)
├── scripts/precompute_regime.py       # 실행 진입점 (argparse)
└── tests/regime/                      # pytest — 계산 규칙 시험

backend/src/main/java/com/ssafy/thorp/regime/     # 계산하지 않는다. 008에서는 등록표만 읽는다
├── presentation/controller|response/  # GET 5개
├── application/service|result|repository/
│   └── port/MacroIndicatorPort.java   # 008 등록표 경계 (이름·단위·분야 순서)
├── domain/model|vo/                   # SearchStatus, StatusReason, SearchMode, SnapshotKey
└── infrastructure/
    ├── persistence/                    # snapshot·baseline-month·case 조회
    ├── config/RegimeProperties.java     # macro.source (fixture|macro) — 코드 기본값 없음
    └── macro/
        ├── MacroIndicatorAdapter.java  # 008 완성 후 작성
        └── fixture/                    # ⚠ [임시] 008 대역 — 008 완성 시 통째 삭제

backend/src/main/resources/
└── db/migration/V{YYYYMMDDHHMMSS}__regime_search.sql

frontend/src/
├── pages/RegimeSearchPage.tsx
├── components/regime/                 # 결과 목록, 사례 상세, 상태 표시, 자료 기준 시점, 비교 시점 표기
├── hooks/useRegimeSearch.ts
├── api/regime.ts
└── types/regime.ts
```

**Structure Decision**: 계산은 `data/`, 조회는 `backend/regime`, 화면은 `frontend`로 셋으로 나눈다.
루트 `AGENTS.md`의 **"Backend는 계산하지 않는다"** 와 `data/CLAUDE.md`의 적재 방향
(`raw → processed → PostgreSQL`, *"Backend·Worker가 읽는 곳은 PostgreSQL이다"*)이 그대로 맞는다.
**Backend가 008에서 읽는 것은 등록표뿐이고 시계열은 읽지 않는다** — 계산 입력은 `data/`,
표시용 이름은 포트다. Backend 패키지는 ADR-0001의 4계층을 따르고 기존 `market` 도메인은 건드리지
않는다(research R10).

## 구현 순서

묶음마다 끝에 검증 명령이 통과해야 다음으로 간다. 파트가 갈리므로 Jira 티켓도 묶음 단위로 나뉜다.

| 묶음 | 파트 | 내용 | 끝났다고 말할 근거 |
|---|---|---|---|
| **M1** | DE | 지표 소스 계약 + **격리된** 픽스처 대역 | 아홉 가지 모양이 다 들어 있고, 픽스처가 `macro/fixture/` 밖으로 나가지 않으며, 자료 버전이 `FIXTURE-`로 시작하고, `THORP_ENV=prod`에서 `--source fixture`면 적재 전에 실패한다 |
| **M2** | DE | `scale.py`·`comparison.py` — 연속 구간, 중앙값·MAD·IQR, 지표별 비교 시점 | 구멍 뚫린 계열이 60개월 미만으로 판정된다. 상수 계열이 IQR로 넘어가고 완전 상수는 계산 불가다. 한 달 결측이 그 지표만 두 달로 줄이고, 세 달 결측이 분야를 계산 불가로 만든다 |
| **M3** | DE | `distance.py` — z(질의는 질의 자, **후보는 후보 자**)·E_i·D_g·종합·지표 기여·분야 기여 | 수기 계산과 일치(SC-003). 거리 0이면 기여 키가 없다. 명목 비중과 실제 기여가 함께 나온다 |
| **M4** | DE | `candidates.py`·`explain.py` — 후보 제한·전체 순위·상위 10·설명 문장 | 12개월 이내 제외, ±6개월 건너뛰기, 동률 오래된 월 우선, 부족하면 부족한 대로. 전체 유효 후보 목록이 상위 10개 밖까지 나온다 |
| **M5** | DE | `watermark.py`·`snapshot.py`·`load.py` — 재계산 범위·상태 판정·적재 | `t ≥ M`만 다시 돌고, 내용이 같으면 새 행이 안 생기며(멱등), 달라지면 `revision`이 오른다. `regime_baseline_month`가 함께 적재된다 |
| **M6** | DB·BE | 마이그레이션 + `MacroIndicatorPort`·임시 어댑터 + `regime` 도메인 조회 API 5개 | 계약 예시와 응답이 일치. `NOT_COMPUTABLE`이 200. `regime` 패키지에 계산 코드도 시계열 조회도 없다. prod 프로파일 + `source: fixture`면 기동 실패 |
| **M7** | FE | 결과 목록·상태 표시·자료 기준 시점·비교 시점 표기·역사 비교 표시 | 배지·확률 표현 0건, 후보 부족 시 빈자리 없음, 빠진 지표·월과 역사 비교 표시가 보인다 |
| **M8** | FE | 사례 상세(거리·기여·z·척도) + 종합 비교 전환 + 기준월 지정 | 모드 전환 시 한 문장 알림, 기준월 표시, 유지율 0건 |
| **M9** | DOCS | quickstart 실행 기록·폐기 예행연습 | S1~S9 실행 기록이 남고, 픽스처 디렉터리를 지웠을 때 운영 코드가 깨지지 않음을 확인했다 |

**M2·M3·M4를 가장 먼저 두는 이유**: 이 셋이 010의 전부다. 화면과 API는 그 결과를 옮기는 일이고,
틀렸을 때 드러나는 곳도 여기다. 007이 화면부터 만들어 워커 없이 숫자를 주입하게 된 전철을 밟지
않는다.

**M1이 DE 단독인 이유**: Backend의 `MacroIndicatorPort`를 쓰는 곳은 `baseline-months` 엔드포인트
하나뿐이라 M6 안에서 끝난다. 이전 계획은 포트를 M1에 두어 DE·BE 혼합 묶음이 됐다 — 같은 브랜치에
파이프라인과 백엔드가 섞이면 리뷰 범위가 두 배가 되고 되돌릴 때도 함께 움직인다.

## 위험과 그에 대한 대비

| 위험 | 대비 |
|---|---|
| 008이 늦어져 실제 자료로 한 번도 못 돌아 본다 | 픽스처로 **계산 규칙**은 전부 검증된다. 검증되지 않는 것은 "사례가 그럴듯한가"이며, 그 한계를 quickstart에 명시하고 화면에 예시 자료임을 표시한다(008 FR-022) |
| 픽스처가 만든 가짜 자료가 008 이후에도 남는다 | 자료 버전 `FIXTURE-` 접두사 + 디렉터리 격리 + 적재 가드·기동 가드. 폐기는 조건 한 줄([contracts/macro-indicator-port.md](contracts/macro-indicator-port.md) §4.3) |
| **배치가 아직 안 돌아 화면의 기준월이 008보다 뒤처진다** | 정상 동작이다. 화면은 `regime_baseline_month`만 보므로 결과 없는 달을 제시하지 않는다. 008의 최신 T_g를 화면이 직접 읽지 않는 이유가 이것이다(포트 계약 §0.2) |
| 008의 실제 과거 범위가 픽스처와 달라 규모가 어긋난다 | 후보 지평을 "자료가 있는 전 구간"으로 정했으므로 코드가 범위에 의존하지 않는다. 규모는 배치 소요 시간으로 실측한다 |
| 부동소수점 때문에 재검색 순위가 흔들린다 | 정렬 전 12자리 반올림(research R4). 회귀 테스트로 같은 입력 2회 실행의 순위 동일을 고정한다 |
| **`content_hash`가 흔들려 같은 결과가 새 revision으로 쌓인다** | 해시 입력에 `computed_at` 같은 시각 값을 넣지 않는다. 배치를 두 번 돌려 행 수가 늘지 않음을 테스트로 고정한다(SC-002) |
| **008의 `changed_since()`가 개정을 놓쳐 낡은 결과가 남는다** | `None`이면 전 구간 재계산으로 물러난다. 008 완성 후 교체 점검 항목에 넣었다(포트 계약 §4) |
| PD-09가 확정되며 상태가 늘어 기존 결과 해석이 바뀐다 | 검색 상태를 **저장 값**으로 두고 조회 시 재판정하지 않는다(research R6). 이전 `NEAREST_ONLY` 기록은 그대로 남는다 |
| 지표명·분야명이 화면 코드에 스며든다 | 007과 같은 정적 검사를 붙인다. 대상 파일은 손 목록이 아니라 훑어서 모은다 |

## 임시 대역의 격리와 폐기

008이 없어 픽스처로 시작한다. **처음부터 한 번에 지워지도록 만든다** — 008이 완성된 날 "어디를
지워야 하지"를 뒤지게 되면 가짜 자료가 운영에 남는다. 007의 `PROVISIONAL-` 등록표가 같은 문제를
버전 문자열로 풀었고, 여기서는 그것을 **자료 버전과 디렉터리 경계** 둘로 푼다.

| | 규칙 |
|---|---|
| **격리** | 지울 것이 **두 디렉터리**에 모인다 — `data/…/regime/macro/fixture/`(시계열), `backend/…/regime/infrastructure/macro/fixture/`(등록표). **두 픽스처는 담는 것이 달라 어긋날 자리가 없다.** 겹칠 뻔했던 T_g·T_all은 배치가 표에 기록하는 쪽으로 뺐다 |
| **전환** | 배치는 `--source fixture \| macro`(기본 `macro`), Backend는 `thorp.regime.macro.source`. **`THORP_ENV=prod`에서 `fixture`면 적재 전에 실패**하고 **prod 프로파일에서 `fixture`면 기동에 실패한다** |
| **표기** | 자료 버전이 `FIXTURE-`로 시작 / API `versions.sampleData: true` / 화면에 예시 자료 표시 |
| **폐기** | 디렉터리 2개 삭제 + 프로퍼티 키 삭제 + `DELETE … WHERE data_version LIKE 'FIXTURE-%'` + 전 구간 재계산 |

**가장 오래 남는 것은 코드가 아니라 DB 행이다.** 그래서 표기를 `data_version`에 박는다 — 식별
키·API 응답·기록에 전부 실려 다니므로 값이 어느 경로로 흘러가도 가짜임이 드러나고, 폐기가 조건
한 줄로 끝난다. 절차는 [contracts/macro-indicator-port.md](contracts/macro-indicator-port.md) §3.3.

**픽스처의 열 모양은 008이 와도 남긴다.** 실제 자료에 상수 계열이나 정확한 동률이 없을 수 있는데,
그러면 `NOT_COMPUTABLE`·동률 경로가 검증 밖으로 나간다. 운영 대역(위 한 디렉터리)은 폐기하되 같은
모양을 담은 **단위 테스트 픽스처**는 `data/tests/regime/fixtures/`에 남는다.

## Complexity Tracking

> Constitution Check에 정당화가 필요한 위반이 없다. 이 절은 비운다.

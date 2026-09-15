# Implementation Plan: 경제환경 검색 (비슷했던 때 보기)

**Branch**: `010-economic-regime-search` (spec 디렉터리 식별자. 구현 Task의 Git 브랜치는 Jira Task마다 `{type}({position})/{JIRA_NUMBER}-{description}`로 따로 만든다.) | **Date**: 2026-09-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/010-economic-regime-search/spec.md`

## Summary

지금과 비슷했던 과거 구간을 경제환경 모델 v1로 찾아, 분야별 검색을 기본으로 하고 Thorp 종합 비교를
선택해서 열 수 있게 한다. 거리순 사례를 처음 3개(더 보기 최대 10개) 보이고, 그 결과가 어느 상태인지
(계산 불가 / 가까운 순서만 / 교정된 기준 충족)를 결과 옆에 항상 표시한다.

기술 접근은 **오프라인 사전 계산 + 읽기 전용 조회**다. `data/` 파이프라인이 자료 전 구간의 기준월마다
척도·거리·순위를 미리 계산해 PostgreSQL에 적재하고, Backend는 그 행을 읽어 HTTP로 내준다. 사용자
요청 처리 중에는 척도도 거리도 계산하지 않는다(FR-030, SC-002a). 이 구조가 재현성(SC-002)을
구조적으로 보장한다 — 같은 식별의 재검색은 같은 행을 읽는 일이라 달라질 여지가 없다.

**지표는 010이 갖지 않는다.** 지표 17개가 무엇인지, 어느 분야인지, 값이 얼마인지는 전부 008이
`MacroIndicatorPort`로 준다(FR-004, [contracts/macro-indicator-port.md](contracts/macro-indicator-port.md)).
008이 미구현이므로 픽스처 어댑터로 만들고 완성 시 어댑터만 교체한다 — 007이 003 없이 완성된 경로와
같다.

## Technical Context

**Language/Version**: Java 21 (Backend 조회 API), Python 3.11 (`data/` 사전 계산 배치), TypeScript 5 / React 19 (Frontend)

**Primary Dependencies**: Spring Boot 3.5.4 (Web, Data JPA, Validation), Flyway, PostgreSQL 드라이버, Jackson / pandas·NumPy·SciPy(`scipy.stats.norm.ppf` — 정규분포 보정 상수, research R5) / 프론트는 기존 의존만. **브로커(RabbitMQ)를 쓰지 않는다** — 동기 조회이고 Job이 아니다

**Storage**: PostgreSQL. Flyway 마이그레이션 신규 1개(`regime_search_snapshot`·`regime_search_case`·`regime_search_log`). 사전 계산 중간 산출은 `data/processed/`에 Parquet(자산/연도 파티션 규칙은 `data/CLAUDE.md`)

**Testing**: JUnit 5 + Testcontainers(PostgreSQL) + Spring Boot Test (Backend), pytest (`data/` 배치 — 계산 규칙의 본체가 여기 있으므로 시험의 무게중심도 여기다), Vitest (Frontend)

**Target Platform**: Linux 서버(Docker), 데스크톱 브라우저 우선 한국어 웹

**Project Type**: 모노레포 웹 서비스. 이번 범위는 `data/` 배치 신규 + Backend 신규 도메인(`regime`, 읽기 전용) + Frontend 화면 2개(분야 검색 결과, 사례 상세)

**Performance Goals**: 조회 p95 300ms(research R9 — **측정 목표이지 한도가 아니다**). 사전 계산 배치에는 목표를 두지 않고 소요 시간을 기록만 한다. 미측정 처리량을 성능으로 제시하지 않는다(원칙 IX)

**Constraints**: 요청 처리 중 척도·거리 계산 0건 / 절대 문턱 숫자를 만들지 않을 것(PD-09 미확정) / 임의 epsilon·클리핑·지표 몰래 제외 0건(FR-007) / 후보 부족 시 채우지 않을 것(FR-013) / 확률·추천 표현 0건(FR-022) / 010 운영 코드에 지표 ID·지표명·분야 이름 0건

**Scale/Scope**: 사전 계산 항목 ≈ 기준월 378 × 모드 8 ≈ 3,000건, 저장 행 ≈ 30,000행, 전량 재계산 수 초(research R3). Backend 신규 패키지 1개(`regime`), 테이블 3개, 엔드포인트 3개, Frontend 화면 2개

### 해소된 NEEDS CLARIFICATION

Phase 0에서 10건을 해소했다. 상세 근거는 [research.md](research.md).

| 항목 | 결론 |
|---|---|
| 지표 자료 원천 (008 미구현) | `MacroIndicatorPort` + 픽스처 어댑터. 008 완성 시 어댑터 교체 (R1) |
| 사전 계산 위치 | `data/` 파이프라인 배치 → PostgreSQL 적재. Backend는 읽기만 (R2) |
| 사전 계산 단위·규모 | (버전 셋) × 기준월 × 모드 8. 전량 재계산이 수 초라 증분을 만들지 않는다 (R3) |
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
| III. 진단이지 추천이 아니다 | PASS | '대표 유사국면' 배지·확률 표현을 계약 수준에서 배제했다([contracts/regime-search-api.md](contracts/regime-search-api.md) §4). 거리는 순서일 뿐 점수가 아니다 |
| IV. 비교는 같은 조건에서만 한다 | PASS | 질의와 모든 후보에 **같은 m·s**를 적용한다(FR-005). 시대별 위치는 보조 설명으로 분리해 기본 순위와 섞지 않는다(FR-017) |
| V. 당시에 알 수 있던 정보만 쓴다 | **조건부 PASS** | 최신 개정자료를 쓰는 **역사 탐색**이며 당시 재현이 아니다. 그 사실을 화면에 표시한다(FR-027, 008 FR-012). 빈티지 확보 전에는 실시간 전략 검증을 주장하지 않는다 |
| VI. 없는 결과를 만들어내지 않는다 | PASS | 척도 0·60개월 미만·후보 없음을 전부 `NOT_COMPUTABLE`로 내보내고 **200으로** 처리한다. epsilon·클리핑·지표 몰래 제외 없음. 후보 부족 시 채우지 않는다 |
| VII. 탐색과 검증을 구분하고 기준은 결과 전에 고정 | PASS | 문턱 미교정을 `NEAREST_ONLY`로 표시하고 합격·불합격을 단정하지 않는다(R6). 가중·거리식은 결과를 보기 전에 spec이 고정했다(PD-08) |
| VIII. 재현할 수 있게 기록한다 | PASS | 자료·등록표·모델 버전이 식별 키에 들어간다. 버전이 바뀌면 새 행이고 이전 행을 덮어쓰지 않는다. 척도 m·s와 z를 상세에 남겨 독립 계산으로 대조할 수 있다(FR-028) |
| IX. 실행 한도는 실측으로 정하고 과금과 구분한다 | PASS | 배치 소요 시간·실패를 기록만 하고 목표로 제시하지 않는다(R9). 결제·과금 단계 없음 |
| X. 자료는 이용 조건 안에서만 쓴다 | PASS | 원천 수집·이용 조건은 008 소유다. 010은 적재된 자료만 읽고 원천을 직접 호출하지 않는다 |

**조건부 PASS 하나(원칙 V)**는 회피가 아니라 spec이 이미 정면으로 다룬 항목이다 — FR-027이
"역사 탐색"과 "당시 재현"을 구분해 화면에 표시하게 하고, 빈티지 확보 전 실시간 전략 검증 주장을
금지한다. Complexity Tracking에 적을 위반이 아니다.

### Phase 1 이후 재확인

| 원칙 | 판정 | 설계가 바꾼 것 |
|---|---|---|
| IV | PASS 유지 | 사전 계산 단위를 기준월로 잡아 같은 m·s가 한 snapshot 안의 모든 후보에 적용됨을 스키마로 고정 |
| VI | **강화** | `NOT_COMPUTABLE`을 200으로 내보내고 503을 쓰지 않기로 계약에 적었다. 기여도는 거리 0일 때 **키를 생략**한다 — 0으로 채우지 않는다 |
| VIII | **강화** | `data_asof`를 008에서 복사해 저장한다. 계산 당시의 발표일이어야 하며, 나중에 008을 다시 읽으면 개정된 값이 나온다 |
| III | PASS 유지 | API 계약 §4에 "내보내지 않는 것" 표를 두어 유사도·배지·유지율이 응답에 들어갈 자리를 없앴다 |

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
│   └── macro-indicator-port.md        # 008 경계
├── checklists/requirements.md
└── tasks.md                           # /speckit-tasks 산출물 (이 명령이 만들지 않는다)
```

### Source Code (repository root)

```text
data/
├── src/data_pipeline/regime/          # 사전 계산 — 계산 규칙의 본체
│   ├── scale.py                       # 연속 구간 판정, 중앙값·MAD·IQR, 보정 상수
│   ├── distance.py                    # z, E_i, D_g, 종합 거리, 기여도
│   ├── candidates.py                  # 12개월 초과·±6개월 건너뛰기·동률·상위 10
│   ├── explain.py                     # 비슷한 점·다른 점 템플릿 조립
│   ├── snapshot.py                    # 기준월 단위 조립, 검색 상태 판정
│   ├── load.py                        # PostgreSQL 적재
│   └── fixture/                       # ⚠ [임시] 008 대역 — 008 완성 시 통째 삭제
├── scripts/precompute_regime.py       # 실행 진입점 (argparse)
└── tests/regime/                      # pytest — 계산 규칙 시험

backend/src/main/java/com/ssafy/thorp/regime/
├── presentation/controller|response/  # GET 3개
├── application/service|result|repository|port/
│   └── port/MacroIndicatorPort.java   # 008 경계
├── domain/model|vo/                   # SearchStatus, BaselineOrigin, SnapshotKey
└── infrastructure/
    ├── persistence/                    # snapshot·case 조회
    └── macro/
        ├── MacroIndicatorAdapter.java  # 008 완성 후 작성
        └── fixture/                    # ⚠ [임시] 008 대역 — 008 완성 시 통째 삭제

backend/src/main/resources/
├── fixtures/macro-indicator/           # ⚠ [임시] 픽스처 지표 시계열
└── db/migration/V{YYYYMMDDHHMMSS}__regime_search.sql

frontend/src/
├── pages/RegimeSearchPage.tsx
├── components/regime/                 # 결과 목록, 사례 상세, 상태 표시, 자료 기준 시점
├── hooks/useRegimeSearch.ts
├── api/regime.ts
└── types/regime.ts
```

**Structure Decision**: 계산은 `data/`, 조회는 `backend/regime`, 화면은 `frontend`로 셋으로 나눈다.
루트 `AGENTS.md`의 **"Backend는 계산하지 않는다"** 와 `data/CLAUDE.md`의 적재 방향
(`raw → processed → PostgreSQL`, *"Backend·Worker가 읽는 곳은 PostgreSQL이다"*)이 그대로 맞는다.
Backend 패키지는 ADR-0001의 4계층을 따르고 기존 `market` 도메인은 건드리지 않는다(research R10).

## 구현 순서

묶음마다 끝에 검증 명령이 통과해야 다음으로 간다. 파트가 갈리므로 Jira 티켓도 묶음 단위로 나뉜다.

| 묶음 | 파트 | 내용 | 끝났다고 말할 근거 |
|---|---|---|---|
| **M1** | DE·BE | 포트 계약 + **격리된** 픽스처 대역 | 7가지 모양이 다 들어 있고, 픽스처가 세 디렉터리 밖으로 나가지 않으며, 자료 버전이 `FIXTURE-`로 시작하고, prod 프로파일에서 `fixture`면 기동이 실패한다 |
| **M2** | DE | `scale.py` — 연속 구간, 중앙값·MAD·IQR, 보정 상수 | 구멍 뚫린 계열이 60개월 미만으로 판정된다. 상수 계열이 IQR로 넘어가고 완전 상수는 계산 불가다 |
| **M3** | DE | `distance.py` — z·E_i·D_g·종합·기여도 | 수기 계산과 일치(SC-003). 거리 0이면 기여도 키가 없다 |
| **M4** | DE | `candidates.py` — 후보 제한·정렬·상위 10 | 12개월 이내 제외, ±6개월 건너뛰기, 동률 오래된 월 우선, 부족하면 부족한 대로 |
| **M5** | DE | `snapshot.py`+`load.py` — 상태 판정·적재 | 전 구간 배치가 돌고 같은 입력에 같은 행이 나온다(멱등) |
| **M6** | BE | 마이그레이션 + `regime` 도메인 조회 API 3개 | 계약 예시와 응답이 일치. `NOT_COMPUTABLE`이 200 |
| **M7** | FE | 결과 목록·상태 표시·자료 기준 시점 | 배지·확률 표현 0건, 후보 부족 시 빈자리 없음 |
| **M8** | FE | 사례 상세(거리·기여도·z·척도) + 종합 비교 전환 | 모드 전환 시 한 문장 알림, 기준월 표시 |

**M2·M3·M4를 가장 먼저 두는 이유**: 이 셋이 010의 전부다. 화면과 API는 그 결과를 옮기는 일이고,
틀렸을 때 드러나는 곳도 여기다. 007이 화면부터 만들어 워커 없이 숫자를 주입하게 된 전철을 밟지
않는다.

## 위험과 그에 대한 대비

| 위험 | 대비 |
|---|---|
| 008이 늦어져 실제 자료로 한 번도 못 돌아 본다 | 픽스처로 **계산 규칙**은 전부 검증된다. 검증되지 않는 것은 "사례가 그럴듯한가"이며, 그 한계를 quickstart에 명시하고 화면에 예시 자료임을 표시한다(008 FR-022) |
| 픽스처가 만든 가짜 자료가 008 이후에도 남는다 | 자료 버전 `FIXTURE-` 접두사 + 디렉터리 격리 + prod 기동 가드. 폐기는 조건 한 줄(위 "임시 대역의 격리와 폐기") |
| 008의 실제 과거 범위가 픽스처와 달라 규모가 어긋난다 | 후보 지평을 "자료가 있는 전 구간"으로 정했으므로 코드가 범위에 의존하지 않는다. 규모는 배치 소요 시간으로 실측한다 |
| 부동소수점 때문에 재검색 순위가 흔들린다 | 정렬 전 12자리 반올림(research R4). 회귀 테스트로 같은 입력 2회 실행의 순위 동일을 고정한다 |
| PD-09가 확정되며 상태가 늘어 기존 결과 해석이 바뀐다 | 검색 상태를 **저장 값**으로 두고 조회 시 재판정하지 않는다(research R6). 이전 `NEAREST_ONLY` 기록은 그대로 남는다 |
| 지표명·분야명이 화면 코드에 스며든다 | 007과 같은 정적 검사를 붙인다. 대상 파일은 손 목록이 아니라 훑어서 모은다 |

## 임시 대역의 격리와 폐기

008이 없어 픽스처로 시작한다. **처음부터 한 번에 지워지도록 만든다** — 008이 완성된 날 "어디를
지워야 하지"를 뒤지게 되면 가짜 자료가 운영에 남는다. 007의 `PROVISIONAL-` 등록표가 같은 문제를
버전 문자열로 풀었고, 여기서는 그것을 **자료 버전과 디렉터리 경계** 둘로 푼다.

| | 규칙 |
|---|---|
| **격리** | 지울 것이 세 디렉터리에만 있다 — `regime/infrastructure/macro/fixture/`, `resources/fixtures/macro-indicator/`, `data/src/data_pipeline/regime/fixture/`. 운영 코드는 픽스처 클래스를 이름으로 참조하지 않는다 |
| **전환** | 프로퍼티 한 줄 `thorp.regime.macro.source: fixture \| macro`. **prod 프로파일에서 `fixture`면 기동 실패** — 설정 실수로 가짜 자료가 운영에 나가는 경로를 없앤다 |
| **표기** | 자료 버전이 `FIXTURE-`로 시작 / API `versions.sampleData: true` / 화면에 예시 자료 표시 |
| **폐기** | 디렉터리 3개 삭제 + 프로퍼티 키 삭제 + `DELETE … WHERE data_version LIKE 'FIXTURE-%'` + 전 구간 재계산 |

**가장 오래 남는 것은 코드가 아니라 DB 행이다.** 그래서 표기를 `data_version`에 박는다 — 식별
키·API 응답·기록에 전부 실려 다니므로 값이 어느 경로로 흘러가도 가짜임이 드러나고, 폐기가 조건
한 줄로 끝난다. 절차는 [contracts/macro-indicator-port.md](contracts/macro-indicator-port.md) §3.3.

**픽스처의 일곱 모양은 008이 와도 남긴다.** 실제 자료에 상수 계열이나 정확한 동률이 없을 수 있는데,
그러면 `NOT_COMPUTABLE`·동률 경로가 검증 밖으로 나간다. 운영 대역(위 세 디렉터리)은 폐기하되 같은
모양을 담은 **단위 테스트 픽스처**는 `data/tests/regime/fixtures/`에 남는다.

## Complexity Tracking

> Constitution Check에 정당화가 필요한 위반이 없다. 이 절은 비운다.

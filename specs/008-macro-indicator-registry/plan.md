# Implementation Plan: 경제지표 등록·수집 (Spec 008)

**Branch**: `docs/275-spec008-plan-task` (현재 문서 작업 브랜치; 기능 식별자 `008-macro-indicator-registry`) | **Date**: 2026-09-17 | **Spec**: [spec.md](spec.md)

**Input**: `specs/008-macro-indicator-registry/spec.md`. 계획 산출물은 [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)다. 구현 작업 분할과 Jira 티켓은 후속 `/speckit-tasks` 단계에서 정한다.

## Summary

17개 월별 경제지표를 공식 원천에서 수집·변환하고, 원본·개정 이력을 보존하며, 17개가 모두 처리된 불변 자료 스냅샷만 공개한다. 계산 등록표, 화면 분류, 자료 버전을 분리한다. `T_g`와 `T_all`을 자료 버전에 고정하고, 008의 단일 조회 응답이 009 화면에 필요한 17개 지표·최근 3개월·수집 상태를 한 버전에서 제공한다. 010의 계산용 시계열은 `data/`에서 읽고 Backend에서는 계산하지 않는다. PD-10/PD-11은 미확정으로 유지하며 실제 원천 검증 전에는 구현 완료를 선언하지 않는다.

## Technical Context

**Language/Version**: Python 3.11 (`data/`), Java 21 (Spring Boot 3.5 Backend). 009 화면 소비자는 TypeScript/React 19이나 이 spec의 화면 구현 범위는 아니다.  
**Primary Dependencies**: 기존 `data/`의 requests·pandas·PyArrow·SQLAlchemy/psycopg 계열을 재사용하고 필요한 버전은 구현 시 lockfile/`pyproject.toml`에서 확정한다. Backend는 기존 Spring Web·JPA·Flyway를 사용한다.  
**Storage**: `data/raw/` 원본과 `data/processed/` Parquet, 공개 조회용 PostgreSQL 16 `macro_*` 전용 테이블. 기존 ETF `dataset_version` 및 009 `market_flow_field_summary`는 재사용하지 않는다.  
**Testing**: `ruff check src scripts tests`, `pytest`, `./gradlew spotlessApply build`, PostgreSQL Testcontainers 및 008/009/010 계약 테스트. 테스트가 실행되지 않아 skip되면 통과로 간주하지 않는다.  
**Target Platform**: Linux 컨테이너 기반 배치·Spring 서비스; 개발용 로컬 Docker Compose.  
**Project Type**: 모노레포의 데이터 수집 배치 + 읽기 전용 Backend REST 서비스.  
**Performance Goals**: 17개 계열 전체 발행의 소요 시간, 원천 호출 수, 008 조회 p50/p95를 실측해 작업별 기준을 확정한다. 근거 없는 고정 지연 목표를 선제 지정하지 않는다.  
**Constraints**: KOSIS 분당 200회·요청당 40,000셀, OECD/관세청/ECOS의 실제 조건 준수; 사용자 요청 중 원천 호출 금지; 부분 발행·보간·운영 fixture fallback 금지; Backend 계산 금지; 시크릿 비노출.  
**Scale/Scope**: 고정 17개 지표/7개 분야, 대상월별 전 가용 구간(PD-11 확인 전 길이 미정), 지표별 최근 재조회 `N`(미정), 조회 시 최근 3개월과 지표별 최신값. 18번째 지표와 빈티지 확보는 범위 밖.

## Constitution Check

*GATE: Phase 0 이전 판정, Phase 1 산출물 작성 후 재판정. 기준: `.specify/memory/constitution.md`의 현재 본문. 상단 Sync Impact Report는 3.4.0 승인 완료라고 하나 footer는 초안으로 표기되어 메타데이터가 불일치한다. 이 plan은 현재 본문의 더 엄격한 기준으로 검증하며, 헌장 표기 정합화는 별도 문서 작업으로 남긴다.*

| 원칙/규칙 | 계획상 대응 | 판정 |
|---|---|---|
| I 전략 진단 중심 | 008은 검색·시장 맥락용 입력만 공급, 효과 입증 주장 없음 | 통과 |
| II 단순 화면 | 공급원/인증키 선택을 사용자에게 요구하지 않고 개별 실제 값 유지 | 통과 |
| III 추천 아님 | 지표 수집·노출만 하며 투자 권고/점수화 없음 | 통과 |
| IV 같은 조건 | 검색 소비자가 하나의 불변 자료·계산 등록표 버전을 사용 | 통과 |
| V 당시 정보·역사 탐색 구분 | 최신 개정자료 역사 탐색으로 명시, 빈티지/당시 재현 주장 없음 | 통과 |
| VI 없는 결과 금지 | 결측 null, `T_all` null, 무자료/실패, 이전 완결 버전 유지 | 통과 |
| VII 탐색·검증 구분 | 008은 검색 결과나 효과 검증을 만들지 않음; PD-10/11 미확정 표시 | 통과 |
| VIII 재현성 | raw 개정 이력, 값 규칙 fingerprint, 불변 자료·등록표 버전, 포인터 원자적 발행 | 통과 |
| IX 실측 실행 한도 | 배치·조회 성능 수치 사전 고정 없음; 원천 한도와 실측 근거 기록 | 통과 |
| X 이용 조건 | 지표별 작성기관·출처·조건 확인, 화면 전달, 무검증 항목 완료 금지 | 통과 |
| 스키마 협업 | 구현 시 각 작업 묶음 1 Ticket·Branch·MR, Flyway 변경과 코드·검증 동일 MR | 통과 |
| 현재 Git 브랜치 형식 | 사용자가 지정한 `docs/275-spec008-plan-task`는 헌장의 `{type}({position})/{JIRA_NUMBER}-{description}`과 불일치. 이 plan에서 임의 변경하지 않으며 MR 전 팀 확인 필요 | 보류 |

**Phase 1 재판정**: [data-model.md](data-model.md)의 완결성 검증/원자적 포인터, [contracts/openapi.yaml](contracts/openapi.yaml)의 무자료·출처·버전, [contracts/macro-indicator-source.md](contracts/macro-indicator-source.md)의 010 경계를 대조했다. 설계 원칙 위반은 없으나 기존 브랜치 명명 게이트는 보류 상태다. PD-10/PD-11은 구현 완료 게이트이지 임의로 채울 계획 빈칸이 아니다.

## Architecture and Data Flow

```text
ECOS / KOSIS / 관세청 / OECD
       │ (인증·한도·원본 개정 보존)
       ▼
data/ raw → 월별 정규화·변환 → processed/ Parquet ──▶ macro_* PostgreSQL
                                                       (새 완결 스냅샷 + 기준월,
                                                        원자적 current 포인터)
                                  ├──▶ 008 Backend 조회 REST ──▶ 009의 기존 두 화면 API ──▶ 화면
                                  └──▶ 010 data/ MacroIndicatorSource ──▶ 검색 사전계산
```

- 수집 실패는 원본·시도 상태를 남기고 현재 포인터를 움직이지 않는다. raw→Parquet→DB 한 방향으로만 적재한다. 값 규칙 변경은 전 구간 재처리, 매핑·가중만 변경되면 계산 등록표/기준월 재산출, 화면 문구만 변경되면 표시 버전만 올린다. 정기 `N`개월 밖 개정은 원천 공지·메타데이터 감지 시 해당 범위를 재조회한다.
- 008 사용자 조회는 17개 메타데이터·값·3개월 경로·7개 `T_g`·`T_all`·수집 상태·세 버전을 단일 응답으로 고정한다. 009는 `macro_*`를 직접 읽지 않는다. 현재 plan의 기본 구현안은 009→008 REST 호출이다. 동일 프로세스 호출 방식 변경 시 사용자 합의와 계약을 먼저 갱신한다.
- 010의 검색 가능 기준월은 검색 배치가 완료한 결과에서 판정한다. 008의 최신 `T_g`/`T_all`을 검색 진입 가능월로 간주하지 않는다. 009·010 문서/계약의 기존 충돌은 연동 작업에서 별도 정합화한다.

## Delivery Slices (tasks.md 작성 전 설계 경계)

1. **실자료 검증·등록표**: PD-11 검증표와 17개 주 원천/항목/단위/이용 조건/`N` 확정. 미확정 계열을 실제 수집 성공으로 표시하지 않는다.
2. **수집·정규화·개정**: 원천 어댑터, raw/Parquet, 원값/변환값과 다중 원본 계보, 발표일/대상월/수신시각, 개편·결측 규칙, 호출 한도/실패 복구, `N` 밖 개정 감지·재조회.
3. **저장·발행**: 버전 분리, 전 구간 불변 스냅샷, 17개 유효 변환값 게이트, `T_g`/`T_all`, 파일 manifest 봉인과 원자적 DB 포인터, 장애 복구, Flyway·통합 테스트.
4. **008 조회**: 단일 스냅샷 REST, 무자료/실패 응답, 출처·작성기관·예시 표시, 버전 일관성/계약 테스트.
5. **009·010 연동**: 009 직접 저장소/fixture 경계 교체와 두 화면 API 유지, 010 Python source 및 Backend 등록표 포트 연결, 검색 완료월 구분, 계약 충돌 정정.

각 slice는 작업 종속 관계를 보여줄 뿐 Jira Task/브랜치 수를 확정하지 않는다. `/speckit-tasks`에서 독립 검증 가능한 작업 묶음으로 분할한다.

## Project Structure

### Documentation (this feature)

```text
specs/008-macro-indicator-registry/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── openapi.yaml
│   └── macro-indicator-source.md
└── tasks.md                 # /speckit-tasks에서만 생성
```

### Source Code (planned, no implementation in this turn)

```text
data/
├── src/data_pipeline/macro/      # 원천 어댑터·정규화·변환·발행·010 source
├── scripts/                      # 명시적 초기/정기 수집 실행점
└── tests/                        # 원천 fixture·변환·발행·010 계약
backend/
├── src/main/java/com/ssafy/thorp/macro/  # 008 controller/application/domain/infrastructure
├── src/main/resources/db/migration/      # macro_* Flyway migration
└── src/test/                           # REST/DB/아키텍처 계약
backend/src/main/java/com/ssafy/thorp/marketflow/  # 009의 소비 adapter 정합화
```

**Structure Decision**: 기존 `data/`·Backend 패키지와 파트 규칙을 따른다. 신규 서비스나 브로커를 추가하지 않는다. 스키마 마이그레이션과 이를 이용하는 Python/Java 코드 및 검증을 동일 변경 묶음에 포함한다.

## Integration Risks and Exit Criteria

- **PD-11**: 17개 모두의 실제 응답·이용 조건·과거 범위 확인 전 Spec 008 완료 불가. OECD G20 CLI 후보 SDMX 키와 ECOS 항목 코드는 실조회 후 확정한다.
- **PD-10**: 최소 유효 척도를 008이 추정하지 않는다. 확정 전 010의 MAD→IQR→계산 불가 규칙을 유지한다.
- **009 계약 충돌**: 009 spec의 최신 `T_g` 검색 진입, OpenAPI의 무자료 non-null 기준월·상세 경로 길이가 008 계약과 다르다. 009 예시의 `dubai_crude`와 Backend fixture의 `dubai_oil` ID도 불일치한다. 기존 4회 분리 조회 포트는 단일 스냅샷 조회로 교체한다. 저장된 변화 서술의 키는 자료·계산·표시·서술 규칙 버전을 포함하고, 수집 상태/마지막 시도는 008 현재 응답으로 덧씌운다. 008 REST 응답 봉투는 009 및 신규 Backend API의 `HttpApiResponse`와 일치시킨다. 연동 전 canonical ID 확정·수정·공동 계약 테스트가 필요하다.
- **010 계약 충돌**: 기존 010 포트의 자료/등록표 버전과 008의 표시 버전, 분야 ID 표기, `changed_since(previous,target)` 정의를 고정한다. `T_all=null`이면 010의 `baseline_month NOT NULL` 테이블에 종합 완료월 행을 만들지 않고, baseline-months API는 `composite:null`, 종합 검색은 `NOT_COMPUTABLE`로 명시한다. 010 계산은 오직 `data/`에 둔다.
- **운영 대역**: 009 `ExampleMacroIndicatorSnapshotAdapter`와 010 fixture 결과는 운영에서 선택되지 않도록 검증한다.
- **완료 판정**: [quickstart.md](quickstart.md)의 실패·무자료·개정·동시 조회·`T_all=null`·실자료 검증 시나리오를 통과하고 출처/작성기관을 전 화면으로 전달한다. 계획 문서 작성 자체는 구현/실자료 검증 완료가 아니다.

## Complexity Tracking

설계 복잡성을 요구하는 Constitution 위반은 없다. 별도 `macro_*` 모델은 기존 ETF 버전 모델과 자료 종류·원자적 완결성 조건이 달라 필요한 분리이며, 신규 인프라 도입은 아니다. 현재 브랜치 이름의 헌장 형식 불일치는 위 게이트에 기록했고 문서 작성 과정에서 브랜치를 임의로 변경하지 않는다.

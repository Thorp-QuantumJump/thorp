# Macro Data & Consumer Contract Checklist: 경제지표 등록·수집

**Purpose**: Spec 008의 원천·월별 자료·버전 발행과 009/010 소비 경계에 관한 요구사항의 완전성·명료성·일관성을 검토한다.
**Created**: 2026-09-17
**Feature**: [spec.md](../spec.md) · [plan.md](../plan.md)

**Note**: 이 점검표는 구현 동작 시험이 아니라 요구사항 문장의 품질을 검토한다.
**Review Ownership**: 검토자가 요구사항 품질을 판단해 항목을 확인한다. 새 항목은 모두 미체크 상태다.
**Marker Semantics**: `[x]`는 요구사항 품질이 검토·충족되었다는 뜻이며 구현 완료를 뜻하지 않는다.

## Requirement Completeness

- [x] CHK001 17개 지표 각각의 분야·변환·주 원천을 빠짐없이 특정하고, 같은 지표의 공급원 중복 처리 기준을 정의했는가? [Completeness, Spec §FR-001, §FR-005, §FR-006]
- [x] CHK002 등록표의 필수 속성, 미확인 값의 표기, 실자료 완료 전 확정할 항목을 서로 구별해 문서화했는가? [Completeness, Spec §FR-002, §SC-001, §PD-11]
- [x] CHK003 일별 원천을 월 기말·월 평균으로 바꾸거나 전년동월 대비 값을 만드는 데 필요한 원값·비교월·발표 근거의 보존 범위를 명시했는가? [Completeness, Spec §FR-001, §FR-006, §FR-007]
- [ ] CHK004 지표별 원천 권리·작성기관·출처 표기·재배포 제한의 확인 항목과 미확인 시 제공 범위를 정의했는가? [Completeness, Spec §FR-016, §FR-018, §FR-019, §PD-11]
- [x] CHK005 008이 009와 010에 제공해야 할 값·메타데이터·버전·수집 상태의 범위를 각각 빠짐없이 정했는가? [Completeness, Spec §FR-013, §FR-014, §FR-023]

## Requirement Clarity

- [ ] CHK006 '유효한 월별 변환값'의 판정에 원값만 존재하는 월, 결측, 분모 부재·0, 개편 단절이 어떻게 포함 또는 제외되는지 명확한가? [Clarity, Spec §FR-006, §FR-008, §FR-010, §FR-011]
- [x] CHK007 대상월·기관 발표일·수신 시각·개정 기준의 서로 다른 의미와 원천이 날짜·개정 식별자를 제공하지 않을 때의 표기가 분명한가? [Clarity, Spec §FR-007, §FR-012]
- [x] CHK008 지표별 재조회 구간 `N`의 결정 근거와 `N` 밖 개정 발견 시 필요한 재조회 범위가 구분되어 있는가? [Clarity, Spec §FR-002, §FR-017]
- [x] CHK009 원천·통계 항목·단위·변환·연결·결측 규칙의 변경과 매핑·가중·화면 문구 변경이 각각 어떤 버전을 바꾸는지 모호함 없이 분리되어 있는가? [Clarity, Spec §FR-003, §SC-005]
- [x] CHK010 `T_g`, `T_all`, 지표별 최신월 및 검색 계산 완료월의 정의·null 의미가 서로 구별되어 있는가? [Clarity, Spec §FR-013, §FR-023]

## Requirement Consistency

- [x] CHK011 17개 전부의 유효값이 필요한 발행 조건과 공통월 `T_all`이 없어도 발행한다는 조건이 같은 용어·판정 순서로 서술되어 있는가? [Consistency, Spec §FR-011, §FR-013, §SC-008]
- [x] CHK012 계산 등록표·표시 분류·자료 버전을 독립 변경한다는 요구와 동일 버전 재조회 시 재현성이 유지된다는 기준이 양립하도록 서술되어 있는가? [Consistency, Spec §FR-003, §FR-009, §SC-004, §SC-005]
- [x] CHK013 첫 완결 버전이 없는 상태의 17개 등록 정보 제공 요구와 값·기준월·자료 버전 부재 요구가 서로 모순되지 않는가? [Consistency, Spec §FR-011, §FR-023, §SC-008]
- [ ] CHK014 008의 최신 자료월 표시와 010의 검색 완료월만 검색 진입에 쓴다는 원칙이 009의 관련 요구·계약에도 동일하게 반영되도록 변경 범위가 정의되어 있는가? [Conflict, Spec §FR-013, Plan §Integration Risks and Exit Criteria]
- [ ] CHK015 무자료 또는 `T_g=null`일 때 최근 경로·기준월·지연 원인 지표의 의미가 008 제공 정보와 009 화면 요구에서 일관되는가? [Consistency, Spec §FR-023, Plan §Integration Risks and Exit Criteria]
- [x] CHK016 010의 종합 공통월 부재·검색 계산 불가와 분야별 검색 유지 요구가 008의 null 기준월 정의와 충돌 없이 서술되어 있는가? [Conflict, Spec §FR-013, Plan §Integration Risks and Exit Criteria]

## Acceptance Criteria Quality

- [x] CHK017 17개 지표·7개 분야·필수 등록 항목의 누락 여부를 객관적으로 판정할 기준이 명시되어 있는가? [Measurability, Spec §SC-001, §SC-008]
- [ ] CHK018 원값에서 변환값을 독립 대조할 표본 대상월·변환 유형·허용 오차를 정할 책임과 시점이 명시되어 있는가? [Measurability, Spec §SC-007, §PD-11]
- [x] CHK019 자료 버전과 등록표 버전이 같을 때 무엇이 동일해야 하는지(관측값·기준월·표시 정보·수집 상태)를 구분해 측정 가능하게 적었는가? [Measurability, Spec §SC-004, §FR-003, §FR-011]
- [x] CHK020 '완결 자료 버전'의 17개 처리 완료·유효값 조건과 발행 보류 사유를 개별적으로 판정할 수 있는가? [Measurability, Spec §FR-011, §SC-008]
- [ ] CHK021 PD-10·PD-11이 미확정일 때의 허용된 중간 상태와 각 결정이 완료될 때의 증거·판정 책임이 명시되어 있는가? [Acceptance Criteria, Spec §FR-015, §FR-022, §PD-10, §PD-11]

## Scenario & Edge Case Coverage

- [x] CHK022 최초 전 구간 확보, 정상 증분 재조회, 과거 개정, 기준연도 개편의 서로 다른 요구 흐름을 모두 다루는가? [Coverage, Spec §FR-009, §FR-010, §FR-017]
- [x] CHK023 일부 원천 성공·인증 실패·호출 한도 도달·전체 변환값 0건에 대해 원본 보존과 공개 버전 유지/보류 기준이 정의되어 있는가? [Coverage, Spec §FR-011, §FR-022]
- [x] CHK024 각 지표에는 유효값이 있으나 한 분야 또는 전체의 공통 대상월이 없는 경우의 제공 정보와 검색 가능 범위를 다루는가? [Edge Case, Spec §FR-013, §FR-023]
- [x] CHK025 지표의 최신월이 분야 `T_g`보다 뒤선 경우와 최근 세 달 중 한 달이 결측인 경우를 구분해 표시 정보 요구를 정했는가? [Edge Case, Spec §FR-008, §FR-014, §FR-023]
- [ ] CHK026 발행 도중 중단되거나 중복 수집이 겹치는 상황에서 어느 완결 버전을 소비자에게 유지할지 요구사항으로 명시했는가? [Recovery, Gap, Spec §FR-009, §FR-011]
- [x] CHK027 개정 자료를 이용한 역사 탐색과 당시 발표자료 재현의 범위·표시 문구·금지된 해석이 예외 없이 구별되어 있는가? [Coverage, Spec §FR-012, §Assumptions]

## Non-Functional Requirements

- [x] CHK028 사용자 조회 시 원천을 호출하지 않는 조건과 원천별 호출 한도·지연 시 제공 상태가 요구사항에 명시되어 있는가? [Non-Functional, Spec §FR-011, §FR-017]
- [x] CHK029 인증키의 사용자 비노출, 개발 예시 자료의 명시적 사용, 운영 자동 대체 금지가 한 흐름으로 정의되어 있는가? [Security, Spec §FR-021, §FR-022]
- [x] CHK030 수집·발행·조회 성능의 측정 대상과 수치 상한을 실측 후 정한다는 결정이 서로 구분되어 있는가? [Performance, Plan §Technical Context, Constitution §IX]
- [x] CHK031 원본·개정 이력·과거 공개 버전의 보존 필요성과 보관/삭제 정책의 미확정 범위가 명시되어 있는가? [Non-Functional, Spec §FR-009, Plan §Architecture and Data Flow]

## Dependencies & Assumptions

- [x] CHK032 실제 원천의 항목 코드·과거 범위·이용 조건을 확인하기 전 예시 자료를 실제 자료로 오인하지 않도록 하는 완료 경계가 분명한가? [Dependency, Spec §FR-022, §PD-11]
- [x] CHK033 관세청 무역수지의 공식 제공값 여부와 OECD G20 계열·권리 조건처럼 실조회가 필요한 결정이 미확정 상태로 추적되는가? [Assumption, Spec §FR-006, §FR-019, §PD-11]
- [x] CHK034 기존 시장 카드용 11개 지표와 008의 월별 17개 지표, 010의 검색 계산 책임 사이의 범위 경계가 충분히 명시되어 있는가? [Dependency, Spec §Out of Scope, §FR-023]

## 미충족 항목 메모 (2026-09-17 검토)

34개 중 27개는 spec·plan·data-model에 근거가 있어 확인했다. 아래 7개는 근거를 찾지 못해
미체크로 둔다. 옆에 고칠 문서와 판단 근거를 적는다.

| 항목 | 판단 근거 | 고칠 문서 |
|---|---|---|
| CHK004 | FR-022는 인증키 미발급과 자료 이용 불가만 다룬다. 이용 권리가 미확인인 계열을 어디까지 제공할지(화면 표시 보류인지 수집만 보류인지) 정한 문장이 없다 | spec.md FR-016 또는 FR-022 |
| CHK006 | 원값만 있는 월·결측·개편 단절은 유효 변환값에서 제외한다고 적혔으나, 전년동월 대비의 분모가 되는 전년 값이 없거나 0인 월의 처리가 어디에도 없다. 0으로 나누면 값이 생기거나 무한대가 된다 | spec.md FR-006 |
| CHK014 | plan의 연동 위험 목록이 009와의 충돌을 구체적으로 나열하지만, 어느 spec이 무엇을 고치는지 소유 경계가 정해지지 않았다. 008 문서만으로는 009 쪽 변경이 일어나지 않는다 | plan.md 연동 항목, 009 spec |
| CHK015 | T_g가 없을 때의 지표별 경로 제공과 지연 원인 지표는 data-model에만 있고 spec 요구사항에 없다. 009 화면 요구와 같은 뜻인지 009 문서를 대조하지 않았다 | spec.md FR-023, 009 spec |
| CHK018 | SC-007이 표본 대상월을 '팀이 정한다'로 두고, 누가 언제 정하는지와 허용 오차가 없다. 정하는 사람이 없으면 검증이 미뤄진다 | spec.md SC-007 |
| CHK021 | PD-10·PD-11의 중간 상태는 적혀 있으나 무엇을 증거로 보고 누가 완료를 판정하는지가 없다 | spec.md PD 표 |
| CHK026 | 발행 도중 중단과 동시 수집 충돌 처리는 data-model 상태 전이에만 있다. 설계 문서는 요구사항이 아니므로 어느 동작이 약속인지 spec에서 읽을 수 없다 | spec.md FR-009 또는 FR-011 |

## Notes

- 항목을 `[x]`로 바꾸는 것은 검토자의 요구사항 품질 승인이다. 구현·테스트 완료 표시가 아니다.
- 충족하지 않은 항목은 수정할 문서와 판단 근거를 옆에 적고 미체크로 둔다.
- `$speckit-implement`는 체크 상태를 읽지만 이 표식을 수정하지 않는다.
- `checklists/requirements.md`는 `$speckit-specify`·`$speckit-clarify`가 관리하는 별도 기본 점검표다.

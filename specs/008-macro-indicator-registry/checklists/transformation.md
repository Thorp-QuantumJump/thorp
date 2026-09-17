# Transformation & Missing-Value Rules Checklist: 경제지표 등록·수집

**Purpose**: Spec 008의 원값→월별 변환 규칙과 결측·개편 처리에 관한 요구사항의 완전성·명료성·일관성을 검토한다.
**Created**: 2026-09-17
**Feature**: [spec.md](../spec.md) · [plan.md](../plan.md) · [research.md](../research.md) · [data-model.md](../data-model.md)

**Note**: 이 점검표는 변환 결과가 맞는지 시험하지 않는다. 변환 규칙을 적은 문장이 빠짐없고 한 가지로 읽히는지 검토한다.
**Review Ownership**: 검토자가 요구사항 품질을 판단해 항목을 확인한다. 새 항목은 모두 미체크 상태다.
**Marker Semantics**: `[x]`는 요구사항 품질이 검토·충족되었다는 뜻이며 구현이나 실자료 검증 완료를 뜻하지 않는다.

## Requirement Completeness

- [x] CHK001 17개 지표마다 적용할 변환이 FR-001 표의 유형 중 정확히 하나로 특정되고, 등록표에 지표별로 기록하도록 요구하는가? [Completeness, Spec §FR-001, §FR-002]
- [x] CHK002 일별 원천을 월값으로 만들 때 '월 기말'이 마지막 영업일인지 마지막 달력일인지, 그 달의 거래일이 없으면 어떻게 하는지 정의되어 있는가? [Gap, Spec §FR-001, §Assumptions]
- [x] CHK003 전년동월 대비 증가율의 계산식과 비교 대상월 선정 규칙(t-12 고정 여부, 개편 전후를 넘는 비교 허용 여부)이 명시되어 있는가? [Gap, Spec §FR-001, §FR-006, §FR-010]
- [x] CHK004 실업률의 계절조정 비율이 원천 제공값인지 자체 조정값인지 구별해 기록하도록 요구하는가? [Gap, Spec §FR-001, §FR-002]
- [x] CHK005 무역수지가 원천 공식값인지 수출에서 수입을 뺀 파생값인지에 따라 변환 기록과 검증 근거를 달리 남기도록 요구하는가? [Completeness, Spec §FR-006, Research §R1]

## Requirement Clarity

- [x] CHK006 전년동월 값이 결측이거나 0인 달의 변환값을 어떻게 처리하는지 정의되어 있는가? [Gap, Spec §FR-006, §FR-008]
- [x] CHK007 '유효한 월별 변환값'과 '원값만 있는 월'의 판정 경계가 한 가지로만 읽히는가? [Clarity, Spec §FR-006, §FR-011]
- [x] CHK008 결측 사유(원천 미발표, 수집 실패, 변환 불가, 개편 구간 제외)를 서로 구분해 기록하도록 요구하는가? [Clarity, Spec §FR-008, Data Model §1]
- [x] CHK009 공표 정밀도에 따른 반올림을 저장값과 표시값에 각각 어떻게 적용하는지 구분되어 있는가? [Clarity, Spec §FR-015, Data Model §1]
- [x] CHK010 단위 환산(지수 기준연도 변경, 금액 단위 통일)이 변환 규칙에 포함되는지 별개 항목인지 분명한가? [Ambiguity, Spec §FR-001, §FR-002]

## Requirement Consistency

- [x] CHK011 변환 규칙 변경이 새 계산 등록표 버전과 전 구간 재처리를 함께 요구한다는 서술이 여러 문서에서 같은 뜻으로 쓰였는가? [Consistency, Spec §FR-003, §FR-017, Research §R3]
- [x] CHK012 개편 연결 규칙으로 이은 구간과 규칙이 없어 제외한 구간이 유효 변환값 집합과 기준월 산출에서 같은 기준으로 다뤄지는가? [Consistency, Spec §FR-010, §FR-013]
- [x] CHK013 보간 금지 요구가 변환 단계 안(월 평균을 낼 때 빠진 날을 어떻게 둘지)에도 같게 적용되는지 분명한가? [Conflict, Spec §FR-008, §FR-001]
- [x] CHK014 최근 3개월 경로에서 결측을 그대로 보이라는 요구와 기준월 산출에서 결측월을 빼라는 요구가 서로 모순되지 않는가? [Consistency, Spec §FR-013, §FR-014]

## Acceptance Criteria Quality

- [x] CHK015 원값 대조 검증의 표본 대상월·변환 유형·허용 오차를 누가 언제 정하는지 명시되어 있는가? [Measurability, Spec §SC-007]
- [x] CHK016 변환값을 원값에서 다시 만들 수 있는지 판정할 계보 보존 범위가 측정 가능하게 적혔는가? [Measurability, Spec §FR-006, Data Model §2]
- [x] CHK017 '보간된 값으로 채운 사례 0건'을 어떤 기록으로 판정하는지 정의되어 있는가? [Measurability, Spec §SC-003]

## Edge Case Coverage

- [x] CHK018 전년 대비 변환에 필요한 과거 구간이 없는 계열 초기 12개월의 취급이 정해져 있는가? [Edge Case, Gap, Spec §FR-006]
- [x] CHK019 한 달의 일별 표본이 일부만 확보된 경우 월값을 산출할지 결측으로 둘지의 최소 표본 조건이 있는가? [Edge Case, Gap, Spec §FR-001, §FR-008]
- [x] CHK020 원천 개정으로 원값이 바뀌어 변환값의 유효 여부나 부호가 달라진 경우 이전 변환값을 어떻게 남길지 요구하는가? [Edge Case, Spec §FR-009]

## 미충족 항목 메모 (2026-09-17 검토)

20개 중 16개는 spec·plan·data-model·계약에 근거가 있어 확인했다. 남은 4개는 미체크로 둔다.

| 항목 | 판단 근거 | 고칠 문서 |
|---|---|---|


## Notes

- 항목을 `[x]`로 바꾸는 것은 검토자의 요구사항 품질 승인이다. 구현·테스트 완료 표시가 아니다.
- 충족하지 않은 항목은 고칠 문서와 판단 근거를 옆에 적고 미체크로 둔다.
- `$speckit-implement`는 체크 상태를 읽지만 이 표식을 수정하지 않는다.
- `checklists/requirements.md`는 `$speckit-specify`·`$speckit-clarify`가 관리하는 별도 기본 점검표다.
- 자료·버전·소비 계약 전반은 [macro-data.md](macro-data.md), 원천·이용 조건은 [source-compliance.md](source-compliance.md)가 다룬다. 이 목록은 그 둘을 대체하지 않는다.

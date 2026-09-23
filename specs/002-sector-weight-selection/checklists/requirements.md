# Specification Quality Checklist: 섹터·비중 선택

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-10
**Last validated**: 2026-09-15 (SC-004의 합격선을 걷어낸 뒤 4차 판정. 열여섯 항목 모두 통과)
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) (본문의 엔드포인트 경로·HTTP 상태·오류 코드를 화면 용어로 바꿨다: spec.md의 FR-008·FR-017·SC-008·Out of Scope. 남은 인용은 spec.md의 Clarifications뿐이고 이는 고칠 수 없는 세션 기록이다. 어느 계약의 어느 상태인지는 [plan.md](../plan.md)의 'spec 본문의 화면 용어와 계약 식별자의 대응' 표가 받는다)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders (본문에서 계약 식별자를 걷어낸 뒤에는 "섹터 목록을 아직 받을 수 없는 상태이면"처럼 화면에서 보이는 말로만 읽힌다)
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain (2026-09-15 clarify 세션에서 9건을 해소했다)
- [x] Requirements are testable and unambiguous (spec.md의 FR-013을 002가 관찰할 수 있는 범위로 좁혔다. 002의 몫은 사용자 입력 원본과 대응표 버전을 가공 없이 다음 단계에 남기는 데까지이고, 분석 요청 구성은 003, 기록 보존은 [006](../../006-my-records/spec.md) FR-020의 몫임을 밝혔다. [contracts/selection-handoff.md](../contracts/selection-handoff.md)의 '004 분석 요청과의 관계'가 밝힌 소유 구분과 어긋나지 않고, SC-013이 판정 기준을 준다)
- [x] Success criteria are measurable (SC-004는 합격 비율을 두지 않고 관찰 결과를 기록하는 것과 두 명 이상이 같은 단계에서 막히면 화면을 고치는 것까지만 요구한다. 무엇을 '막힘'으로 세는지는 판정 기준 세 가지로 정해져 있어 지금 상태로 측정할 수 있다. 이는 003 SC-006·005 SC-002·006 SC-006·007 SC-008·009 SC-006·010 SC-008·011 SC-008·013 SC-005가 쓰는 것과 같은 형태다. 나머지 열두 개도 측정 가능하다)
- [x] Success criteria are technology-agnostic (no implementation details) (SC-008의 오류 코드를 "대응표 미발행으로 섹터 목록을 아직 받을 수 없는 경우"로 바꿨다. 새로 넣은 SC-012·SC-013도 화면에서 관찰되는 사실만 센다)
- [x] All acceptance scenarios are defined (FR-010은 User Story 1의 시나리오 4, FR-014는 같은 스토리의 시나리오 5, FR-016은 User Story 2의 시나리오 5, FR-017은 User Story 4의 시나리오 5로 받는다)
- [x] Edge cases are identified (spec.md의 Edge Cases가 열세 항목이 되었다. 표시 정밀도를 넘는 소수 입력, 100% 초과·음수·숫자가 아닌 값·빈 칸, 브라우저 저장소를 쓸 수 없는 환경, 유지해 둔 값을 읽을 수 없는 경우, 대응표 미발행이 아닌 조회 실패, 대표 상품 정보를 아직 받을 수 없는 경우, '동일하게 나누기' 뒤의 섹터 증감을 모두 넣었고 plan의 research.md 결정 1·3·6·10과 data-model.md의 입력 파싱 표에 어긋나지 않는다)
- [x] Scope is clearly bounded (spec.md의 Out of Scope가 소유 spec과 '앞으로 할 일'을 구분해 적었다)
- [x] Dependencies and assumptions identified (spec.md의 Pre-Implementation Decisions가 PD-01 의존을 적고, SC-004의 사용성 관찰이 언제 누구를 대상으로 이루어지는지는 PD-14가 정하되 이 spec의 성공 기준 자체는 그 확정을 기다리지 않는다는 사실을 밝힌다)

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria (FR-010은 User Story 1 시나리오 4와 SC-012, FR-013은 SC-013이 받는다. 열일곱 개 모두 Acceptance Scenario나 Success Criteria 가운데 하나 이상으로 받쳐졌다)
- [x] User scenarios cover primary flows (첫 화면 진입·섹터 선택·비중 수정·다음 단계 이동이 User Story 1·2·4에 모두 있다)
- [x] Feature meets measurable outcomes defined in Success Criteria (양방향 추적의 구멍을 메웠다. 요구사항 쪽은 SC-012·SC-013이 FR-010·FR-013을 받고, 성공 기준 쪽은 SC-004가 관찰 대상을 "User Story 1·2의 흐름"으로 밝혀 어느 요구사항 묶음을 재는지가 드러난다)
- [x] No implementation details leak into specification (위 Content Quality 첫 항목과 같은 근거다. 본문은 화면 용어로 쓰고 식별자는 [plan.md](../plan.md)와 [contracts/](../contracts/)에만 둔다)

## Notes

- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`
- 2026-09-10 검증: 최소 선택 섹터 수(2개)와 백분율 입력은 기획서에 없는 값이라 Assumptions에 기록했다. 표시 정밀도는 plan으로 넘겼다.
- 첫 화면 메뉴 구조(FR-001)는 별도 spec으로 나누면 Task 크기에 가까워 이 spec에 포함했다.
- **2026-09-15 재판정**: 위 2026-09-10 판정은 기능 요구사항 13개·성공 기준 5개 시절의 것이다. clarify 두 차례로 FR-014~FR-017이 생기고 FR-001·FR-005·FR-007·FR-008·FR-012·FR-013이 개정되었으며 성공 기준이 열한 개가 되어 전 항목을 다시 판정했다. 열여섯 항목 가운데 여섯이 통과, 열이 미통과다.
- **2026-09-15 재판정 보충**: 위 2026-09-10 메모의 "표시 정밀도는 plan으로 넘겼다"는 그 뒤 해소되었다. plan.md의 Technical Context Constraints와 research.md 결정 1이 표시 정밀도와 합계 판정 정밀도를 모두 소수 첫째 자리로 정했고, 화면 안에서는 0.1%p 단위 정수로 다룬다. spec.md의 Edge Cases와 Assumptions에 있는 "정밀도는 plan에서 정한다"는 문장은 그대로 유효하다.
- **2026-09-15 3차 판정(미통과 열 항목 수정 뒤)**: 앞의 재판정이 남긴 열 항목을 고치고 다시 판정했다. 열여섯 항목 가운데 **열다섯이 통과, 하나가 미통과**다. 고친 내용은 다음과 같다. FR-013을 002가 관찰할 수 있는 범위(원본을 가공 없이 넘기는 데까지)로 좁히고 [plan.md](../plan.md)의 묶음 M4 근거 요구사항을 맞췄다. FR-010·FR-014·FR-016·FR-017에 Acceptance Scenario를 붙였다. Edge Cases를 여섯 항목에서 열세 항목으로 늘려 plan이 새로 정한 사용자 노출 동작(정밀도 초과 입력 거부, 상한·음수 입력 거부, 저장소 사용 불가, 저장 값 복원 실패, 대응표 미발행이 아닌 조회 실패, 대표 상품 정보 미수신, '동일하게 나누기' 뒤의 섹터 증감)에 spec 근거를 만들었다. 본문의 엔드포인트·HTTP 상태·오류 코드를 화면 용어로 바꾸고 식별자 대응표를 plan.md에 두었다. SC-008을 기술 중립으로 고치고 SC-004에 '막힘' 판정 기준을 넣었으며 SC-012·SC-013을 더했다. Pre-Implementation Decisions에 PD-14 의존을 적었다.
- **2026-09-15 4차 판정(SC-004 합격선 제거 뒤)**: 3차 판정이 남긴 미통과 하나를 해소해 **열여섯 항목이 모두 통과**한다. 3차 판정에서 SC-004에 '막힘' 판정 기준을 넣을 때 원래 없던 "합격으로 볼 완료 비율"을 함께 요구하게 되어, 팀 결정 항목 PD-14가 확정되기 전에는 영영 통과할 수 없는 기준이 되었다. 다른 여덟 spec의 사용성 성공 기준을 대조해 보니 어느 것도 합격 비율을 두지 않고 "기록하고, 두 명 이상이 막히면 고친다"까지만 요구한다. SC-004를 같은 형태로 되돌리고 Pre-Implementation Decisions의 PD-14 의존도 그에 맞게 고쳤다. 관찰의 참여자·과제·일정은 여전히 PD-14가 정하지만, 이 spec의 성공 기준은 그 확정을 기다리지 않는다.
- **2026-09-15 인용 방식 변경**: spec.md에 줄이 늘면서 다른 문서가 적어 둔 `spec.md:{줄번호}`가 모두 밀렸다. 틀린 줄 번호를 새 줄 번호로 고치면 다음에 spec이 바뀔 때 같은 일이 되풀이되므로, 가리키는 방식 자체를 요구사항 번호와 절 이름으로 바꿨다. research.md·data-model.md·plan.md와 이 문서에서 서른두 건을 고쳤고 002 폴더에 줄 번호 인용은 남지 않았다. **줄 번호 인용은 한쪽만 바뀌어도 아무 경고 없이 틀린 문서가 되므로 앞으로도 쓰지 않는다.**
- 미통과 항목의 근거는 각 항목 괄호 안에 있다. 이 문서는 spec을 고치지 않는다. 고칠 자리는 spec.md이며 소유자가 판단한다.
- 화면 요구사항의 품질은 [ux.md](./ux.md), 다른 spec과의 경계는 [boundaries.md](./boundaries.md)가 따로 본다. 이 문서는 spec 자체의 완결성만 본다.

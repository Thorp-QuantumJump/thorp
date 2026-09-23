# UX Requirements Quality Checklist: 섹터·비중 선택

**Purpose**: 사용자가 직접 조작하는 첫 화면의 요구사항이 작업 묶음으로 쪼개지기 전에 완전하고 명확하며 서로 어긋나지 않고 측정 가능하게 쓰였는지 검토한다. 화면이 실제로 동작하는지 확인하는 절차는 [quickstart.md](../quickstart.md)가 따로 갖고 있다.
**Created**: 2026-09-15
**Feature**: [spec.md](../spec.md)

**Note**: 이 체크리스트는 `/speckit-checklist` 명령으로 만든 요구사항 품질 검토 문서이며, 구현 동작을 시험하는 테스트 목록이 아니다.
**Review Ownership**: 이 체크리스트는 리뷰어가 소유한다. 요구사항 품질 기준이 충족되었다고 리뷰어가 판단한 항목만 `[x]`로 표시한다.
**Marker Semantics**: `[x]`는 요구사항 품질 검토가 끝났다는 뜻이며 구현 완료를 뜻하지 않는다.

## Requirement Completeness

- [ ] CHK001 이 화면이 보여야 할 안내 여섯 가지가 각각 나타나는 조건과 사라지는 조건까지 요구사항 수준에서 정의되어 있는가? [Completeness, Spec §FR-005·§FR-007~FR-009·§FR-015~FR-016, Research 결정 7]
- [ ] CHK002 비중 입력 칸이 받아들이는 값의 범위(하한·개별 상한·소수 자릿수)가 요구사항에 있는가, 아니면 plan에만 있는가? [Gap, Spec §FR-006, Data Model "사용자 입력 파싱"]
- [ ] CHK003 섹터를 두 개 이상 골라야 한다는 제약을 사용자가 어느 시점에 어떤 형태로 알게 되는지 정의되어 있는가? [Completeness, Spec §FR-004, §Edge Cases]
- [ ] CHK004 다음 단계로 넘어갈 수 없는 상태가 버튼 비활성인지 눌렀을 때 막히는 것인지 요구사항이 가르는가? [Gap, Spec §FR-007, §User Story 2]
- [ ] CHK005 대표 상품 정보 패널을 여는 방법·닫는 방법·자료를 받지 못했을 때의 표시가 요구사항에 정의되어 있는가? [Completeness, Spec §FR-010, Data Model "대표 상품 정보"]
- [ ] CHK006 첫 화면의 네 메뉴 각각이 어디로 가는지(이미 있는 화면인지 준비 중 화면인지)가 요구사항 수준에서 정해져 있는가? [Gap, Spec §FR-001, Research 결정 5]
- [ ] CHK007 '준비 중' 상태와 대응표 미발행 상태가 사용자에게 같은 것으로 보여야 하는지 다른 것으로 보여야 하는지 요구사항이 정하는가? [Gap, Spec §FR-001·§FR-017, Research 결정 6]

## Requirement Clarity

- [ ] CHK008 여러 요구사항이 되풀이하는 '한 문장 안내'가 길이 제한인지 표현 형식인지, 리뷰에서 같은 결론이 나올 만큼 정의되어 있는가? [Ambiguity, Spec §FR-007·§FR-008·§FR-017]
- [ ] CHK009 '가장 중요한 행동 버튼 하나'를 판정하는 기준이 버튼 개수인지 시각적 위계인지 명확한가? [Clarity, Spec §FR-001, Constitution 원칙 II]
- [ ] CHK010 초깃값이 "입력 편의를 위한 예시이며 투자 권고가 아니다"라는 표시가 가리키는 범위(입력 칸마다인지 묶음 전체인지)가 명확한가? [Clarity, Spec §FR-005, Research 결정 7]
- [ ] CHK011 FR-005의 '초깃값이 예시'라는 표시와 FR-015의 '섹터 목록이 예시 대응표에서 왔다'는 표시가 사용자에게 다른 것으로 읽히도록 요구사항이 차이를 진술하는가? [Clarity, Spec §FR-005·§FR-015·§SC-007]
- [ ] CHK012 남은 비중을 표시하는 "그 위치"가 어디인지 객관적으로 판정할 수 있는가? [Ambiguity, Spec §FR-007, Constitution 원칙 VI]
- [ ] CHK013 상장일 안내가 담아야 할 내용(비교 시작일·원인이 된 섹터·워밍업 몫은 여기 없다는 사실)이 빠짐없이 열거되어 있는가? [Clarity, Spec §FR-008, Research 결정 4]
- [ ] CHK014 "데이터 입력이나 공급원 선택을 요구하지 않는다"가 안내 문구에 대한 제약인지 화면 요소에 대한 제약인지 명확한가? [Ambiguity, Spec §FR-008·§User Story 3]

## Requirement Consistency

- [ ] CHK015 사용자 수정 여부라는 분기값이 FR-005(섹터 증감)와 FR-007('동일하게 나누기') 사이에서 어떻게 바뀌는지 두 요구사항만 읽고 알 수 있는가? [Conflict, Spec §FR-005·§FR-007, Research 결정 10]
- [ ] CHK016 0% 허용(FR-016)과 두 개 이상 선택(FR-004)이 함께 걸릴 때 다음 단계로 넘어갈 수 있는지 두 요구사항이 같은 답을 주는가? [Consistency, Spec §FR-004·§FR-016]
- [ ] CHK017 화면의 행동 버튼을 하나로 제한하는 규칙과 대응표 미발행 상태의 '다시 불러오기' 행동이 서로 어긋나지 않는가? [Consistency, Spec §FR-001·§FR-017, Constitution 원칙 II]
- [ ] CHK018 덮어쓰기 확인(FR-012)이 늘리는 행동 수와 SC-001이 세는 두 번의 행동이 서로를 무효로 만들지 않는 조건이 명시되어 있는가? [Consistency, Spec §FR-012·§SC-001]
- [ ] CHK019 초깃값 자동 채움과 '동일하게 나누기'가 같은 잔여분 규칙을 쓴다는 요구가 두 곳에서 같은 말로 적혀 있는가? [Consistency, Spec §FR-005·§FR-007]

## Acceptance Criteria Quality

- [ ] CHK020 SC-004의 '막힌다'를 관찰 기록만으로 판정할 수 있는 기준이 정의되어 있는가? [Measurability, Spec §SC-004, specs/README.md PD-14]
- [ ] CHK021 SC-001이 세는 '행동'이 클릭 수인지 화면 전환 수인지 정의되어 있는가? [Measurability, Spec §SC-001]
- [ ] CHK022 SC-002의 "시스템이 비중을 자동으로 바꾼 사례"에 입력 거부·반올림·정규화 가운데 무엇이 포함되는지 판정할 수 있는가? [Measurability, Spec §SC-002, Research 결정 1]
- [ ] CHK023 안내가 '보인다'를 판정하는 기준이 SC-003·SC-007·SC-008에서 같은 뜻으로 쓰이는가? [Consistency, Measurability, Spec §SC-003·§SC-007·§SC-008]
- [ ] CHK024 SC-005의 "입력 항목이 0개"가 화면에 보이지 않는 숨은 항목까지 포함하는지 명확한가? [Clarity, Spec §SC-005·§FR-011]

## Scenario Coverage

- [ ] CHK025 FR-014(탭 세션 유지)·FR-016(0% 허용)·FR-017(대응표 미발행)에 대응하는 Acceptance Scenario가 있는가? [Gap, Spec §FR-014·§FR-016·§FR-017, §User Scenarios]
- [ ] CHK026 섹터 목록 조회가 네트워크 실패나 5xx로 끝나는 경우의 요구사항이 있는가, 아니면 대응표 미발행만 정의되어 있는가? [Gap, Exception Flow, Spec §FR-017, Research 결정 6]
- [ ] CHK027 탭 세션에서 되살린 값 가운데 일부가 현재 대응표에 없어 빠지는 경우, 사용자가 무엇을 보게 되는지 요구사항이 정하는가? [Coverage, Recovery, Spec §Edge Cases, Research 결정 3]
- [ ] CHK028 덮어쓰기 확인에 사용자가 답하지 않고 닫기만 한 경우의 결과가 정의되어 있는가? [Gap, Alternate Flow, Spec §FR-012]

## Edge Case Coverage

- [ ] CHK029 표시 정밀도를 넘는 소수를 입력했을 때의 처리가 요구사항에 있는가? [Gap, Spec §Edge Cases, Research 결정 1]
- [ ] CHK030 선택 섹터 전부의 비중이 0%여서 합계가 0%인 상태를 요구사항이 다루는가? [Coverage, Edge Case, Spec §Edge Cases·§FR-016]
- [ ] CHK031 브라우저 저장소를 쓸 수 없는 환경에서 FR-014가 어떻게 되는지 요구사항이 밝히는가? [Gap, Spec §FR-014, Research 결정 3]
- [ ] CHK032 섹터를 해제했다가 다시 고를 때의 비중이 요구사항으로 정해져 있는가? [Gap, Spec §FR-005, Data Model "상태 전이"]

## Non-Functional Requirements

- [ ] CHK033 키보드만으로 섹터 선택·비중 입력·덮어쓰기 확인을 끝낼 수 있어야 한다는 요구사항이 있는가? [Gap, Accessibility]
- [ ] CHK034 합계 상태 변화와 조건부 안내 문장이 보조 기술에 전달되어야 하는지 정해져 있는가? [Gap, Accessibility, Spec §FR-007]
- [ ] CHK035 화면 폭이 좁을 때도 섹터 목록과 비중 입력이 한 화면에 있어야 한다는 제약을 어떻게 지키는지 정해져 있는가? [Gap, Spec §범위 요약·§Assumptions, Plan Technical Context]
- [ ] CHK036 입력마다 즉시 반영한다는 반응 요구가 사용자가 판정할 수 있는 형태로 쓰였는가, 아니면 실측 전 수치를 피한 자리로 남았는가? [Measurability, Plan Performance Goals, Constitution 원칙 IX]

## Dependencies and Assumptions

- [ ] CHK037 SC-004가 PD-14에 의존한다는 사실이 spec의 결정 항목 절에 적혀 있는가? [Dependency, Spec §Pre-Implementation Decisions, specs/README.md PD-14]
- [ ] CHK038 섹터 수를 10~20개로 보는 전제가 요구사항의 가정으로 적혀 있는가, plan에만 있는가? [Assumption, Spec §Assumptions, Plan Technical Context]
- [ ] CHK039 "데스크톱 브라우저를 우선한다"는 가정이 지원 범위를 정하는 문장인지 우선순위만 정하는 문장인지 명확한가? [Ambiguity, Spec §Assumptions]

## Notes

- 요구사항 품질 기준이 충족되었다고 리뷰어가 판단한 항목만 `[x]`로 표시한다. 새로 만든 항목은 전부 `[ ]`로 둔다.
- 명확화·수정·리뷰가 더 필요한 항목은 `[ ]`로 남기고 발견 내용을 항목 아래에 적는다.
- `/speckit-implement`는 체크박스 상태를 게이트로 읽을 수 있지만 표시를 바꾸지 않는다.
- `checklists/requirements.md`는 `/speckit-specify`와 `/speckit-clarify`가 관리하는 별도 기본 체크리스트다.
- 접근성 항목(CHK033·CHK034)을 따로 파일로 나누지 않은 까닭은 002가 화면 하나짜리 spec이고, [014](../../014-user-auth/spec.md) FR-016이 같은 요구를 기능 요구사항 안에 둔 선례가 있기 때문이다.
- 이 체크리스트는 요구사항이 어떻게 쓰였는지를 본다. 화면이 실제로 그렇게 움직이는지는 [quickstart.md](../quickstart.md)의 시나리오 0~11이 확인한다. 같은 내용을 두 곳에 적지 않는다.

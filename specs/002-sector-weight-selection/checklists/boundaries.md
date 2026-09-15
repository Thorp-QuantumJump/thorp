# Cross-Spec Boundary Requirements Quality Checklist: 섹터·비중 선택

**Purpose**: 002가 다른 spec에 넘긴 규칙과 다른 spec에서 받아 쓰는 규칙이 빠짐없이, 한 곳에서만, 같은 뜻으로 쓰였는지 검토한다. 002의 Clarifications 아홉 건 가운데 여섯 건이 "이 규칙은 어느 spec의 것인가"로 결론이 났고, Out of Scope 여섯 줄 가운데 넷이 다른 spec에 소유를 넘긴다. 경계가 어긋나면 같은 문장을 두 곳에서 관리하거나 아무도 관리하지 않게 된다.
**Created**: 2026-09-15
**Feature**: [spec.md](../spec.md)

**Note**: 이 체크리스트는 `/speckit-checklist` 명령으로 만든 요구사항 품질 검토 문서이며, 구현 동작을 시험하는 테스트 목록이 아니다.
**Review Ownership**: 이 체크리스트는 리뷰어가 소유한다. 요구사항 품질 기준이 충족되었다고 리뷰어가 판단한 항목만 `[x]`로 표시한다.
**Marker Semantics**: `[x]`는 요구사항 품질 검토가 끝났다는 뜻이며 구현 완료를 뜻하지 않는다.

## 소유와 책임 경계

- [ ] CHK001 FR-013이 002 안에서 확인할 수 있는 요구사항인지, 아니면 003이 소유해야 할 문장인지 판정되어 있는가? [Conflict, Spec §FR-013, Contract "004 분석 요청과의 관계", Plan 묶음 M4]
- [ ] CHK002 전략 워밍업 때문에 비교 기간이 더 줄어드는 몫을 003이 알린다는 분담이 002와 [003](../../003-strategy-settings/spec.md) 양쪽에서 같은 말로 적혀 있는가? [Consistency, Spec §FR-008, 003 §FR-011]
- [ ] CHK003 탭 세션에서 되살린 섹터가 현재 대응표에 없을 때, 002가 하는 '빠졌다는 사실 알림'과 [006](../../006-my-records/spec.md) FR-008이 소유하는 '무엇을 할지 제안'의 경계가 판정할 수 있게 그어져 있는가? [Ambiguity, Spec §Edge Cases, Research 결정 3]
- [ ] CHK004 배우기 예시로 들어왔을 때의 입력 유지 범위를 [013](../../013-learning-pages/spec.md) FR-013이 002에 위임한다는 사실이 013 쪽에도 같은 뜻으로 적혀 있는가? [Consistency, Spec §Clarifications·§FR-012]
- [ ] CHK005 002가 소유하지 않는 계약을 002의 문서가 다시 정의한 곳이 없는가? [Consistency, Contracts README "002가 소유하지 않는 계약"]
- [ ] CHK006 인증과 복귀 규칙을 [014](../../014-user-auth/spec.md)가 소유한다는 결정이 002의 어느 문서에서도 뒤집히지 않았는가? [Consistency, Spec §Out of Scope, Contracts README]

## 받아 쓰는 계약에 대한 가정

- [ ] CHK007 "`sectors` 배열의 모든 항목에 대표 ETF가 있다"는 001 계약의 불변식에 002의 요구사항 두 개(FR-008·Key Entities)가 통째로 기대고 있다는 사실과, 그 불변식이 바뀌면 무엇이 깨지는지가 적혀 있는가? [Assumption, Spec §FR-008·§Key Entities]
- [ ] CHK008 섹터 목록의 정렬이 001 계약에 정해져 있지 않아 002가 스스로 정렬한다는 사실이 요구사항의 근거로 남아 있는가? [Dependency, Research 결정 2]
- [ ] CHK009 002가 `GET /sectors/evaluation-window`를 부르지 않는다는 결정이, 그 값을 003이 반드시 받는다는 요구로 이어지는가? [Dependency, Contracts README, Research 결정 4]
- [ ] CHK010 KRX 출처 표시를 화면 상수로 두는 조건부 통과가 001 계약이 넓어지기 전까지 무엇을 지켜야 하는 상태인지 적혀 있는가? [Assumption, Plan Complexity Tracking, Research 결정 8]
- [ ] CHK011 대표 ETF 상장폐지 안내를 이번 범위에서 뺀 근거(응답에 폐지일이 없다)가 001 계약이 넓어지면 다시 검토된다는 조건까지 적혀 있는가? [Assumption, Spec §Out of Scope]
- [ ] CHK012 002가 예시 대응표 여부를 001의 값 하나로만 판정한다는 점과, 대표 상품 정보의 같은 이름 필드가 다른 것을 가리킨다는 점이 혼동되지 않게 쓰였는가? [Ambiguity, Spec §FR-015, Data Model "대표 상품 정보"]

## 넘겨주는 값의 계약

- [ ] CHK013 013이 보내는 예시 페이로드의 필수 필드와 002가 그것을 검증하는 규칙이 한 곳에서만 정의되는가? [Consistency, Contract "배우기 예시 진입"]
- [ ] CHK014 세션 페이로드의 형식 번호가 올라갔을 때의 동작이 읽는 쪽 둘(002와 003) 모두에 같은 규칙으로 요구되는가? [Consistency, Contract "탭 세션 보관"]
- [ ] CHK015 합계가 100%가 아닌 값도 그대로 보관한다는 불변식이, 003이 그런 값을 받았을 때 무엇을 해야 하는지까지 이어지는가? [Gap, Contract "탭 세션 보관", Spec §FR-007]
- [ ] CHK016 비중을 0.1%p 단위 정수로 싣는다는 표현 규칙이 004의 메시지 계약과 만나는 지점에서 누가 변환하는지 정해져 있는가? [Gap, Contract "004 분석 요청과의 관계"]
- [ ] CHK017 대응표 버전을 세션과 인계 값에 함께 싣는 요구가 재현 기록(원칙 VIII)에 실제로 쓰이는 곳까지 연결되어 있는가? [Dependency, Plan Constitution Check VIII, Contract "탭 세션 보관"]

## 문서 사이의 표기 일치

- [ ] CHK018 spec 본문이 다른 spec의 계약 식별자(엔드포인트 경로·HTTP 상태·오류 코드)를 직접 인용하는 것이 팀의 spec 작성 기준에 맞는지 판정되어 있는가? [Ambiguity, Spec §FR-008·§FR-017·§SC-008]
- [ ] CHK019 002 문서 묶음이 서로를 줄 번호로 가리키는 곳들이 원문이 바뀌어도 어긋나지 않게 관리되는가? [Gap, Research·Plan의 `spec.md:NNN` 인용]

## Notes

- 요구사항 품질 기준이 충족되었다고 리뷰어가 판단한 항목만 `[x]`로 표시한다. 새로 만든 항목은 전부 `[ ]`로 둔다.
- 이 체크리스트가 보는 것은 "경계가 문서에 제대로 쓰였는가"이지 "다른 spec이 약속을 지키는가"가 아니다. 뒤쪽은 각 spec의 몫이다.
- 항목이 다른 spec의 문서를 가리키더라도 그 문서를 고치는 일은 이 spec의 범위가 아니다. 어긋남을 찾으면 소유 spec에 알린다.
- `/speckit-implement`는 체크박스 상태를 게이트로 읽을 수 있지만 표시를 바꾸지 않는다.
- `checklists/requirements.md`는 `/speckit-specify`와 `/speckit-clarify`가 관리하는 별도 기본 체크리스트다.

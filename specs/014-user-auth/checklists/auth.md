# Authentication Requirements Quality Checklist: 사용자 인증

**Purpose**: 회원가입·이메일 인증·세션 인증·보호 경로에 관한 요구사항이 구현 작업으로 분리되기 전에 완전하고 명확하며 일관되고 측정 가능하게 작성되었는지 검토한다.
**Created**: 2026-09-10
**Feature**: [014-user-auth specification](../spec.md)

**Note**: 이 체크리스트는 `/speckit-checklist` 명령으로 생성한 요구사항 품질 검토 문서이며, 구현 동작을 시험하는 테스트 목록이 아니다.
**Review Ownership**: 이 체크리스트는 리뷰어가 소유한다. 요구사항 품질 기준이 충족되었다고 리뷰어가 판단한 항목만 `[x]`로 표시한다.
**Marker Semantics**: `[x]`는 요구사항의 품질 검토가 완료되었다는 뜻이며 구현 작업의 완료를 뜻하지 않는다.

## Requirement Completeness

- [x] CHK001 회원가입 입력값, 정규화 기준, 검증 제한 및 고유성 조건이 spec·data model·API contract 전반에 빠짐없이 정의되어 있는가? [Completeness, Spec §FR-001–FR-003]
- [x] CHK002 인증번호 발송·재발송·확인·만료·이미 확인됨·요청 없음의 전체 이메일 인증 생명주기 요구사항이 정의되어 있는가? [Completeness, Spec §FR-004]
- [x] CHK003 회원가입 성공 후 이메일만 로그인 화면에 전달하고 비밀번호를 폐기해야 한다는 요구사항이 모든 관련 흐름에 적용되도록 정의되어 있는가? [Completeness, Spec §FR-005, §FR-015]
- [x] CHK004 로그인, 현재 사용자 조회, 로그아웃 및 CSRF 준비에 필요한 서버 기능과 각 기능의 구현 상태가 명시되어 있는가? [Completeness, Spec §FR-014, Contract §paths]
- [x] CHK005 인증 상태의 여섯 상태(확인 중·로그인됨·비로그인·만료·확인 실패·로그아웃 미확인)에 각각 진입·이탈 조건과 허용 동작이 정의되어 있는가? [Completeness, Spec §Key Entities, Plan §Frontend state and routing]
- [x] CHK006 공개 기능과 보호 기능의 범위가 모든 관련 화면 및 진입점에 대해 정의되어 있는가? [Completeness, Spec §FR-010–FR-012]
- [x] CHK007 회원가입·로그인뿐 아니라 이메일 인증, 상태 확인, 로그아웃 및 CSRF 실패에도 네트워크 오류와 복구 요구사항이 정의되어 있는가? [Coverage, Spec §FR-006, §FR-009, §FR-013]

## Requirement Clarity

- [x] CHK008 이메일의 공백 제거·대소문자 처리와 전화번호 정규화 주체 및 판정 규칙이 FE와 BE에서 동일하게 해석될 만큼 구체적인가? [Ambiguity, Spec §Edge Cases, §FR-001–FR-003]
- [x] CHK009 이메일 인증의 “3분”이 시작되는 시점, 만료 판정 주체 및 재발송 후 기존 번호 무효화 시점이 명확한가? [Clarity, Spec §FR-004]
- [x] CHK010 “동일 동작의 중복 제출”이 어떤 요청과 시간 구간을 뜻하는지, 늦게 도착한 응답의 우선순위까지 명확히 정의되어 있는가? [Ambiguity, Spec §FR-006, §Edge Cases]
- [x] CHK011 “서버에서 로그아웃 완료를 확인”하는 조건과 `logoutUnconfirmed`에서 상태 확인·재시도 후 가능한 결과가 명확한가? [Clarity, Spec §FR-009, Contract §/auth/sessions/current]
- [x] CHK012 최초 비로그인과 기존 로그인 세션 만료를 같은 401 응답에서 구분하는 기준이 문서 간 동일하고 명확한가? [Clarity, Spec §FR-008, §FR-012, Contract §AuthenticationRequired]
- [x] CHK013 안전한 복귀 위치의 허용 형식, 제외 경로, 기본 목적지 및 일회성 소비 시점이 명확하게 정의되어 있는가? [Clarity, Spec §FR-011–FR-012, Plan §Frontend state and routing]
- [x] CHK014 여러 탭의 인증 상태가 달라졌을 때 “보호 기능 이용 전 재확인”이 일어나는 구체적인 조건이 정의되어 있는가? [Ambiguity, Spec §Edge Cases]
- [x] CHK015 사용자에게 보여줄 수 있는 오류와 숨겨야 하는 내부 오류·민감정보의 경계가 안정된 오류 코드 수준으로 정의되어 있는가? [Clarity, Spec §FR-003, §FR-013, Contract §ApiError]

## Requirement Consistency

- [x] CHK016 spec의 서버 지정 유효기간과 plan의 기본 30분 비활성 만료가 충돌 없이 “설정 가능한 기본값”으로 일관되게 표현되어 있는가? [Consistency, Spec §FR-008, Plan §Authentication and session]
- [x] CHK017 비영구 세션 쿠키 정책이 별도 로그인 유지 옵션을 제공하지 않는다는 결정과 일치하는가? [Consistency, Spec §Clarifications, Plan §Authentication and session]
- [x] CHK018 spec에서 구분 가능한 가입 거절 사유를 요구하면서 contract가 이를 단일 `INVALID_INPUT`으로 표현하는 부분에 FE가 구분할 수 있는 명확한 계약이 있는가? [Conflict, Spec §FR-003–FR-004, Contract §InvalidInput]
- [x] CHK019 로그인 실패 시 계정 존재·비밀번호 불일치·계정 상태를 외부에서 구분하지 않는 원칙이 spec·plan·contract에 일관되게 반영되어 있는가? [Consistency, Spec §FR-007, Plan §Validation and error mapping, Contract §InvalidCredentials]
- [x] CHK020 외부 `/api` 경로와 Backend 내부 매핑 경로의 책임 및 기존 Account API 경로 보정 필요성이 plan과 contract에서 일치하는가? [Consistency, Plan §CSRF and request boundary, Contract §implementation status]
- [x] CHK021 현재 사용자 응답과 회원가입 응답에서 노출 가능한 필드 및 `ACTIVE` 상태의 의미가 data model과 contract에서 일치하는가? [Consistency, Spec §Key Entities, Contract §SignupResponse, §AuthenticatedUser]
- [x] CHK022 `내 기록`만 보호하고 전략 진단·시장 흐름·배우기는 공개한다는 경계가 관련 006 spec 및 프로젝트 공통 가정과 일치하는가? [Consistency, Spec §FR-010–FR-012, Spec §Dependencies]

## Acceptance Criteria Quality

- [x] CHK023 회원가입 완료 3분 기준의 시작·종료점과 “메일 도착 대기 제외” 측정 방식이 객관적으로 판정 가능하게 정의되어 있는가? [Measurability, Spec §SC-001]
- [x] CHK024 오류 표본 100%에서 “다음 조치를 알 수 있는 안내”를 판정할 수 있도록 필요한 안내 내용 또는 판정 기준이 정의되어 있는가? [Measurability, Spec §SC-002]
- [x] CHK025 중복 요청 0건 기준에 반복 횟수, 요청 종류 및 관찰 범위가 정의되어 있는가? [Measurability, Spec §SC-003]
- [x] CHK026 인증 복원·보호 정보 비노출·원래 위치 복귀 기준이 각 인증 상태와 직접 대응되어 객관적으로 판정 가능한가? [Acceptance Criteria, Spec §SC-004, §SC-006–SC-007]
- [x] CHK027 키보드와 보조 기술에 전달되어야 할 주요 상태 변경 및 완료 기준이 구체적으로 열거되어 있는가? [Measurability, Spec §SC-009, §FR-016]
- [x] CHK028 세션·CSRF·민감정보 비노출과 같은 핵심 보안 요구사항에 대응하는 측정 가능한 성공 기준이 정의되어 있는가? [Gap, Acceptance Criteria]

## Scenario and Recovery Coverage

- [x] CHK029 앱 최초 진입의 현재 사용자 조회가 성공·비로그인·네트워크 실패·서버 실패로 끝나는 각 경우의 요구사항이 정의되어 있는가? [Coverage, Spec §FR-008, §User Story 5]
- [x] CHK030 로그인 상태가 보호 API 요청 도중 만료되는 경우와 화면 진입 전에 만료되는 경우 모두에 보호 정보 제거·복귀 위치 보존 요구사항이 정의되어 있는가? [Coverage, Spec §FR-012]
- [x] CHK031 CSRF 토큰 누락·불일치·만료 시 토큰 갱신, 사용자 안내 및 재시도 책임이 정의되어 있는가? [Coverage, Plan §CSRF and request boundary, Contract §InvalidCsrf]
- [x] CHK032 CSRF 갱신 후 상태 변경 요청을 자동 재전송할지 사용자 동작으로 재시도할지 모든 요청 종류에 대해 일관되게 정의되어 있는가? [Clarity, Plan §Validation and error mapping]
- [x] CHK033 로그아웃 응답 미수신 후 재확인 결과가 로그인됨·비로그인·계속 확인 실패인 각 경우의 다음 상태가 정의되어 있는가? [Recovery, Spec §FR-009, Plan §Frontend state and routing]
- [x] CHK034 화면 이탈 뒤 늦게 도착한 로그인·회원가입·이메일 인증 응답이 현재 인증 상태를 덮어쓰지 않도록 요구되는 우선순위 규칙이 정의되어 있는가? [Coverage, Spec §Edge Cases]
- [x] CHK035 이미 로그인한 사용자가 로그인·회원가입 화면에 직접 접근하는 경우의 기대 흐름이 정의되어 있는가? [Gap, Alternate Flow]
- [x] CHK036 로그아웃 또는 세션 만료 뒤 브라우저 뒤로가기·캐시 복원으로 이전 보호 정보가 보일 수 있는 상황의 요구사항이 정의되어 있는가? [Gap, Edge Case]

## Security, Privacy, and Accessibility Requirements

- [x] CHK037 세션 쿠키의 `HttpOnly`, 운영 `Secure`, `SameSite=Lax`, `Path=/`, 비영구 속성과 적용 환경이 계약 또는 계획에 명확히 정의되어 있는가? [Completeness, Plan §Authentication and session]
- [x] CHK038 로그인 성공 시 세션 고정 공격을 막기 위한 세션 식별자 교체 요구사항이 정의되었거나 의도적으로 제외되었는가? [Gap, Security]
- [x] CHK039 비밀번호·세션 식별자·인증번호를 URL, 브라우저 영구 저장소, 사용자 응답 및 로그에 남기지 않는 요구사항이 각 데이터 경계별로 정의되어 있는가? [Completeness, Spec §FR-005, §FR-015, Plan §Authentication and session]
- [x] CHK040 반복 로그인·인증번호 요청에 대한 남용 방지, 시도 제한 또는 해당 범위의 명시적 제외가 정의되어 있는가? [Gap, Security]
- [x] CHK041 현재 세션만 로그아웃한다는 범위와 동시 로그인 세션을 제한하지 않는다는 결정이 사용자 기대 및 계약에 명시되어 있는가? [Clarity, Contract §/auth/sessions/current, Data Model §AuthenticationSession]
- [x] CHK042 오류·처리 중·만료·로그아웃 미확인 상태의 보조 기술 알림과 키보드 포커스 요구사항이 각 인증 흐름에 대해 정의되어 있는가? [Coverage, Spec §FR-016]
- [x] CHK043 데스크톱 우선과 모바일 대응 범위가 계획 단계에서 결정되어 반응형 인증 화면 요구사항으로 명확히 남아 있는가? [Ambiguity, Spec §Assumptions]

## Dependencies and Responsibility Boundaries

- [x] CHK044 기존 회원가입·이메일 인증 구현에서 재사용할 요청값·응답값·거절 규칙과 변경이 필요한 경로가 검증된 사실로 구분되어 있는가? [Dependency, Spec §Dependencies, Contract §implementation status]
- [x] CHK045 신규 로그인·현재 사용자 조회·로그아웃·CSRF 기능이 미구현 서버 의존성으로 표시되고 FE가 이를 구현 완료로 가정하지 않도록 정의되어 있는가? [Dependency, Spec §FR-014, Contract §implementation status]
- [x] CHK046 FE의 인증 상태·화면·복귀 위치 책임과 BE의 자격 증명 검증·세션·CSRF·계정 상태 책임이 겹치거나 빠짐없이 구분되어 있는가? [Completeness, Plan §Work Partition for Task Generation]
- [x] CHK047 API별 성공·입력 오류·인증 오류·CSRF 오류·서버 오류·응답 없는 네트워크 실패의 형식과 담당 주체가 필요한 범위에서 정의되어 있는가? [Completeness, Contract §paths, Contract §responses]
- [x] CHK048 세션 테이블, 계정 상태, 이메일 인증 저장소 및 `내 기록` 식별 연결에 필요한 데이터 소유권과 개인정보 분리 요구사항이 정의되어 있는가? [Dependency, Data Model §Data ownership and privacy, Constitution §기록·개인정보·보관]

## Notes

- 요구사항 품질 기준이 충족되었다고 리뷰어가 판단한 항목만 `[x]`로 표시한다.
- 명확화·수정·리뷰가 더 필요한 항목은 `[ ]` 상태로 남기고 발견 내용을 항목 아래에 기록한다.
- `/speckit-implement`는 체크박스 상태를 게이트로 읽을 수 있지만 표시를 변경하지 않는다.
- `checklists/requirements.md`는 `/speckit-specify`와 `/speckit-clarify`가 관리하는 별도 기본 체크리스트다.
- 항목의 `[Gap]`, `[Ambiguity]`, `[Conflict]`, `[Dependency]` 표시는 우선 검토해야 할 요구사항 품질 위험을 나타낸다.

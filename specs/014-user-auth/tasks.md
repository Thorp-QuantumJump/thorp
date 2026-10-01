---

description: "로그인·회원가입·로그아웃 사용자 기능의 실행 작업 목록"
---

# Tasks: 사용자 인증

**Input**: `specs/014-user-auth/`의 spec.md, plan.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: spec의 독립 검증 시나리오와 plan의 FE·BE·통합 검증 방침에 따라 자동화 테스트 작업을 포함한다.

**Organization**: 작업은 사용자 스토리별로 구성한다. 개별 T번호는 실행 지시이며, 하단의 작업 묶음 하나가 Jira Task·Branch·MR 하나에 대응한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 선행 작업이 끝난 뒤 다른 파일에서 병렬 진행 가능
- **[Story]**: spec.md의 User Story 추적 라벨
- 모든 경로는 저장소 루트 기준

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 인증 구현과 자동화 검증에 필요한 FE·BE 공통 기반을 준비한다.

- [x] T001 [P] Add Vitest, jsdom, React Testing Library, and user-event dependencies and test scripts in `frontend/package.json` and `frontend/package-lock.json`
- [x] T002 Configure Vitest DOM environment, aliases, and shared cleanup in `frontend/vite.config.ts` and `frontend/src/test/setup.ts` after T001
- [x] T003 [P] Replace crypto-only security dependency with Spring Security starter and add Spring Session JDBC in `backend/build.gradle`
- [x] T004 [P] Add PostgreSQL Spring Session tables and indexes without changing existing user data in `backend/src/main/resources/db/migration/V20260915092702__spring_session.sql`
- [x] T005 Configure 30-minute inactive session timeout and `THORP_SESSION` cookie defaults per environment in `backend/src/main/resources/application.yml`, `backend/src/main/resources/application-local.yml`, and `backend/src/main/resources/application-prod.yml`

**Checkpoint**: FE component tests and BE session-backed security code can be implemented.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 인증 사용자 스토리가 공유하는 보안 경계, 오류 계약, FE 타입과 요청 기반을 만든다.

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 사용자 스토리 구현을 병합하지 않는다.

- [x] T006 [P] Add failing contract tests for the common authentication error envelope, CSRF 403, and sanitized 500 responses in `backend/src/test/java/com/ssafy/thorp/account/presentation/AuthErrorContractTest.java`
- [x] T007 [P] Add failing integration tests for public routes, protected authentication routes, CSRF bootstrap, cookie attributes, and session-id rotation in `backend/src/test/java/com/ssafy/thorp/account/security/AuthSecurityIntegrationTest.java`
- [x] T008 [P] Define auth request, response, user, state, and stable error-code types from `contracts/auth-api.yaml` in `frontend/src/types/auth.ts`
- [x] T009 Add stable account/auth error codes and security exception envelope handling in `backend/src/main/java/com/ssafy/thorp/common/exception/ErrorCode.java` and `backend/src/main/java/com/ssafy/thorp/common/web/advice/GlobalExceptionHandler.java` to satisfy T006
- [x] T010 Add Spring Security session persistence, session-fixation protection, CSRF cookie repository, and JSON authentication handlers in `backend/src/main/java/com/ssafy/thorp/common/config/SecurityConfig.java` to satisfy T007
- [x] T011 Extend Axios credentials, XSRF cookie/header names, CSRF bootstrap, and normalized API/network error handling in `frontend/src/api/client.ts`
- [x] T012 [P] Add reusable auth API and provider test fixtures without storing credentials in `frontend/src/test/authFixtures.ts` and `frontend/src/test/renderWithRouter.tsx`

**Checkpoint**: Foundation ready. US1 and the BE/FE portions of later stories can proceed according to their dependencies.

---

## Phase 3: User Story 1 - 새 사용자가 계정을 만든다 (Priority: P1) 🎯 MVP

**Goal**: 이메일 소유 확인 후 계정을 만들고 가입 이메일만 채워진 로그인 화면으로 이동한다.

**Independent Test**: 신규 이메일의 발송·확인·가입 성공과 형식 오류, 중복 이메일·전화번호, 인증 요청 없음·잘못됨·만료·미확인을 각각 재현해 계정 중복 없이 다음 행동 안내를 확인한다.

### Tests for User Story 1

- [x] T013 [P] [US1] Add failing service tests for canonical email use, phone validation, verification lifecycle, and detailed account error codes in `backend/src/test/java/com/ssafy/thorp/account/application/SignupAndEmailVerificationServiceTest.java`
- [x] T014 [P] [US1] Add failing controller tests for the three existing account operations, internal proxy paths, CSRF, and contract responses in `backend/src/test/java/com/ssafy/thorp/account/presentation/AccountControllerContractTest.java`
- [x] T015 [P] [US1] Add failing component tests for validation, send/confirm/signup pending states, duplicate submission, accessible errors, and email-only handoff in `frontend/src/pages/SignupPage.test.tsx`
- [x] T016 [P] [US1] Add failing auth client tests for email verification and signup success/error envelopes in `frontend/src/api/auth.test.ts`

### Implementation for User Story 1

- [x] T017 [US1] Implement trim-and-lowercase email normalization at the Backend trust boundary in `backend/src/main/java/com/ssafy/thorp/account/application/EmailNormalizer.java`, `backend/src/main/java/com/ssafy/thorp/account/application/service/SignupService.java`, and `backend/src/main/java/com/ssafy/thorp/account/application/service/EmailVerificationService.java`
- [x] T018 [US1] Normalize Account controller mappings to internal `/accounts`, return the stable rejection codes, and preserve 204/201 success contracts in `backend/src/main/java/com/ssafy/thorp/account/presentation/controller/SignupController.java` and `backend/src/main/java/com/ssafy/thorp/account/presentation/controller/EmailVerificationController.java`
- [x] T019 [P] [US1] Implement CSRF-aware email verification and signup client functions in `frontend/src/api/auth.ts`
- [x] T020 [P] [US1] Implement reusable labeled auth fields, status announcements, first-error focus, and submit controls in `frontend/src/components/auth/AuthField.tsx`, `frontend/src/components/auth/AuthStatus.tsx`, and `frontend/src/components/auth/AuthForm.module.css`
- [x] T021 [US1] Implement the responsive email verification and signup flow with request identity guards in `frontend/src/pages/SignupPage.tsx` and `frontend/src/pages/SignupPage.module.css` after T019 and T020
- [x] T022 [US1] Register `/signup` and a lazy `/login` route shell, then pass only the canonical email through navigation state after signup in `frontend/src/App.tsx` and `frontend/src/pages/LoginPage.tsx`

**Checkpoint**: 이메일 인증과 회원가입은 로그인 세션 구현 없이도 계약 수준에서 독립 검증 가능하다.

---

## Phase 4: User Story 2 - 기존 사용자가 로그인하고 상태를 복원한다 (Priority: P1)

**Goal**: 이메일·비밀번호로 로그인하고 새로고침·재진입 뒤 서버 세션 기준으로 같은 사용자를 복원한다.

**Independent Test**: 정상·잘못된·비활성 계정 로그인과 `/auth/me`의 200·401·네트워크 실패를 재현해 인증 상태 전이와 확인 중 보호 정보 비노출을 확인한다.

### Tests for User Story 2

- [x] T023 [P] [US2] Add failing repository and service tests for normalized-email lookup, password verification, inactive-account concealment, and last-login update in `backend/src/test/java/com/ssafy/thorp/account/application/AuthenticationServiceTest.java`
- [x] T024 [P] [US2] Add failing MockMvc and PostgreSQL session tests for login and current-user contracts in `backend/src/test/java/com/ssafy/thorp/account/presentation/AuthenticationControllerIntegrationTest.java`
- [x] T025 [P] [US2] Add failing tests for checking/authenticated/anonymous/error transitions and bootstrap deduplication in `frontend/src/auth/AuthProvider.test.tsx`
- [x] T026 [P] [US2] Add failing login page tests for canonical email, generic credential failure, pending submission, signup-email prefill, and authenticated redirect in `frontend/src/pages/LoginPage.test.tsx`

### Implementation for User Story 2

- [x] T027 [US2] Add normalized-email lookup and authenticated account projection in `backend/src/main/java/com/ssafy/thorp/account/application/repository/UserRepository.java`, `backend/src/main/java/com/ssafy/thorp/account/infrastructure/persistence/UserRepositoryImpl.java`, and `backend/src/main/java/com/ssafy/thorp/account/application/result/AuthenticatedUserResult.java`
- [x] T028 [US2] Implement credential validation, account-state concealment, last-login update, and explicit SecurityContext persistence in `backend/src/main/java/com/ssafy/thorp/account/application/service/AuthenticationService.java` and `backend/src/main/java/com/ssafy/thorp/account/application/command/LoginCommand.java`
- [x] T029 [US2] Implement login, current-user, and CSRF endpoints plus request/response DTOs in `backend/src/main/java/com/ssafy/thorp/account/presentation/controller/AuthenticationController.java`, `backend/src/main/java/com/ssafy/thorp/account/presentation/request/LoginRequest.java`, and `backend/src/main/java/com/ssafy/thorp/account/presentation/response/AuthenticatedUserResponse.java`
- [x] T030 [P] [US2] Add login and current-user operations to `frontend/src/api/auth.ts`
- [x] T031 [US2] Implement the single-owner authentication state machine and startup restoration in `frontend/src/auth/AuthProvider.tsx` after T025 and T030
- [x] T032 [US2] Complete the login form, signup navigation, generic credential error, status announcement, and `/home` redirect for authenticated users in `frontend/src/pages/LoginPage.tsx` and `frontend/src/pages/LoginPage.module.css`
- [x] T033 [US2] Mount `AuthProvider`, connect lazy auth routes, and expose authenticated user/login state in `frontend/src/App.tsx`, `frontend/src/main.tsx`, and `frontend/src/components/navigation/AppHeader.tsx`

**Checkpoint**: 로그인과 현재 사용자 복원은 내 기록·로그아웃 화면 없이 독립 검증 가능하다.

---

## Phase 5: User Story 3 - 로그인 후 원래 내 기록으로 돌아간다 (Priority: P1)

**Goal**: 비로그인 사용자의 내 기록을 보호하고 로그인 후 검증된 원래 내부 위치로 복귀시킨다.

**Independent Test**: 로그아웃 상태로 `/records` 하위 경로에 직접 접근하고 로그인한 뒤 query/hash를 포함한 원래 위치로 돌아가며 외부·이중 슬래시·인증 화면 경로는 `/home`으로 대체되는지 확인한다.

### Tests for User Story 3

- [x] T034 [P] [US3] Add failing unit tests for same-origin return-target validation, one-time consumption, reload persistence, and unsafe fallback in `frontend/src/auth/returnTarget.test.ts`
- [x] T035 [P] [US3] Add failing router tests for checking, anonymous, authenticated, expired, and invalid-return-target access in `frontend/src/auth/ProtectedRoute.test.tsx`
- [x] T036 [P] [US3] Add failing navigation tests proving public routes remain available and only the records entry is protected in `frontend/src/App.auth.test.tsx`

### Implementation for User Story 3

- [x] T037 [US3] Implement validated tab-scoped return-target storage and one-time consumption in `frontend/src/auth/returnTarget.ts` to satisfy T034
- [x] T038 [US3] Implement the records-only authentication boundary and loading/login-required states in `frontend/src/auth/ProtectedRoute.tsx` to satisfy T035
- [x] T039 [US3] Add an authentication-only `/records/*` entry boundary without implementing record content in `frontend/src/pages/MyRecordsEntryPage.tsx` and `frontend/src/pages/MyRecordsEntryPage.module.css`
- [x] T040 [US3] Wire the protected records route, post-login return, and public route exemptions in `frontend/src/App.tsx` after T037–T039
- [x] T041 [US3] Add the secondary “내 기록” navigation entry and authenticated/anonymous destination behavior in `frontend/src/components/navigation/AppHeader.tsx` and `frontend/src/components/navigation/AppHeader.module.css`

**Checkpoint**: US3 protects only the records boundary and remains independent from the record list implementation in spec 006.

---

## Phase 6: User Story 4 - 사용자가 로그아웃한다 (Priority: P2)

**Goal**: 현재 서버 세션을 종료하고 응답을 확인하지 못하면 보호 정보를 숨긴 `logoutUnconfirmed` 상태를 유지한다.

**Independent Test**: 로그인 상태의 로그아웃 204, 이미 비로그인인 로그아웃 204, 네트워크 실패, 재시도 및 `/auth/me` 재확인을 각각 재현해 이전 사용자 정보가 노출되지 않는지 확인한다.

### Tests for User Story 4

- [x] T042 [P] [US4] Add failing integration tests for current-session invalidation, idempotent anonymous logout, cookie expiry, and post-logout 401 in `backend/src/test/java/com/ssafy/thorp/account/presentation/LogoutIntegrationTest.java`
- [x] T043 [P] [US4] Add failing provider and header tests for logout start, 204 completion, network uncertainty, retry, recheck, and immediate data removal in `frontend/src/auth/LogoutFlow.test.tsx`

### Implementation for User Story 4

- [x] T044 [US4] Implement idempotent current-session logout and JSON security behavior in `backend/src/main/java/com/ssafy/thorp/account/presentation/controller/AuthenticationController.java` and `backend/src/main/java/com/ssafy/thorp/common/config/SecurityConfig.java` to satisfy T042
- [x] T045 [P] [US4] Add current-session logout operation to `frontend/src/api/auth.ts`
- [x] T046 [US4] Implement `logoutUnconfirmed`, retry, and current-user recheck transitions with immediate credential-free state clearing in `frontend/src/auth/AuthProvider.tsx` after T043 and T045
- [x] T047 [US4] Add accessible logout, retry, and status UI without blocking public navigation in `frontend/src/components/navigation/AppHeader.tsx` and `frontend/src/components/navigation/AppHeader.module.css`

**Checkpoint**: 로그아웃 성공과 미확인 복구 흐름을 독립적으로 검증할 수 있다.

---

## Phase 7: User Story 5 - 만료와 일시적인 실패에서 안전하게 이어간다 (Priority: P2)

**Goal**: 세션 만료·CSRF 오류·네트워크 실패·다중 탭·페이지 캐시 복원에서도 보호 정보를 숨기고 안전하게 이어간다.

**Independent Test**: 보호 API 401, CSRF 403, 현재 사용자 조회 실패, 탭 재활성화, 뒤로가기와 page cache 복원을 재현해 공개 기능은 유지되고 보호 정보는 재확인 전 노출되지 않는지 확인한다.

### Tests for User Story 5

- [x] T048 [P] [US5] Add failing Backend tests for 30-minute inactivity expiry, CSRF refresh, session fixation, sanitized logs, and migration compatibility in `backend/src/test/java/com/ssafy/thorp/account/security/AuthSessionLifecycleIntegrationTest.java`
- [x] T049 [P] [US5] Add failing FE tests for authenticated 401 expiry, CSRF refresh without automatic mutation replay, stale-response rejection, and public-page availability in `frontend/src/auth/AuthRecovery.test.tsx`
- [x] T050 [P] [US5] Add failing browser-state tests for tab reactivation, protected-entry recheck, back-forward cache restoration, and stale protected-data clearing in `frontend/src/auth/AuthRevalidation.test.tsx`
- [x] T051 [P] [US5] Add failing accessibility and 360px responsive tests for pending, error, expired, and logout-unconfirmed states in `frontend/src/pages/AuthAccessibility.test.tsx`

### Implementation for User Story 5

- [x] T052 [US5] Complete session expiry, CSRF refresh, safe security logging, and production cookie behavior in `backend/src/main/java/com/ssafy/thorp/common/config/SecurityConfig.java`, `backend/src/main/resources/application.yml`, and `backend/src/main/resources/application-prod.yml` to satisfy T048
- [x] T053 [US5] Implement request identity/abort handling, authenticated 401 expiry, one-time CSRF refresh, and user-driven retry without automatic mutation replay in `frontend/src/api/client.ts` and `frontend/src/auth/AuthProvider.tsx`
- [x] T054 [US5] Revalidate authentication on tab activation, protected-route entry, and page-cache restoration while leaving public screens available in `frontend/src/auth/AuthProvider.tsx` and `frontend/src/auth/ProtectedRoute.tsx`
- [x] T055 [US5] Complete focus movement, live status announcements, reduced-motion-safe feedback, and 360px auth layouts in `frontend/src/components/auth/AuthStatus.tsx`, `frontend/src/pages/LoginPage.module.css`, and `frontend/src/pages/SignupPage.module.css`

**Checkpoint**: 모든 인증 상태와 복구 흐름이 독립적으로 검증 가능하다.

---

## Phase 8: Polish & Cross-Cutting Verification

**Purpose**: 전체 계약·회귀·문서 증거를 확인하고 MR 단위로 마무리한다.

- [x] T056 [P] Run and record FE lint, unit/component tests, and production build results in `specs/014-user-auth/verification/frontend.md`
- [x] T057 [P] Run and record BE spotless and PostgreSQL-backed test results in `specs/014-user-auth/verification/backend.md`
- [x] T058 Validate all seven operations and success/error examples against `specs/014-user-auth/contracts/auth-api.yaml` and record results in `specs/014-user-auth/verification/contract.md`
- [x] T059 Execute the signup→login→restore→records→expiry/logout scenarios and record desktop/360px evidence in `specs/014-user-auth/verification/integration.md`
- [x] T060 Update implementation status, deviations, and the deferred rate-limit security follow-up in `specs/014-user-auth/plan.md`, `specs/014-user-auth/contracts/README.md`, and `specs/014-user-auth/quickstart.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 → Phase 2**: test/runtime dependencies and session storage configuration precede shared security code.
- **Phase 2 → US1–US5**: common envelope, CSRF/session boundary, FE auth types/client are blocking prerequisites.
- **US1**: starts after Phase 2 and delivers the first independently demonstrable user value.
- **US2**: starts after Phase 2; T033 reuses the `/login` shell from T022.
- **US3**: depends on US2 authentication restoration and login completion.
- **US4**: Backend logout can start after Phase 2; FE logout depends on US2 provider state.
- **US5**: depends on US2 state restoration, US3 protection, and US4 uncertain-logout state.
- **Phase 8**: runs after the work batches included in the release are complete.

### User Story Dependency Graph

```text
Setup → Foundation → US1
                   └→ US2 → US3
                          └→ US4 → US5
US1 + US2 + US3 + US4 + US5 → Cross-cutting verification
```

### Within Each User Story

- Failing tests precede implementation.
- Backend persistence/repository changes precede services; services precede controllers.
- FE types/client functions precede Provider and pages; shared fields precede composed forms.
- A story checkpoint must pass before its work batch is offered for review.

### Parallel Opportunities

- T001, T003, T004 can run in parallel; T002 follows T001 and T005 follows the session dependency decision.
- T006, T007, T008, and T012 touch separate test/type files and can run in parallel.
- Within US1, T013–T016 can be authored in parallel; after contracts stabilize, BE T017–T018 and FE T019–T021 can run in parallel.
- Within US2, T023–T026 can run in parallel; Backend T027–T029 and Frontend T030–T032 can then run in parallel.
- US3 test tasks T034–T036 and US5 test tasks T048–T051 are parallel within their phases.
- T056 and T057 can run in parallel; T058–T060 consume their results.

---

## Parallel Execution Examples

### User Story 1

```text
BE: T013 → T017 → T018
FE: T015 + T016 → T019 + T020 → T021 → T022
```

### User Story 2

```text
BE: T023 + T024 → T027 → T028 → T029
FE: T025 + T026 → T030 → T031 + T032 → T033
```

### User Stories 3–5

```text
US3: T034 + T035 + T036 → T037 + T038 → T039 → T040 + T041
US4: T042 + T043 → T044 + T045 → T046 → T047
US5: T048 + T049 + T050 + T051 → T052 + T053 → T054 + T055
```

---

## Recommended Work Batches and Jira Tasks

개별 T번호가 아니라 아래 묶음 하나를 Jira Task·Branch·MR 하나로 사용한다. 실제 Jira 번호를 받은 뒤 constitution의 `{type}({position})/{JIRA_NUMBER}-{description}` 형식으로 브랜치를 만든다.

| Work Batch | Recommended Jira Task | Included Tasks | Depends On |
|---|---|---|---|
| WB-BE-1 | `[BE] 인증 보안 기반과 계정 계약 정비` | T003–T007, T009–T010, T013–T014, T017–T018 | 없음 |
| WB-FE-1 | `[FE] 회원가입과 이메일 인증 화면 구현` | T001–T002, T008, T011–T012, T015–T016, T019–T022 | WB-BE-1의 계약 확정 |
| WB-BE-2 | `[BE] 로그인과 현재 사용자 세션 API 구현` | T023–T024, T027–T029 | WB-BE-1 |
| WB-FE-2 | `[FE] 로그인과 인증 상태 복원 구현` | T025–T026, T030–T033 | WB-FE-1, WB-BE-2 계약 |
| WB-FE-3 | `[FE] 내 기록 보호와 로그인 복귀 구현` | T034–T041 | WB-FE-2 |
| WB-BE-3 | `[BE] 로그아웃과 세션 생명주기 구현` | T042, T044, T048, T052 | WB-BE-2 |
| WB-FE-4 | `[FE] 로그아웃·만료·인증 복구 구현` | T043, T045–T047, T049–T051, T053–T055 | WB-FE-2, WB-FE-3, WB-BE-3 계약 |
| WB-DOCS-1 | `[DOCS] 사용자 인증 통합 검증 결과 정리` | T056–T060 | 선택한 구현 묶음 완료 |

**Deferred follow-up**: 반복 로그인·인증번호 발송의 제한 수치와 계정 잠금 정책은 별도 Jira Task `[BE] 인증 요청 남용 방지 정책 수립 및 적용`으로 만든다. 014 작업에 임의 수치를 섞지 않는다.

---

## Implementation Strategy

### MVP First

1. Phase 1과 Phase 2를 완료한다.
2. WB-BE-1과 WB-FE-1로 US1 회원가입·이메일 인증을 완성한다.
3. US1 독립 검증을 통과한 뒤 데모하고 로그인 세션 묶음으로 확장한다.

### Incremental Delivery

1. Setup + Foundation → 인증 계약과 보안 경계 준비
2. US1 → 회원가입·이메일 인증
3. US2 → 로그인·현재 사용자 복원
4. US3 → 내 기록 보호·원래 위치 복귀
5. US4 → 로그아웃·미확인 복구
6. US5 → 만료·다중 탭·CSRF·캐시 복구
7. Phase 8 → 전체 통합 증거와 문서 상태 갱신

### Team Execution

- FE와 BE는 같은 contract를 기준으로 서로 다른 work batch에서 병렬 작업한다.
- 한 개발자가 여러 T번호를 수행해도 동일 목적의 work batch 안에서만 한 브랜치로 묶는다.
- Backend 계약이 바뀌면 해당 FE work batch를 시작하기 전에 `contracts/auth-api.yaml`을 먼저 갱신한다.
- rate-limit 수치 정책은 별도 보안 Task가 확정되기 전까지 014에 구현 완료로 표시하지 않는다.

## Notes

- 모든 T번호는 구현·테스트·리뷰가 가능한 구체적인 실행 지시다.
- `[P]`는 동일 파일 충돌과 미완료 선행 의존성이 없는 경우에만 표시했다.
- 각 work batch는 독립적으로 리뷰·검증·롤백 가능해야 하며 Jira Task 하나, Branch 하나, MR 하나에 대응한다.
- Backend나 Frontend 실제 코드는 `$speckit-implement` 또는 승인된 작업 묶음 구현 단계에서만 수정한다.
- 완료 표시는 기능 구현, 테스트 또는 동작 확인, 코드 리뷰, MR 병합, 필요한 문서 수정이 모두 끝난 뒤에만 한다.

---

# Social Login Extension (2026-10-01)

spec 2026-10-01 개정(US6·US7, FR-020~FR-026, SC-012~SC-015)의 작업이다. 위 T001~T060은 이메일 범위의 완료
기록이라 다시 열지 않는다. 설계 근거는 [plan.md](plan.md)의 Social login design, 계약은
[contracts/auth-api.yaml](contracts/auth-api.yaml)의 `x-implementation-status: planned` operation이다.

## Phase 9: Social Foundation (BE, Blocking)

**Purpose**: 제공자와 무관한 소셜 판정·대기 상태·가입·연결과 보안 설정을 만든다. 제공자별 작업(Phase 10~12)은 이 단계 뒤에 한다.

- [X] T061 [P] Add `spring-boot-starter-oauth2-client` to `backend/build.gradle` and record the dependency rationale for the MR
- [X] T062 [P] Add `social_accounts` with `(provider, provider_user_id)` and `(user_id, provider)` unique constraints and drop NOT NULL on `users.password_hash` in `backend/src/main/resources/db/migration/V{yyyyMMddHHmmss}__social_accounts.sql`
- [X] T063 [P] [US6] Add failing tests for the seven outcomes (`LOGGED_IN`, `ACCOUNT_UNAVAILABLE`, `EMAIL_MISSING`, `EMAIL_VERIFICATION`, `PROVIDER_CONFLICT`, `LINK_REQUIRED`, `SIGNUP_REQUIRED`) in `backend/src/test/java/com/ssafy/thorp/account/application/SocialLoginServiceTest.java`
- [X] T064 [P] [US6] Add failing tests for pending state expiry (10 min), single use, cancel, restart, and social verification codes (6 digits, 3 min, resend invalidates previous) in `backend/src/test/java/com/ssafy/thorp/account/application/PendingSocialLoginServiceTest.java`
- [X] T065 [P] [US7] Add failing tests for social signup and link rules: one transaction, stage mismatch → `SOCIAL_PENDING_STAGE_MISMATCH`, overlapping account not `ACTIVE` → `ACCOUNT_UNAVAILABLE` / `SOCIAL_SIGNUP_REJECTED`, phone match → `SOCIAL_LINK_REQUIRED` with `linkReason=PHONE`, `PASSWORD`/`EMAIL_CODE`/`VERIFIED_EMAIL` methods, `VERIFIED_EMAIL` rejected for `PHONE`, same-provider conflict, unique-constraint race in `backend/src/test/java/com/ssafy/thorp/account/application/SocialSignupAndLinkServiceTest.java`
- [X] T066 [P] Add failing contract tests for the social operations, error codes, CSRF, and session-id rotation on signup/link success in `backend/src/test/java/com/ssafy/thorp/account/presentation/SocialAuthContractTest.java`
- [X] T067 [P] Add a regression test that a password-less account gets `INVALID_CREDENTIALS` from `POST /auth/sessions` without calling the password encoder in `backend/src/test/java/com/ssafy/thorp/account/application/AuthenticationServiceTest.java`
- [X] T098 [P] Add failing tests for the callback handlers: `access_denied` → `/login?social=cancelled`, provider error or `state` mismatch → `/login?social=error`, no `code`/`state` in any redirect, and every non-`LOGGED_IN` outcome leaving the session unauthenticated so `/my-records/**` and `/auth/me` answer 401, in `backend/src/test/java/com/ssafy/thorp/account/presentation/SocialLoginCallbackTest.java`
- [X] T068 Add `SocialProvider`, `SocialAccount`, its repository port, JPA entity, mapper, and nullable `passwordHash` in `backend/src/main/java/com/ssafy/thorp/account/domain/model/`, `backend/src/main/java/com/ssafy/thorp/account/application/repository/`, and `backend/src/main/java/com/ssafy/thorp/account/infrastructure/persistence/` after T062
- [X] T069 Add `SocialProfile`, the `SocialProfileExtractor` port, and `SocialLoginService.resolve` to satisfy T063 in `backend/src/main/java/com/ssafy/thorp/account/application/`
- [X] T070 Add the session-backed `PendingSocialLogin` store and social verification codes reusing `VerificationMailSender` to satisfy T064 in `backend/src/main/java/com/ssafy/thorp/account/application/service/PendingSocialLoginService.java` and `backend/src/main/java/com/ssafy/thorp/account/infrastructure/session/`
- [X] T071 Add `SocialSignupService` and `SocialLinkService` to satisfy T065 in `backend/src/main/java/com/ssafy/thorp/account/application/service/`
- [X] T072 Add social error codes and `SocialAuthController` (`providers`, `pending` GET/DELETE, pending email verifications, `signup`, `link`) to satisfy T066 in `backend/src/main/java/com/ssafy/thorp/common/exception/ErrorCode.java` and `backend/src/main/java/com/ssafy/thorp/account/presentation/controller/SocialAuthController.java`
- [X] T073 Wire `oauth2Login()` (success handler replaces the stored `OAuth2AuthenticationToken` with the `AuthenticatedPrincipal` authentication on `LOGGED_IN` and saves an empty context otherwise, to satisfy T098) with `/auth/oauth2/authorization` and `/auth/oauth2/callback/*` base URIs, outcome success handler, cancel/error failure handler, non-persisting authorized-client repository, `reprompt`/`intent=link` request resolver, and `server.forward-headers-strategy=framework` plus `THORP_PUBLIC_BASE_URL`-based redirect URIs in `backend/src/main/java/com/ssafy/thorp/common/config/SecurityConfig.java`, `backend/src/main/resources/application.yml`, and `backend/src/main/java/com/ssafy/thorp/account/infrastructure/oauth/`
- [X] T074 Reject password-less accounts before the password comparison (no encoder warning log) in `backend/src/main/java/com/ssafy/thorp/account/application/service/AuthenticationService.java` to satisfy T067

**Checkpoint**: 가짜 `SocialProfile`로 모든 판정·가입·연결이 테스트에서 동작한다. 실제 제공자는 아직 없다.

## Phase 10: Kakao (BE)

- [X] T075 [P] [US6] Add failing extractor tests for Kakao `id`, `kakao_account.email`, `is_email_verified` true/false, missing email, and nickname length in `backend/src/test/java/com/ssafy/thorp/account/infrastructure/oauth/KakaoProfileExtractorTest.java`
- [X] T076 [US6] Register the Kakao provider (authorization, token, user-info URIs, `client_secret_post`, scopes `account_email profile_nickname`) from environment variables in `backend/src/main/resources/application.yml` and implement `KakaoProfileExtractor` to satisfy T075
- [X] T077 [US6] Add the Kakao `reprompt` parameter (`scope=account_email`) to the request resolver with a test, and record the business-app consent setting in `specs/014-user-auth/quickstart.md`

## Phase 11: Google (BE)

- [X] T078 [P] [US6] Add failing extractor tests for Google `sub`, `email`, `email_verified` true/false, and `name` in `backend/src/test/java/com/ssafy/thorp/account/infrastructure/oauth/GoogleProfileExtractorTest.java`
- [X] T079 [US6] Register Google (OIDC, scopes `openid email profile`) from environment variables in `backend/src/main/resources/application.yml` and implement `GoogleProfileExtractor` to satisfy T078
- [X] T080 [US6] Add the Google `reprompt` parameter (`prompt=consent`) to the request resolver with a test

## Phase 12: Naver (BE)

- [ ] T081 [P] [US6] Add failing extractor tests for Naver `response.id`, `response.email`, missing email, `nickname`, and `emailVerified=UNKNOWN` in `backend/src/test/java/com/ssafy/thorp/account/infrastructure/oauth/NaverProfileExtractorTest.java`
- [ ] T082 [US6] Register the Naver provider (authorization, token, user-info URIs, `user-name-attribute=response`) from environment variables with `client-authentication-method: client_secret_post` in `backend/src/main/resources/application.yml` and implement `NaverProfileExtractor` to satisfy T081
- [ ] T083 [US6] Add the Naver `reprompt` parameter (`auth_type=reprompt`) to the request resolver with a test, and confirm Naver always routes through `EMAIL_VERIFICATION`

**Checkpoint**: 세 제공자 모두 판정 서비스에 같은 `SocialProfile`을 넘긴다.

## Phase 13: Social Screens (FE)

- [ ] T084 [P] [US6] Add social types, error codes, and API functions from `contracts/auth-api.yaml` in `frontend/src/types/auth.ts` and `frontend/src/api/socialAuth.ts`
- [ ] T085 [P] [US6] Add failing tests for provider buttons (only enabled providers, full-page navigation, return target kept) and `/login?social=cancelled|error` notices in `frontend/src/components/auth/SocialLoginButtons.test.tsx` and `frontend/src/pages/LoginPage.test.tsx`
- [ ] T086 [P] [US6] Add failing tests for `/auth/social/complete`, `/signup/social` (read-only email, prefilled nickname, `SOCIAL_LINK_REQUIRED` → link screen, duplicate submit), `/auth/social/verify-email`, and expired pending state in `frontend/src/pages/Social*.test.tsx`
- [ ] T087 [P] [US7] Add failing tests for `/auth/social/link` (masked email, each method, wrong password/code, cancel), `/auth/social/email-required` (three actions, reprompt link), and `/auth/social/already-linked` in `frontend/src/pages/Social*.test.tsx`
- [ ] T088 [US6] Implement `SocialLoginButtons` following each provider's brand guide and add it to `frontend/src/pages/LoginPage.tsx` and `frontend/src/pages/SignupPage.tsx` to satisfy T085
- [ ] T089 [US6] Implement `SocialCompletePage`, `SocialSignupPage`, and `SocialVerifyEmailPage` in `frontend/src/pages/` to satisfy T086
- [ ] T090 [US7] Implement `SocialLinkPage`, `SocialEmailRequiredPage`, and `SocialAlreadyLinkedPage` in `frontend/src/pages/` to satisfy T087
- [ ] T091 Add `/auth/social` to the auth paths in `frontend/src/auth/returnTarget.ts`, register the social routes, apply `AuthEntryGuard` (authenticated → `/home`) to the social entry routes except `/auth/social/complete`, and refresh CSRF after social signup/link in `frontend/src/App.tsx` and `frontend/src/auth/AuthProvider.tsx`
- [ ] T092 Check status announcements, focus on first invalid input, keyboard-only completion, and 360px layout for every social screen

## Phase 14: Social Verification

- [ ] T093 [P] Run and record FE lint, tests, and build in `specs/014-user-auth/verification/frontend.md` under a 2026-10 social section
- [ ] T094 [P] Run and record BE spotless and tests in `specs/014-user-auth/verification/backend.md` under a 2026-10 social section
- [ ] T095 Validate the planned social operations against `contracts/auth-api.yaml` and switch them to `implemented` in the yaml and `contracts/README.md`
- [ ] T096 Execute quickstart Scenarios F–H with real Kakao, Google, and Naver test accounts and record evidence for SC-012~SC-015 in `specs/014-user-auth/verification/social.md`
- [ ] T097 Update the Social Login Extension status and deviations in `specs/014-user-auth/plan.md`

## Social Dependencies

- T061·T062 → T068 → T069 → T070 → T071 → T072·T073. T073은 T098을 만족해야 한다. T074는 T067 뒤 언제든.
- Phase 10~12는 Phase 9가 끝난 뒤 서로 독립이다. 순서는 카카오 → 구글 → 네이버로 둔다.
- Phase 13은 계약(T084)만 있으면 테스트를 먼저 쓸 수 있고, 화면 연결 확인은 Phase 9와 제공자 하나 이상이 끝난 뒤 한다.
- T096은 세 제공자의 개발자 앱 등록과 자격 증명이 있어야 한다.

## Social Commit Units

사용자 결정(2026-10-01)에 따라 소셜 확장은 브랜치 `feat/Oauth_create` 하나에서 하고, 아래 단위로 커밋을 나눈다.
한 커밋 안에서 테스트와 구현을 함께 넣는다. constitution "작업 묶음" 절의 크기 조건에 맞추려고 MR은 둘로 나눈다.
**MR-BE**: C1~C5(문서와 BE). **MR-FE**: C6~C7(FE와 검증 기록). MR-BE를 먼저 병합한다.

| Commit | 범위 | Tasks |
|---|---|---|
| C1 `docs(be)` | spec·plan·research·data-model·contracts·quickstart·tasks 동기화 | (문서) |
| C2 `feat(be)` | 소셜 공통 기반 | T061–T065, T067–T074 |
| (C3에 포함) | 콜백·계약 통합 테스트. 실제 콜백 흐름은 제공자 등록이 하나 있어야 태울 수 있다 | T066, T098 |
| C3 `feat(be)` | 카카오 | T075–T077 |
| C4 `feat(be)` | 구글 | T078–T080 |
| C5 `feat(be)` | 네이버 | T081–T083 |
| C6 `feat(fe)` | 소셜 화면 | T084–T092 |
| C7 `docs(be)` | 검증 기록과 상태 갱신 | T093–T097 |

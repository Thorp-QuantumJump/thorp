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
- [ ] T003 [P] Replace crypto-only security dependency with Spring Security starter and add Spring Session JDBC in `backend/build.gradle`
- [ ] T004 [P] Add PostgreSQL Spring Session tables and indexes without changing existing user data in `backend/src/main/resources/db/migration/V3__spring_session.sql`
- [ ] T005 Configure 30-minute inactive session timeout and `THORP_SESSION` cookie defaults per environment in `backend/src/main/resources/application.yml`, `backend/src/main/resources/application-local.yml`, and `backend/src/main/resources/application-prod.yml`

**Checkpoint**: FE component tests and BE session-backed security code can be implemented.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 인증 사용자 스토리가 공유하는 보안 경계, 오류 계약, FE 타입과 요청 기반을 만든다.

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 사용자 스토리 구현을 병합하지 않는다.

- [ ] T006 [P] Add failing contract tests for the common authentication error envelope, CSRF 403, and sanitized 500 responses in `backend/src/test/java/com/ssafy/thorp/account/presentation/AuthErrorContractTest.java`
- [ ] T007 [P] Add failing integration tests for public routes, protected authentication routes, CSRF bootstrap, cookie attributes, and session-id rotation in `backend/src/test/java/com/ssafy/thorp/account/security/AuthSecurityIntegrationTest.java`
- [x] T008 [P] Define auth request, response, user, state, and stable error-code types from `contracts/auth-api.yaml` in `frontend/src/types/auth.ts`
- [ ] T009 Add stable account/auth error codes and security exception envelope handling in `backend/src/main/java/com/ssafy/thorp/common/exception/ErrorCode.java` and `backend/src/main/java/com/ssafy/thorp/common/web/advice/GlobalExceptionHandler.java` to satisfy T006
- [ ] T010 Add Spring Security session persistence, session-fixation protection, CSRF cookie repository, and JSON authentication handlers in `backend/src/main/java/com/ssafy/thorp/common/config/SecurityConfig.java` to satisfy T007
- [x] T011 Extend Axios credentials, XSRF cookie/header names, CSRF bootstrap, and normalized API/network error handling in `frontend/src/api/client.ts`
- [x] T012 [P] Add reusable auth API and provider test fixtures without storing credentials in `frontend/src/test/authFixtures.ts` and `frontend/src/test/renderWithRouter.tsx`

**Checkpoint**: Foundation ready. US1 and the BE/FE portions of later stories can proceed according to their dependencies.

---

## Phase 3: User Story 1 - 새 사용자가 계정을 만든다 (Priority: P1) 🎯 MVP

**Goal**: 이메일 소유 확인 후 계정을 만들고 가입 이메일만 채워진 로그인 화면으로 이동한다.

**Independent Test**: 신규 이메일의 발송·확인·가입 성공과 형식 오류, 중복 이메일·전화번호, 인증 요청 없음·잘못됨·만료·미확인을 각각 재현해 계정 중복 없이 다음 행동 안내를 확인한다.

### Tests for User Story 1

- [ ] T013 [P] [US1] Add failing service tests for canonical email use, phone validation, verification lifecycle, and detailed account error codes in `backend/src/test/java/com/ssafy/thorp/account/application/SignupAndEmailVerificationServiceTest.java`
- [ ] T014 [P] [US1] Add failing controller tests for the three existing account operations, internal proxy paths, CSRF, and contract responses in `backend/src/test/java/com/ssafy/thorp/account/presentation/AccountControllerContractTest.java`
- [x] T015 [P] [US1] Add failing component tests for validation, send/confirm/signup pending states, duplicate submission, accessible errors, and email-only handoff in `frontend/src/pages/SignupPage.test.tsx`
- [x] T016 [P] [US1] Add failing auth client tests for email verification and signup success/error envelopes in `frontend/src/api/auth.test.ts`

### Implementation for User Story 1

- [ ] T017 [US1] Implement trim-and-lowercase email normalization at the Backend trust boundary in `backend/src/main/java/com/ssafy/thorp/account/application/EmailNormalizer.java`, `backend/src/main/java/com/ssafy/thorp/account/application/service/SignupService.java`, and `backend/src/main/java/com/ssafy/thorp/account/application/service/EmailVerificationService.java`
- [ ] T018 [US1] Normalize Account controller mappings to internal `/accounts`, return the stable rejection codes, and preserve 204/201 success contracts in `backend/src/main/java/com/ssafy/thorp/account/presentation/controller/SignupController.java` and `backend/src/main/java/com/ssafy/thorp/account/presentation/controller/EmailVerificationController.java`
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

- [ ] T023 [P] [US2] Add failing repository and service tests for normalized-email lookup, password verification, inactive-account concealment, and last-login update in `backend/src/test/java/com/ssafy/thorp/account/application/AuthenticationServiceTest.java`
- [ ] T024 [P] [US2] Add failing MockMvc and PostgreSQL session tests for login and current-user contracts in `backend/src/test/java/com/ssafy/thorp/account/presentation/AuthenticationControllerIntegrationTest.java`
- [x] T025 [P] [US2] Add failing tests for checking/authenticated/anonymous/error transitions and bootstrap deduplication in `frontend/src/auth/AuthProvider.test.tsx`
- [x] T026 [P] [US2] Add failing login page tests for canonical email, generic credential failure, pending submission, signup-email prefill, and authenticated redirect in `frontend/src/pages/LoginPage.test.tsx`

### Implementation for User Story 2

- [ ] T027 [US2] Add normalized-email lookup and authenticated account projection in `backend/src/main/java/com/ssafy/thorp/account/application/repository/UserRepository.java`, `backend/src/main/java/com/ssafy/thorp/account/infrastructure/persistence/UserRepositoryImpl.java`, and `backend/src/main/java/com/ssafy/thorp/account/application/result/AuthenticatedUserResult.java`
- [ ] T028 [US2] Implement credential validation, account-state concealment, last-login update, and explicit SecurityContext persistence in `backend/src/main/java/com/ssafy/thorp/account/application/service/AuthenticationService.java` and `backend/src/main/java/com/ssafy/thorp/account/application/command/LoginCommand.java`
- [ ] T029 [US2] Implement login, current-user, and CSRF endpoints plus request/response DTOs in `backend/src/main/java/com/ssafy/thorp/account/presentation/controller/AuthenticationController.java`, `backend/src/main/java/com/ssafy/thorp/account/presentation/request/LoginRequest.java`, and `backend/src/main/java/com/ssafy/thorp/account/presentation/response/AuthenticatedUserResponse.java`
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

- [ ] T034 [P] [US3] Add failing unit tests for same-origin return-target validation, one-time consumption, reload persistence, and unsafe fallback in `frontend/src/auth/returnTarget.test.ts`
- [ ] T035 [P] [US3] Add failing router tests for checking, anonymous, authenticated, expired, and invalid-return-target access in `frontend/src/auth/ProtectedRoute.test.tsx`
- [ ] T036 [P] [US3] Add failing navigation tests proving public routes remain available and only the records entry is protected in `frontend/src/App.auth.test.tsx`

### Implementation for User Story 3

- [ ] T037 [US3] Implement validated tab-scoped return-target storage and one-time consumption in `frontend/src/auth/returnTarget.ts` to satisfy T034
- [ ] T038 [US3] Implement the records-only authentication boundary and loading/login-required states in `frontend/src/auth/ProtectedRoute.tsx` to satisfy T035
- [ ] T039 [US3] Add an authentication-only `/records/*` entry boundary without implementing record content in `frontend/src/pages/MyRecordsEntryPage.tsx` and `frontend/src/pages/MyRecordsEntryPage.module.css`
- [ ] T040 [US3] Wire the protected records route, post-login return, and public route exemptions in `frontend/src/App.tsx` after T037–T039
- [ ] T041 [US3] Add the secondary “내 기록” navigation entry and authenticated/anonymous destination behavior in `frontend/src/components/navigation/AppHeader.tsx` and `frontend/src/components/navigation/AppHeader.module.css`

**Checkpoint**: US3 protects only the records boundary and remains independent from the record list implementation in spec 006.

---

## Phase 6: User Story 4 - 사용자가 로그아웃한다 (Priority: P2)

**Goal**: 현재 서버 세션을 종료하고 응답을 확인하지 못하면 보호 정보를 숨긴 `logoutUnconfirmed` 상태를 유지한다.

**Independent Test**: 로그인 상태의 로그아웃 204, 이미 비로그인인 로그아웃 204, 네트워크 실패, 재시도 및 `/auth/me` 재확인을 각각 재현해 이전 사용자 정보가 노출되지 않는지 확인한다.

### Tests for User Story 4

- [ ] T042 [P] [US4] Add failing integration tests for current-session invalidation, idempotent anonymous logout, cookie expiry, and post-logout 401 in `backend/src/test/java/com/ssafy/thorp/account/presentation/LogoutIntegrationTest.java`
- [ ] T043 [P] [US4] Add failing provider and header tests for logout start, 204 completion, network uncertainty, retry, recheck, and immediate data removal in `frontend/src/auth/LogoutFlow.test.tsx`

### Implementation for User Story 4

- [ ] T044 [US4] Implement idempotent current-session logout and JSON security behavior in `backend/src/main/java/com/ssafy/thorp/account/presentation/controller/AuthenticationController.java` and `backend/src/main/java/com/ssafy/thorp/common/config/SecurityConfig.java` to satisfy T042
- [ ] T045 [P] [US4] Add current-session logout operation to `frontend/src/api/auth.ts`
- [ ] T046 [US4] Implement `logoutUnconfirmed`, retry, and current-user recheck transitions with immediate credential-free state clearing in `frontend/src/auth/AuthProvider.tsx` after T043 and T045
- [ ] T047 [US4] Add accessible logout, retry, and status UI without blocking public navigation in `frontend/src/components/navigation/AppHeader.tsx` and `frontend/src/components/navigation/AppHeader.module.css`

**Checkpoint**: 로그아웃 성공과 미확인 복구 흐름을 독립적으로 검증할 수 있다.

---

## Phase 7: User Story 5 - 만료와 일시적인 실패에서 안전하게 이어간다 (Priority: P2)

**Goal**: 세션 만료·CSRF 오류·네트워크 실패·다중 탭·페이지 캐시 복원에서도 보호 정보를 숨기고 안전하게 이어간다.

**Independent Test**: 보호 API 401, CSRF 403, 현재 사용자 조회 실패, 탭 재활성화, 뒤로가기와 page cache 복원을 재현해 공개 기능은 유지되고 보호 정보는 재확인 전 노출되지 않는지 확인한다.

### Tests for User Story 5

- [ ] T048 [P] [US5] Add failing Backend tests for 30-minute inactivity expiry, CSRF refresh, session fixation, sanitized logs, and migration compatibility in `backend/src/test/java/com/ssafy/thorp/account/security/AuthSessionLifecycleIntegrationTest.java`
- [ ] T049 [P] [US5] Add failing FE tests for authenticated 401 expiry, CSRF refresh without automatic mutation replay, stale-response rejection, and public-page availability in `frontend/src/auth/AuthRecovery.test.tsx`
- [ ] T050 [P] [US5] Add failing browser-state tests for tab reactivation, protected-entry recheck, back-forward cache restoration, and stale protected-data clearing in `frontend/src/auth/AuthRevalidation.test.tsx`
- [ ] T051 [P] [US5] Add failing accessibility and 360px responsive tests for pending, error, expired, and logout-unconfirmed states in `frontend/src/pages/AuthAccessibility.test.tsx`

### Implementation for User Story 5

- [ ] T052 [US5] Complete session expiry, CSRF refresh, safe security logging, and production cookie behavior in `backend/src/main/java/com/ssafy/thorp/common/config/SecurityConfig.java`, `backend/src/main/resources/application.yml`, and `backend/src/main/resources/application-prod.yml` to satisfy T048
- [ ] T053 [US5] Implement request identity/abort handling, authenticated 401 expiry, one-time CSRF refresh, and user-driven retry without automatic mutation replay in `frontend/src/api/client.ts` and `frontend/src/auth/AuthProvider.tsx`
- [ ] T054 [US5] Revalidate authentication on tab activation, protected-route entry, and page-cache restoration while leaving public screens available in `frontend/src/auth/AuthProvider.tsx` and `frontend/src/auth/ProtectedRoute.tsx`
- [ ] T055 [US5] Complete focus movement, live status announcements, reduced-motion-safe feedback, and 360px auth layouts in `frontend/src/components/auth/AuthStatus.tsx`, `frontend/src/pages/LoginPage.module.css`, and `frontend/src/pages/SignupPage.module.css`

**Checkpoint**: 모든 인증 상태와 복구 흐름이 독립적으로 검증 가능하다.

---

## Phase 8: Polish & Cross-Cutting Verification

**Purpose**: 전체 계약·회귀·문서 증거를 확인하고 MR 단위로 마무리한다.

- [ ] T056 [P] Run and record FE lint, unit/component tests, and production build results in `specs/014-user-auth/verification/frontend.md`
- [ ] T057 [P] Run and record BE spotless and PostgreSQL-backed test results in `specs/014-user-auth/verification/backend.md`
- [ ] T058 Validate all seven operations and success/error examples against `specs/014-user-auth/contracts/auth-api.yaml` and record results in `specs/014-user-auth/verification/contract.md`
- [ ] T059 Execute the signup→login→restore→records→expiry/logout scenarios and record desktop/360px evidence in `specs/014-user-auth/verification/integration.md`
- [ ] T060 Update implementation status, deviations, and the deferred rate-limit security follow-up in `specs/014-user-auth/plan.md`, `specs/014-user-auth/contracts/README.md`, and `specs/014-user-auth/quickstart.md`

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

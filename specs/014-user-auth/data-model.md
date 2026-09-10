# Data Model: 사용자 인증

## 개요

사용자 계정과 이메일 인증은 기존 모델을 재사용한다. 로그인 상태는 Spring Session JDBC가 PostgreSQL에 보관하며 FE에는 현재 사용자 요약과 인증 상태만 존재한다. 비밀번호 원문, 인증번호 원문, session id는 FE 상태나 브라우저 저장소에 보관하지 않는다.

## Server Entities

### UserAccount (기존 `users`)

| Field | Type | 규칙 |
|---|---|---|
| id | Long | DB 생성 PK |
| email | String | 필수, 유효한 이메일, 고유 |
| passwordHash | String | 필수, `PasswordEncoder` 결과만 저장 |
| nickname | String | 필수, 가입 요청 기준 2~20자 |
| phoneNumber | String | 필수, `^01[016789]\d{7,8}$`, 고유 |
| profileImageUrl | String? | 선택 |
| status | UserStatus | `ACTIVE`, `SUSPENDED`, `WITHDRAWN`; 기존 enum의 `PENDING_VERIFICATION`은 현재 가입 흐름에서 생성되지 않음 |
| lastLoginAt | Instant? | 로그인 성공 시 갱신 |
| createdAt | Instant | 생성 시각 |
| updatedAt | Instant | 수정 시각 |
| withdrawnAt | Instant? | 탈퇴 시각, 이번 범위에서는 변경하지 않음 |

**Identity rule**: 이메일과 전화번호는 각각 고유하다. 이메일 정규형은 앞뒤 공백을 제거하고 소문자로 변환한 값이며 이메일 인증·중복 조회·로그인·저장에 같은 값을 사용한다. FE는 편의를 위해 먼저 정규화하지만 BE가 최종 판정 전에 같은 정규화를 반드시 반복한다. 전화번호는 `^01[016789]\d{7,8}$`에 맞는 하이픈·공백 없는 숫자만 사용한다.

**Security rule**: 로그인 실패 응답은 사용자 없음, 비밀번호 불일치, 비활성 계정을 외부에서 구분하지 않는다.

### EmailVerification (기존 `email_verifications`)

| Field | Type | 규칙 |
|---|---|---|
| id | Long | DB 생성 PK |
| email | String | 인증 대상 이메일 |
| verificationCodeHash | String | 6자리 원문이 아닌 hash |
| expiresAt | Instant | 발급 후 3분 |
| verifiedAt | Instant? | 확인 성공 시 최초 한 번 설정 |
| createdAt | Instant | 발급 시각 |

**Lifecycle**:

```text
none ──request──> issued ──correct code before expiry──> verified ──signup──> deleted
                    ├──resend──> old deleted + new issued
                    ├──wrong code──> issued
                    └──time elapsed──> expired
```

확인 완료 요청을 다시 확인하는 동작은 성공으로 처리한다. 가입 성공 후 같은 인증 기록은 재사용하지 못하게 삭제한다.

### AuthenticationSession (신규, Spring Session JDBC)

논리 모델이며 물리 필드와 index는 Spring Session PostgreSQL schema를 따른다.

| Field | Type | 규칙 |
|---|---|---|
| primaryId | String | 내부 PK, 외부 노출 금지 |
| sessionId | String | cookie가 참조하는 opaque id, 로그에 원문 기록 금지 |
| userId | Long | 인증된 UserAccount 식별자와 논리적으로 연결 |
| creationTime | Instant | 세션 생성 시각 |
| lastAccessedTime | Instant | 유효 요청 시 갱신 |
| maxInactiveInterval | Duration | 기본 30분, 서버 설정 기준 |
| expiryTime | Instant | 서버가 계산하는 만료 기준 |
| securityContext | Serialized attribute | 인증 principal; 비밀번호·credentials 제외 |

**Relationship**: UserAccount 1 : N AuthenticationSession. 동시 로그인 제한은 이번 범위에서 추가하지 않는다.

**Lifecycle**:

```text
absent ──valid login──> active ──authenticated request──> active
 active ──idle timeout──> expired
 active ──logout confirmed──> invalidated
 active ──account unavailable──> invalidated/denied
```

로그인 성공 시 익명 세션에서 사용하던 식별자는 폐기·교체하고 새 세션 식별자에만 인증 상태를 연결한다.

## API Value Objects

### LoginInput

| Field | Type | Validation |
|---|---|---|
| email | String | 필수, 앞뒤 공백 제거·소문자 변환 후 이메일 형식과 서버 기준 적용 |
| password | String | 필수; 로그인 화면은 가입 규칙과 다른 추가 제약으로 요청을 막지 않음 |

비밀번호는 요청 직후 컴포넌트가 보유한 값 이외의 상태·URL·navigation state·storage에 복제하지 않는다.

### AuthenticatedUser

| Field | Type | Source |
|---|---|---|
| userId | Long | UserAccount.id |
| email | String | UserAccount.email |
| nickname | String | UserAccount.nickname |
| status | String | UserAccount.status, 정상 인증 응답은 `ACTIVE` |

전화번호, passwordHash, sessionId는 응답하지 않는다.

### ApiError

| Field | Type | Description |
|---|---|---|
| code | String | FE 분기용 안정된 오류 코드 |
| message | String | 사용자에게 표시 가능한 일반 메시지 |

주요 코드: `INVALID_INPUT`, `INVALID_CREDENTIALS`, `AUTHENTICATION_REQUIRED`, `CSRF_TOKEN_INVALID`, `INTERNAL_ERROR`.

## Frontend State

### AuthState

| State | User | 보호 화면 | 다음 동작 |
|---|---|---|---|
| checking | null | 숨김 | `/auth/me` 완료 대기 |
| authenticated | AuthenticatedUser | 허용 | 정상 이용 |
| anonymous | null | 차단 | 로그인 안내 |
| expired | null | 차단 | 복귀 위치 보존 후 재로그인 |
| error | null | 차단 | 공개 기능 허용, 상태 재확인 제공 |
| logoutUnconfirmed | null | 차단 | 로그아웃 재시도 또는 현재 상태 확인 |

### AuthState transitions

```text
boot ──> checking
checking ──200──> authenticated
checking ──401──> anonymous
checking ──network──> error

anonymous/error/expired ──login 200──> authenticated
authenticated ──API 401──> expired
authenticated ──logout start──> logoutUnconfirmed
logoutUnconfirmed ──logout 204 or /me 401──> anonymous
logoutUnconfirmed ──/me 200──> authenticated
logoutUnconfirmed ──network──> logoutUnconfirmed
```

늦게 도착한 과거 요청이 최신 state를 덮어쓰지 않도록 각 mutation은 현재 요청 식별자 또는 abort signal로 유효성을 확인한다.

`/auth/me` 재확인은 앱 최초 진입, 숨겨졌던 탭의 재활성화, `내 기록` 진입 직전에 수행한다. 로그아웃 시작·API 401·페이지 캐시 복원 뒤에는 메모리의 사용자와 보호 데이터를 먼저 제거하며, 확인 중에는 보호 화면을 복원하지 않는다. `authenticated` 상태에서 로그인·회원가입 route에 접근하면 `/home`으로 이동한다.

### SignupDraft

| Field | Storage | Rule |
|---|---|---|
| email | component state | 앞뒤 공백 제거·소문자 변환; 가입 성공 뒤 로그인 navigation state로 한 번 전달 가능 |
| password | component state | 화면 이탈·성공 시 폐기, 다른 화면으로 전달 금지 |
| nickname | component state | 가입 요청에만 사용 |
| phoneNumber | component state | 숫자만 서버로 전송 |
| verificationCode | component state | 6자리, 확인 완료·이탈 시 폐기 |
| verificationState | component state | idle/sending/sent/verifying/verified/error/expired |

### ReturnTarget

| Field | Type | Rule |
|---|---|---|
| path | String | same-origin 내부 path만 허용, 기본 `/home` |
| search | String | 선택, path와 함께 검증 |
| hash | String | 선택 |
| consumed | Boolean | 로그인 성공 시 한 번 사용 후 제거 |

`sessionStorage`에는 결합된 내부 상대 경로만 저장한다. scheme/host를 포함한 URL, `//` prefix, 로그인·회원가입 자체 경로는 저장하지 않는다.

## Data ownership and privacy

- Backend가 계정·세션 유효성의 기준 저장소다.
- FE cache는 `AuthenticatedUser`의 비민감 요약만 메모리에 보유하고 로그아웃 시작 시 즉시 제거한다.
- 브라우저가 보유하는 session cookie는 JavaScript에서 읽을 수 없다.
- CSRF token은 비밀 인증정보가 아니며 header 전송을 위해 cookie에서 읽되 session id와 분리한다.
- 인증 실패 로그에 비밀번호, 인증번호, session id, 전체 cookie/header를 남기지 않는다.

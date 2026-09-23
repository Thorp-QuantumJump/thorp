# Research: 사용자 인증

## 조사 범위

- Frontend: React 19, React Router의 선언형 라우팅, 공용 Axios client, 기존 Node test 구조
- Backend: account 계층, 회원가입·이메일 인증 controller/service/repository, 공통 응답·예외, PostgreSQL·Flyway 설정
- 운영 경계: Vite와 nginx의 `/api` proxy 규칙
- 공식 참고: Spring Security 인증 유지·CSRF, Spring Session JDBC, React Router navigation, Axios XSRF 설정

## Decision 1: 서버 관리 세션을 사용한다

**Decision**: Spring Security의 `SecurityContext`를 HTTP session에 저장하고 Spring Session JDBC로 PostgreSQL에 보존한다. 브라우저에는 opaque session cookie만 전달한다.

**Rationale**:

- 현재 서비스는 nginx 뒤에서 같은 출처로 React와 Spring Boot가 통신하고 PostgreSQL을 이미 운영한다.
- 세션 식별자를 `HttpOnly` 쿠키로 두면 FE JavaScript와 `localStorage`에 인증 토큰을 노출하지 않는다.
- Spring Security는 `SecurityContextRepository`를 통해 후속 요청의 인증을 복원하며, Spring Session JDBC는 기존 relational database에 `HttpSession`을 보관한다.
- 로그아웃 시 서버 세션을 실제로 무효화할 수 있어 spec의 “서버에서 완료를 확인한 경우에만 완료 표시”와 맞는다.

**Alternatives considered**:

- JWT access token을 `localStorage`에 저장: XSS 노출 위험과 로그아웃 무효화 문제 때문에 제외했다.
- JWT를 `HttpOnly` cookie에 저장: refresh/rotation/revocation 저장소가 추가되고 현재 단일 서비스 범위에 비해 복잡하다.
- 기본 인메모리 `HttpSession`: 서버 재시작과 다중 인스턴스에서 인증 상태가 끊겨 PostgreSQL 세션보다 약하다.

**Sources**:

- [Spring Security — Persisting Authentication](https://docs.spring.io/spring-security/reference/servlet/authentication/persistence.html)
- [Spring Session — JDBC](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)

## Decision 2: session cookie는 비영구이며 서버 비활성 만료를 따른다

**Decision**: cookie에는 `HttpOnly`, 운영 `Secure`, `SameSite=Lax`, `Path=/`를 적용하고 영구 `Max-Age`를 두지 않는다. 서버 비활성 만료 기본값은 30분으로 설정 가능하게 두며 FE는 만료 시각을 추정하지 않는다.

**Rationale**:

- clarify에서 별도 로그인 유지 선택지를 제공하지 않기로 확정했다.
- 비영구 cookie는 remember-me 기능을 만들지 않으면서 같은 브라우저 세션의 새로고침·재진입을 지원한다.
- 서버가 세션 유효성의 기준 저장소가 되어 FE와 실제 만료 상태가 어긋나지 않는다.

**Alternatives considered**:

- 영구 cookie: 명시적인 로그인 유지 선택이 없는 현재 범위보다 긴 인증을 만들 수 있어 제외했다.
- FE 타이머로 만료 예측: 네트워크·서버 연장 상태와 어긋나므로 제외했다.

## Decision 3: cookie 인증과 함께 SPA CSRF 보호를 유지한다

**Decision**: Spring Security CSRF를 활성화하고 `/api/auth/csrf`가 `XSRF-TOKEN` cookie를 준비한다. Axios는 unsafe method에 `X-XSRF-TOKEN` header를 보낸다. 인증 성공·로그아웃 성공 뒤에는 다음 unsafe 요청 전에 토큰을 다시 준비한다.

**Rationale**:

- 브라우저가 session cookie를 자동 전송하므로 POST/DELETE 요청은 CSRF 방어가 필요하다.
- Spring Security는 unsafe method의 CSRF 보호와 SPA용 cookie token repository를 지원한다.
- Axios의 표준 XSRF cookie/header 이름을 그대로 사용하면 별도 토큰 저장소가 필요 없다.

**Alternatives considered**:

- CSRF 비활성화 + SameSite만 사용: 방어 계층이 줄고 Spring Security 기본 보호를 버리므로 제외했다.
- 인증 endpoint만 CSRF 제외: 로그인·이메일 발송 같은 상태 변경 요청을 예외로 만들 이유가 부족하다.

**Sources**:

- [Spring Security — CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Axios — Request Config](https://axios-http.com/docs/req_config)

## Decision 4: 외부 `/api`와 Backend 내부 경로를 분리한다

**Decision**: 사용자·FE가 보는 계약은 `/api/accounts/*`, `/api/auth/*`로 정의하고, Backend controller는 proxy가 `/api`를 제거한 뒤 받는 `/accounts/*`, `/auth/*`를 사용한다.

**Rationale**:

- `frontend/vite.config.ts`와 `infra/nginx/templates/default.conf.template` 모두 외부 `/api` 접두사를 제거한다.
- Market controller는 이미 내부 `/markets`를 사용하지만 현재 Account controller는 `/api/accounts`를 사용한다. 이 상태에서 외부 `/api/accounts`는 내부 `/accounts`가 되어 account endpoint와 맞지 않는다.
- proxy 규칙을 바꾸면 기존 market/backtest API 전체에 영향을 주므로 account mapping을 기존 운영 규칙에 맞추는 변경이 가장 작다.

**Alternatives considered**:

- proxy가 `/api`를 유지하도록 변경: 이미 동작하는 다른 API의 controller 경로를 모두 바꿔야 하므로 제외했다.
- FE에서 `/api/api/accounts` 호출: 환경별 경로가 드러나고 공개 계약이 일관되지 않아 제외했다.

## Decision 5: 기존 account 응답 envelope를 인증 계약에 사용한다

**Decision**: 인증 API는 `{ "success": boolean, "data": ..., "error": ... }` 구조를 사용한다. 인증 전용 오류로 `INVALID_CREDENTIALS`, `AUTHENTICATION_REQUIRED`, `CSRF_TOKEN_INVALID`를 추가한다. 가입·이메일 인증은 사용자 조치가 다른 실패를 `DUPLICATE_EMAIL`, `DUPLICATE_PHONE`, `EMAIL_VERIFICATION_REQUIRED`, `EMAIL_VERIFICATION_NOT_FOUND`, `EMAIL_VERIFICATION_INVALID`, `EMAIL_VERIFICATION_EXPIRED`로 구분하고, 예상하지 못한 오류는 기존 `INTERNAL_ERROR`를 사용한다.

**Rationale**:

- 기존 Signup controller와 `GlobalExceptionHandler`가 `ApiResponse<T>`를 사용한다.
- 로그인 실패를 이메일 없음·비밀번호 불일치·계정 상태별로 외부에 구분하지 않으면 계정 존재 여부 노출을 줄인다.
- 현재 `INVALID_INPUT`만으로는 폼 오류와 인증 실패, 로그인 필요 상태를 FE가 안정적으로 구분하기 어렵다.
- 기존 Account 구현은 사용자 조치가 다른 가입·인증 실패도 모두 `INVALID_INPUT`으로 반환하므로 새 계약에 맞춘 BE 변경이 필요하다.

**Alternatives considered**:

- HTTP status만 사용: 사용자 조치와 FE 상태 전이를 안정적으로 구분하기 어렵다.
- 서버 메시지 문자열을 분기 키로 사용: 문구 변경에 취약하므로 제외했다.
- Market의 별도 `HttpApiResponse` 형태 사용: account의 현재 계약과 맞지 않아 이번 범위에서 통합하지 않는다.

## Decision 6: FE 인증 상태는 단일 Provider가 소유한다

**Decision**: `AuthProvider`가 앱 시작 시 CSRF 준비와 현재 사용자 조회를 수행하고 인증 상태·사용자·로그인·로그아웃·재확인 동작을 제공한다. 보호 경로와 Header는 이 상태를 소비한다.

**Rationale**:

- 현재 앱은 React Router 선언형 mode와 route-level lazy loading을 사용한다. Provider를 Router 내부·Routes 외부에 두면 모든 route가 동일 상태를 본다.
- 페이지마다 `/me`를 호출하거나 cookie 존재를 추측하는 중복을 막는다.
- 보호 화면은 `checking` 동안 렌더링하지 않고 공개 화면은 확인 실패에도 계속 이용할 수 있다.

**Alternatives considered**:

- 전역 상태 라이브러리 신규 도입: 인증 상태 하나를 위해 추가 의존성이 필요하지 않다.
- 각 페이지 local state: 상태 불일치와 중복 요청이 생겨 제외했다.

**Sources**:

- [React Router — Routing](https://reactrouter.com/start/declarative/routing)
- [React Router — Navigating](https://reactrouter.com/start/declarative/navigating)

## Decision 7: 복귀 위치는 검증된 내부 경로만 탭 단위로 보관한다

**Decision**: `pathname + search + hash`를 same-origin 내부 경로로 검증한 후 `sessionStorage`에 저장하고 로그인 성공 뒤 한 번 사용해 제거한다. `/`로 시작하지 않거나 `//`로 시작하는 값, 절대 URL은 기본 `/home`으로 바꾼다.

**Rationale**:

- Router navigation state만 사용하면 로그인 화면 새로고침 때 원래 목적지가 사라진다.
- 탭 단위 저장은 사용자의 목적을 보존하면서 다른 브라우저 세션까지 불필요하게 남기지 않는다.
- 세션 cookie·비밀번호·사용자 객체는 저장하지 않아 민감정보 노출을 만들지 않는다.

**Alternatives considered**:

- query string에 return URL 저장: 외부 URL 주입과 로그 노출 위험이 커진다.
- `localStorage`: 다른 탭·이후 브라우저 세션까지 남으므로 범위가 넓다.

## Decision 8: 인증 UI 테스트를 위해 Vitest와 Testing Library를 추가한다

**Decision**: 기존 순수 함수/API Node tests는 유지하고, DOM·키보드·라우팅 상태가 필요한 인증 tests에는 Vitest, jsdom, React Testing Library, user-event를 사용한다. Backend 계약은 MockMvc와 PostgreSQL Testcontainers로 검증한다.

**Rationale**:

- 현재 FE test command는 Node test만 사용해 React 폼의 초점, 오류 전달, 중복 제출, 보호 route를 검증하기 어렵다.
- Testing Library는 사용자 동작과 접근성 이름을 중심으로 검증할 수 있다.
- 실제 세션 저장과 DB schema는 운영과 같은 PostgreSQL 통합 test가 필요하다.

**Alternatives considered**:

- 수동 브라우저 확인만 수행: 회귀 검증과 Task 완료 증거가 약해 제외했다.
- 즉시 E2E framework까지 추가: 이번 기능에서는 component/router integration tests와 API integration tests로 핵심 계약을 검증할 수 있어 보류했다.

## Decision 9: 이메일 정규형은 양쪽에서 적용한다

**Decision**: 이메일은 앞뒤 공백을 제거하고 소문자로 변환한 값을 인증·중복 조회·로그인·저장에 사용한다. FE는 제출 전에 같은 변환을 적용하고 BE가 신뢰 경계에서 다시 적용한다. 전화번호는 현재 Backend 계약대로 하이픈·공백 없는 국내 휴대폰 숫자 형식만 허용한다.

**Rationale**:

- 현재 Backend는 이메일 원문을 그대로 조회·저장하므로 공백이나 대소문자 차이로 인증 기록·중복 조회·로그인이 어긋날 수 있다.
- FE만 정규화하면 다른 client가 규칙을 우회할 수 있으므로 BE가 기준 저장소의 정규형을 보장해야 한다.

**Alternatives considered**:

- 현재의 원문 비교 유지: 같은 사용자가 대소문자만 다른 계정을 만들거나 인증 기록을 찾지 못할 수 있어 제외했다.
- FE에서만 정규화: 서버 고유성 조건을 보장하지 못해 제외했다.

## 확인된 기존 기능과 부족한 기능

| 구분 | 상태 | 근거/후속 조치 |
|---|---|---|
| 회원가입 | 일부 보완 필요 | 이메일·비밀번호·닉네임·전화번호, 201 `ApiResponse<SignupResponse>` 재사용. 이메일 정규화와 사유별 오류 코드 추가 필요 |
| 인증번호 발송 | 구현됨 | 6자리·3분, 재발송 시 이전 기록 삭제, 204 응답 |
| 인증번호 확인 | 구현됨 | 잘못됨·만료·요청 없음 처리, 이미 확인된 요청은 idempotent, 204 응답 |
| 비밀번호 저장 | 구현됨 | `PasswordEncoder` hash 사용. 원문 저장 없음 |
| 로그인 | 없음 | 신규 세션 API·서비스·Security 설정 필요 |
| 현재 사용자 조회 | 없음 | 새로고침 복원과 보호 route 판정용 API 필요 |
| 로그아웃 | 없음 | 서버 세션 무효화 API 필요 |
| CSRF SPA 계약 | 없음 | token 발급·refresh와 공통 403 envelope 필요 |
| Account proxy 경로 | 불일치 | 내부 controller의 `/api` 접두사를 제거해야 함 |
| Account 테스트 | 없음 | 기존 가입·인증 회귀 test와 신규 auth 계약 test 필요 |

014 구현에 필요한 사용자 흐름과 FE/BE 계약은 위 결정으로 해소했다. 반복 로그인·인증번호 발송의 운영 제한 수치와 계정 잠금 정책은 근거 없이 확정하지 않고 별도 BE 보안 Task로 명시적으로 이관한다.

# Authentication API Contract

[auth-api.yaml](auth-api.yaml)은 브라우저와 nginx가 보는 **외부 경로**를 정의한다. 개발 Vite proxy와 운영 nginx는 `/api` 접두사를 제거한 뒤 Spring Boot에 전달한다.

## 구현 상태

| External API | Backend internal path | 상태 |
|---|---|---|
| `POST /api/accounts/email-verifications` | `POST /accounts/email-verifications` | 구현·계약 검증 완료 |
| `POST /api/accounts/email-verifications/confirm` | `POST /accounts/email-verifications/confirm` | 구현·계약 검증 완료 |
| `POST /api/accounts` | `POST /accounts` | 구현·계약 검증 완료 |
| `GET /api/auth/csrf` | `GET /auth/csrf` | 구현·계약 검증 완료 |
| `POST /api/auth/sessions` | `POST /auth/sessions` | 구현·계약 검증 완료 |
| `GET /api/auth/me` | `GET /auth/me` | 구현·계약 검증 완료 |
| `DELETE /api/auth/sessions/current` | `DELETE /auth/sessions/current` | 구현·계약 검증 완료 |

2026-09-16에 Vite proxy와 Backend 내부 경로, 성공·오류 응답을 대조했다. Account controller는
내부 `/accounts`, 인증 controller는 내부 `/auth`를 사용하며 외부 `/api`와 중복되지 않는다.
상세 결과는 [verification/contract.md](../verification/contract.md)에 있다.

## Cookie and CSRF

- session cookie: `THORP_SESSION`, `HttpOnly`, `Path=/`, `SameSite=Lax`, production `Secure`, persistent `Max-Age` 없음
- session timeout: 서버 비활성 30분 기본값, 환경 설정 가능
- CSRF cookie/header: `XSRF-TOKEN` / `X-XSRF-TOKEN`
- FE는 첫 unsafe 요청 전 CSRF를 준비하고 로그인·로그아웃 성공 후 다음 unsafe 요청 전에 다시 준비한다.
- 로그인 성공 시 기존 익명 session id를 교체하고 새 `THORP_SESSION`만 인증 상태에 연결한다.

## Input normalization

- 이메일은 FE와 BE 모두 앞뒤 공백을 제거하고 소문자로 변환한다. BE의 정규형이 이메일 인증·중복 조회·로그인·저장의 기준이다.
- 전화번호는 하이픈·공백 없는 국내 휴대폰 숫자 형식만 허용하며 자동으로 다른 국가 형식으로 변환하지 않는다.
- FE는 session cookie를 읽거나 `localStorage`에 인증 token을 저장하지 않는다.

## Error handling

- JSON 응답은 기존 account의 `ApiResponse<T>` 구조를 따른다.
- 로그인 정보 오류, 사용자 없음, 비활성 계정은 외부에 `INVALID_CREDENTIALS` 하나로 응답한다.
- 형식 오류는 `INVALID_INPUT`, 가입·이메일 인증의 사용자 조치가 다른 사유는 `DUPLICATE_EMAIL`, `DUPLICATE_PHONE`, `EMAIL_VERIFICATION_REQUIRED`, `EMAIL_VERIFICATION_NOT_FOUND`, `EMAIL_VERIFICATION_INVALID`, `EMAIL_VERIFICATION_EXPIRED`로 구분한다.
- `/auth/me`의 401은 `AUTHENTICATION_REQUIRED`다. FE는 이전 상태를 기준으로 최초 비로그인과 로그인 만료를 구분한다.
- CSRF 검증 실패는 403 `CSRF_TOKEN_INVALID` envelope로 변환한다.
- 모든 operation의 예상하지 못한 서버 실패는 500 `INTERNAL_ERROR` envelope로 변환한다.
- 네트워크 실패에는 서버 body가 없으므로 FE가 별도의 연결 오류로 처리한다.
- 로그아웃 DELETE는 이미 비로그인인 경우에도 204로 idempotent하게 처리한다. 응답 자체를 받지 못한 경우에만 FE가 `logoutUnconfirmed`를 유지한다.

## Social login (2026-10-01)

| External API | Backend internal path | 상태 |
|---|---|---|
| `GET /api/auth/social/providers` | `GET /auth/social/providers` | 구현·자동 검증 |
| `GET /api/auth/oauth2/authorization/{provider}` | `GET /auth/oauth2/authorization/{provider}` | 구현·자동 검증 (브라우저 전체 이동, Spring Security 처리) |
| `GET /api/auth/oauth2/callback/{provider}` | `GET /auth/oauth2/callback/{provider}` | 구현·자동 검증 (제공자 redirect 대상, Spring Security 처리) |
| `GET /api/auth/social/pending` | `GET /auth/social/pending` | 구현·자동 검증 |
| `DELETE /api/auth/social/pending` | `DELETE /auth/social/pending` | 구현·자동 검증 |
| `POST /api/auth/social/pending/email-verifications` | `POST /auth/social/pending/email-verifications` | 구현·자동 검증 |
| `POST /api/auth/social/pending/email-verifications/confirm` | `POST /auth/social/pending/email-verifications/confirm` | 구현·자동 검증 |
| `POST /api/auth/social/signup` | `POST /auth/social/signup` | 구현·자동 검증 |
| `POST /api/auth/social/link` | `POST /auth/social/link` | 구현·자동 검증 |
| `POST /api/auth/social/link/decline` | `POST /auth/social/link/decline` | 구현·자동 검증 |

자동 검증은 [verification/social.md](../verification/social.md)에 있다. 실제 카카오·구글·네이버 계정으로
지나는 흐름(quickstart 시나리오 F~H)은 개발자 앱 자격 증명이 준비된 뒤 확인한다.

- `provider`는 `kakao`, `google`, `naver`다.
- 시작 주소의 선택 쿼리: `reprompt=true`(이메일 재동의), `intent=link`(대기 중인 연결을 이미 연결된 소셜 계정으로 확인).
- 콜백은 JSON을 돌려주지 않고 FE 경로로 302 redirect한다. 이동 대상과 판정은 [plan.md](../plan.md)의
  Outcome classification 표를 따른다. redirect 주소에는 제공자 이름과 판정 종류만 싣는다.
- 인증번호 발송 대상은 대기 상태가 정한다(제공자 이메일 또는 연결 대상 계정 이메일). FE는 주소를 보내지 않는다.
- 소셜 가입·연결 성공은 `POST /auth/sessions`와 같이 세션 ID를 교체하고 `AuthenticatedUser`를 돌려준다.
  FE는 성공 뒤 CSRF를 다시 준비한다.
- 로컬 redirect 기준 주소는 `THORP_PUBLIC_BASE_URL`(기본 `http://localhost:5173`)이다. Vite proxy가 `Host`를 바꾸므로
  요청 주소로 추정하지 않는다.
- 새 오류 코드: `SOCIAL_PENDING_NOT_FOUND`(404), `SOCIAL_PENDING_STAGE_MISMATCH`(409), `SOCIAL_SIGNUP_REJECTED`(400), `SOCIAL_LINK_REQUIRED`(409), `SOCIAL_PROVIDER_ALREADY_LINKED`(409),
  `SOCIAL_ACCOUNT_ALREADY_LINKED`(409), `SOCIAL_PROVIDER_NOT_ENABLED`(404). 비밀번호·인증번호 오류는 기존 코드를 쓴다.
- 기존 `POST /api/auth/sessions`는 비밀번호가 없는 계정에 `INVALID_CREDENTIALS`를 돌려준다. 요청·응답 형식은 같다.

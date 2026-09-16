# Authentication Contract Verification

**기준**: `contracts/auth-api.yaml`  
**실행일**: 2026-09-16

## Operation 대조

| Operation | 외부 → 내부 경로 | 성공 계약 | 오류 계약 | 결과 |
|---|---|---|---|---|
| `requestEmailVerification` | `POST /api/accounts/email-verifications` → `POST /accounts/email-verifications` | 204, body 없음 | 400/403/500 envelope | PASS |
| `confirmEmailVerification` | `POST /api/accounts/email-verifications/confirm` → `POST /accounts/email-verifications/confirm` | 204, body 없음 | 400/403/500 envelope | PASS |
| `signup` | `POST /api/accounts` → `POST /accounts` | 201 `SignupSuccessEnvelope` | 400/403/500 envelope | PASS |
| `prepareCsrfToken` | `GET /api/auth/csrf` → `GET /auth/csrf` | 204 + `XSRF-TOKEN` | 500 envelope | PASS |
| `login` | `POST /api/auth/sessions` → `POST /auth/sessions` | 200 `AuthenticatedUserSuccessEnvelope` + session cookie | 400/401/403/500 envelope | PASS |
| `getCurrentUser` | `GET /api/auth/me` → `GET /auth/me` | 200 `AuthenticatedUserSuccessEnvelope` | 401/500 envelope | PASS |
| `logout` | `DELETE /api/auth/sessions/current` → `DELETE /auth/sessions/current` | 204, session 무효화 | 403/500 envelope | PASS |

## 성공 응답 확인

- 로컬 Vite proxy를 거쳐 CSRF 204, 회원가입 201, 로그인 200, 현재 사용자 복원 200,
  로그아웃 204를 확인했다.
- 회원가입 응답은 `userId`, 정규화된 `email`, `nickname`, `ACTIVE`만 포함했다.
- 로그인과 현재 사용자 응답은 같은 비민감 사용자 필드만 포함했고 비밀번호·전화번호·세션 식별자는
  포함하지 않았다.
- 로그아웃 뒤 동일 세션의 `/api/auth/me`는 401 `AUTHENTICATION_REQUIRED`를 반환했다.
- 인증번호 발송·확인의 204 성공 경로는 `AccountControllerContractTest`에서 검증했다. 실제 SMTP
  전달은 배포 자격 증명이 필요한 외부 통합이므로 이 로컬 검증에서는 수행하지 않았다.

## 오류 응답 확인

로컬 외부 경로에서 다음 응답을 확인했다.

| 조건 | HTTP / code |
|---|---|
| 잘못된 이메일 형식 | 400 `INVALID_INPUT` |
| 존재하지 않는 이메일 인증 요청 확인 | 400 `EMAIL_VERIFICATION_NOT_FOUND` |
| 이메일 확인 전 회원가입 | 400 `EMAIL_VERIFICATION_REQUIRED` |
| CSRF 없는 로그인 | 403 `CSRF_TOKEN_INVALID` |
| 잘못된 로그인 정보 | 401 `INVALID_CREDENTIALS` |
| 세션 없는 현재 사용자 조회 | 401 `AUTHENTICATION_REQUIRED` |

`DUPLICATE_EMAIL`, `DUPLICATE_PHONE`, `EMAIL_VERIFICATION_INVALID`,
`EMAIL_VERIFICATION_EXPIRED`, 예상하지 못한 500 sanitizing은 Backend 계약/서비스 테스트에서
검증됐다.

## 확인된 차이

`INVALID_INPUT`의 계약 예시는 일반 문구지만 Bean Validation의 실제 `message`는 실패한 필드와
검증 문구를 포함한다. 응답 구조와 안정된 분기 키인 `error.code`는 계약과 일치하며 FE도 메시지
문자열이 아니라 code로 분기한다. 따라서 구조적 불일치는 아니며, 오류 문구를 byte-for-byte 계약으로
간주하지 않는다.

T058의 7개 operation과 성공·오류 계약 대조는 통과했다.

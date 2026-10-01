# Implementation Plan: 사용자 인증

**Branch**: `014-user-auth` | **Date**: 2026-09-10 | **Spec**: [spec.md](spec.md)

**Input**: `specs/014-user-auth/spec.md`의 로그인·회원가입·로그아웃 및 보호 경로 요구사항

## Summary

기존 Spring Boot의 회원가입·이메일 인증 기능을 보존하고, 누락됐던 로그인·현재 사용자 조회·로그아웃을 서버 관리 세션으로 추가했다. 인증 상태는 PostgreSQL-backed HTTP session과 `HttpOnly` 쿠키로 유지하며 SPA의 상태 변경 요청은 CSRF 토큰으로 보호한다. React는 공용 Axios 클라이언트와 단일 인증 Provider를 통해 `확인 중 / 로그인됨 / 비로그인 / 만료 / 확인 실패 / 로그아웃 미확인` 상태를 표현하고, `내 기록`을 보호한다. 2026-09-20 결정(014 소유자의 권한 위임)에 따라 011 계산 요청·개인 검색 기록 저장·시장 흐름의 전략 비교 진입도 보호하며, 해당 화면·API 구현은 011이 맡는다.

## Implementation Status (2026-09-16)

- T001~T055의 FE·BE 구현이 현재 소스 트리에 반영됐다.
- FE lint/build와 149개 테스트, BE Spotless/build와 1,272개 테스트를 실행했다. 인증 및 PostgreSQL
  테스트는 skip 없이 통과했다. 상세 결과는 [verification/frontend.md](verification/frontend.md)와
  [verification/backend.md](verification/backend.md)에 있다.
- 계약 7개 operation과 로컬 회원가입→로그인→복원→만료/로그아웃 흐름을 검증했다.
  [verification/contract.md](verification/contract.md),
  [verification/integration.md](verification/integration.md)를 기준 기록으로 사용한다.
- 확인된 편차는 두 가지다. 360px 회원가입 화면의 큰 소개 문구가 viewport 밖으로 잘리며, 실제 SMTP
  전달은 운영 자격 증명이 없어 자동 계약 테스트와 확인 완료 fixture 이후의 가입 흐름까지만 검증했다.
  둘 다 확인되지 않은 상태를 PASS로 표시하지 않고 release 전 후속 확인 대상으로 남긴다.

## Social Login Extension (2026-10-01)

spec 2026-10-01 개정으로 카카오·구글·네이버 소셜 로그인(US6·US7, FR-020~FR-026, SC-012~SC-015)이 범위에
들어왔다. 위 Implementation Status(T001~T060)는 이메일 가입·로그인 범위의 완료 기록이며 이번 확장으로 다시
열지 않는다. 확장은 기존 세션·CSRF·오류 envelope·복귀 위치 규칙을 그대로 쓰고, 새 작업은 tasks.md의
T061 이후로 추적한다. 설계는 아래 [Social login design](#social-login-design) 절에 있다.

- 상태: 계획 완료, 구현 전
- 작업 브랜치: `feat/Oauth_create` 하나에서 진행하고 커밋을 작업 묶음(공통 기반 → 카카오 → 구글 → 네이버 →
  FE → 검증)으로 나눈다.
- 이메일 범위에 주는 영향: `users.password_hash` nullable 전환, 비밀번호 없는 계정의 이메일 로그인 거절,
  로그인·회원가입 화면의 소셜 버튼. 기존 operation의 요청·응답 형식은 바뀌지 않는다.

## Technical Context

**Language/Version**: Frontend TypeScript 5.8·React 19.1, Backend Java 21·Spring Boot 3.5.4

**Primary Dependencies**: React Router DOM 7.1, Axios 1.7, Motion 13; Spring MVC, Validation, Data JPA, Spring Security, Spring Session JDBC, Flyway

**Storage**: 기존 PostgreSQL `users`, `email_verifications`; 신규 Spring Session JDBC 세션 테이블. 비밀번호·세션 식별자는 브라우저 저장소에 보관하지 않는다.

**Testing**: Frontend 기존 Node test에 Vitest·React Testing Library를 추가해 폼·Provider·보호 경로를 검증한다. Backend는 JUnit 5·Spring Boot Test·MockMvc·PostgreSQL Testcontainers로 계약과 세션 생명주기를 검증한다.

**Target Platform**: nginx 뒤의 동일 출처 React SPA와 Linux Spring Boot 서버. 최신 evergreen 데스크톱 브라우저를 우선하고 기존 반응형 기준을 유지해 360px 이상 모바일에서도 인증 흐름을 완료할 수 있게 한다.

**Project Type**: React SPA + Spring Boot web API

**Performance Goals**: 인증 확인 중 보호 정보를 먼저 보여주지 않고 즉시 진행 상태를 표시한다. 제품 수준의 고정 SLO는 이 spec에서 새로 만들지 않으며, 이메일 발송을 제외한 인증 API는 기존 10초 클라이언트 타임아웃 안에 완료되는지 통합 검증한다.

**Constraints**: 외부 `/api/*`는 Vite·nginx가 `/api`를 제거해 Backend에 전달한다. 인증 쿠키는 `HttpOnly`, 운영 `Secure`, `SameSite=Lax`, `Path=/`를 사용한다. 로그인 유지 선택지는 제공하지 않고 서버가 정한 30분 비활성 세션 만료를 기본값으로 두되 FE는 남은 시간을 추정하지 않는다. 이메일은 FE와 BE에서 앞뒤 공백 제거와 소문자 변환을 동일하게 적용하고, 전화번호는 하이픈·공백 없는 국내 휴대폰 숫자 형식만 허용한다. 공개 화면은 인증 장애로 막지 않는다.

**Scale/Scope**: 기존 계정 API 3개 재사용, 인증 API 4개(CSRF 발급·로그인·현재 사용자·로그아웃) 추가, 인증 화면 2개와 단일 보호 경로 경계. 별도 인증 서비스나 Redis는 도입하지 않는다.

## Constitution Check

*GATE: Phase 0 전과 Phase 1 설계 후 모두 통과했다.*

| Gate | 판정 | 설계 반영 |
|---|---|---|
| I. 전략 진단 중심 | PASS | 전략 진단·시장 흐름·배우기는 공개로 유지하고 `내 기록`을 보호한다. 2026-09-20 결정(014 소유자의 권한 위임)에 따라 011 계산 요청·개인 검색 기록 저장·시장 흐름의 전략 비교 진입도 보호하며, 해당 화면·API 구현은 011이 맡는다. |
| II. 첫 화면 단순성 | PASS | 로그인은 이메일·비밀번호, 가입은 서버가 요구하는 필드와 이메일 인증만 노출한다. 유지 옵션을 추가하지 않는다. |
| VI. 없는 결과를 만들지 않음 | PASS | 인증 확인 실패와 로그아웃 미확인을 로그인 성공·완료로 대체하지 않는다. |
| VIII. 개인정보와 기록 분리 | PASS | 사용자·세션은 Backend에 두고 비밀번호나 세션 토큰을 FE 저장소·URL에 기록하지 않는다. |
| 기능 검증과 완료 기준 | PASS | FE 단위/컴포넌트, BE 계약/통합, 전체 사용자 흐름을 각각 검증한다. |
| Git·Jira 작업 묶음 | PASS | 하나의 plan에서 FE·BE·통합 task를 만들고 독립 검증·롤백 가능한 묶음으로 Jira/Branch/MR을 나눈다. |
| 미합의 인증·인가 영역 | PASS | plan에서 서버 세션·쿠키·CSRF 방식을 근거와 함께 결정하고 [research.md](research.md)에 대안을 기록한다. |

설계 후 재검사에서도 위반은 없다. 인증 기능이 공개 핵심 흐름을 차단하지 않으며, 구현 단계의 신규 의존성과 DB 변경은 각각 독립 작업 묶음과 테스트를 가져야 한다.

## Project Structure

### Documentation (this feature)

```text
specs/014-user-auth/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── README.md
│   └── auth-api.yaml
└── tasks.md                 # $speckit-tasks에서 생성
```

### Source Code (implementation targets)

```text
frontend/
├── src/
│   ├── api/
│   │   ├── client.ts        # 쿠키·CSRF 및 공통 오류 처리 확장
│   │   └── auth.ts
│   ├── auth/
│   │   ├── AuthProvider.tsx
│   │   ├── ProtectedRoute.tsx
│   │   └── returnTarget.ts
│   ├── components/auth/
│   ├── pages/
│   │   ├── LoginPage.tsx
│   │   └── SignupPage.tsx
│   ├── types/auth.ts
│   ├── App.tsx              # 인증 Provider·라우트 연결
│   └── main.tsx
└── package.json

backend/
├── src/main/java/com/ssafy/thorp/
│   ├── account/
│   │   ├── application/     # 기존 가입 재사용, 세션 유스케이스 추가
│   │   ├── domain/
│   │   ├── infrastructure/
│   │   └── presentation/    # 계정 경로 정규화와 인증 API
│   └── common/
│       ├── config/          # SecurityFilterChain·session·CSRF 설정
│       └── web/             # 인증 실패 공통 응답
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/        # Spring Session 테이블 마이그레이션
└── src/test/java/com/ssafy/thorp/account/
```

**Structure Decision**: 현재의 FE `pages/api/types` 구조와 BE account 계층 구조를 확장한다. 인증 전용 FE 상태만 `src/auth`에 모으고 화면·API 타입은 기존 위치 규칙을 따른다. BE는 별도 서비스로 분리하지 않고 기존 account bounded context와 공통 보안 설정을 사용한다.

## Design Decisions

### Authentication and session

- 이메일·비밀번호 인증 성공 시 Spring Security `SecurityContext`를 서버 세션에 명시적으로 저장한다.
- 로그인 성공 시 Spring Security session fixation protection을 적용해 기존 익명 세션 식별자를 교체하고 새 식별자만 인증 세션으로 사용한다.
- Spring Session JDBC가 PostgreSQL에 세션을 저장해 Backend 프로세스 재시작이나 향후 다중 인스턴스에서 메모리 세션에 묶이지 않게 한다.
- 세션 쿠키는 스크립트에서 읽을 수 없으며 FE는 로그인 여부를 `/api/auth/me` 결과로만 판정한다.
- 기본 비활성 만료는 30분이며 환경 설정으로 조정한다. remember-me와 영구 쿠키는 범위 밖이다.
- 로그인 실패는 이메일 존재 여부·계정 상태를 추측하지 못하도록 동일한 `INVALID_CREDENTIALS` 응답을 사용한다. 정지·탈퇴 계정의 사용자 메시지는 로그인 실패와 구분하지 않고 서버 감사 로그에서만 원인을 구분한다.

### CSRF and request boundary

- 동일 출처 쿠키 인증을 사용하므로 Spring Security의 CSRF 보호를 유지한다.
- FE는 앱 초기화 및 인증 성공·로그아웃 성공 뒤 `/api/auth/csrf`에서 토큰 쿠키를 갱신하고 Axios가 `X-XSRF-TOKEN` 헤더로 전송하게 한다.
- 운영 외부 경로는 `/api/*`, Backend 내부 컨트롤러 경로는 `/accounts/*`, `/auth/*`로 통일한다. 현재 Account 컨트롤러의 `/api/accounts` 매핑은 프록시가 접두사를 제거하는 구조와 맞지 않으므로 BE 작업에서 내부 `/accounts`로 정규화한다.
- 기존 account API의 `{ success, data, error }` envelope를 인증 API에도 적용한다. 204 응답에는 본문을 두지 않는다.

### Frontend state and routing

- 앱 루트의 단일 `AuthProvider`가 사용자와 인증 상태를 소유한다. 개별 페이지가 쿠키나 서버 상태를 직접 추측하지 않는다.
- `AuthProvider`는 앱 최초 진입, document가 숨김 상태에서 다시 활성화된 시점, `내 기록` 진입 직전에 `/auth/me`를 재확인한다. 공개 화면은 백그라운드 재확인 때문에 막지 않으며 보호 화면은 확인이 끝나기 전 표시하지 않는다.
- 초기 상태는 `checking`이며 `/api/auth/me`가 끝나기 전에 보호 화면을 렌더링하지 않는다. 401은 `anonymous`, 네트워크 실패는 `error`로 분리한다.
- `ProtectedRoute`의 기존 적용 대상은 `내 기록`이며, 011은 보호 진입과 계산 동작에서 같은 인증·복귀 계약을 재사용한다. 로그인 안내는 로그인 화면으로 이동할 때 내부 복귀 위치를 저장한다.
- 복귀 위치는 같은 origin의 `/`로 시작하고 `//`로 시작하지 않는 path/query/hash만 허용한다. 세션 탭 안에서 새로고침해도 보존하도록 `sessionStorage`에 한 번만 저장하며 로그인 성공 후 제거한다. 비밀번호와 사용자 객체는 저장하지 않는다.
- 가입 성공 시 로그인 화면으로 이동해 이메일만 navigation state로 전달한다. 비밀번호는 즉시 폐기한다.
- 이미 인증된 사용자가 로그인·회원가입 route에 접근하면 인증 폼을 표시하지 않고 `/home`으로 이동한다.
- 로그아웃 요청을 시작하면 사용자 표시와 보호 데이터를 숨긴다. 서버 확인 실패 시 `logoutUnconfirmed`로 두고 `/me` 재확인 또는 재시도 전까지 보호 경로를 막는다.
- 로그아웃 시작, 인증 401 또는 페이지 캐시 복원 시 메모리의 사용자·보호 데이터를 제거하고 현재 사용자 확인 전에는 방문 기록에서 복원된 보호 화면도 렌더링하지 않는다.

### Validation and error mapping

- FE 검증은 현재 BE 규칙(이메일, 비밀번호 8~25자, 닉네임 2~20자, 하이픈 없는 국내 휴대폰 10~11자리, 인증번호 6자리)과 동일하게 유지한다.
- FE는 이메일을 앞뒤 공백 제거·소문자 변환한 뒤 전송하고 BE가 동일 정규화를 다시 적용해 이메일 인증·중복 조회·로그인·저장에 같은 값을 사용한다. 전화번호는 양쪽에서 동일한 국내 휴대폰 숫자 정규식으로 검증한다.
- Bean Validation의 필드 메시지와 비즈니스 오류 envelope를 하나의 사용자 오류 모델로 변환하되 서버 내부 예외 문자열은 표시하지 않는다. 가입·이메일 인증은 `DUPLICATE_EMAIL`, `DUPLICATE_PHONE`, `EMAIL_VERIFICATION_REQUIRED`, `EMAIL_VERIFICATION_NOT_FOUND`, `EMAIL_VERIFICATION_INVALID`, `EMAIL_VERIFICATION_EXPIRED`를 안정된 분기 코드로 사용하고 일반 형식 오류만 `INVALID_INPUT`으로 남긴다.
- 401은 이전에 인증된 상태였는지에 따라 `로그인이 필요합니다` 또는 `로그인이 만료되었습니다`로 표현한다. 403 CSRF 오류는 토큰을 한 번 갱신한 뒤 사용자 동작으로 재시도하게 하며 요청을 자동 중복 전송하지 않는다.
- 네트워크 실패에는 서버 body가 없으므로 FE가 연결 오류로 처리한다. 계약에 정의된 모든 operation은 예상하지 못한 서버 실패에 동일한 `INTERNAL_ERROR` envelope를 사용한다.
- 처리 상태는 보조 기술에 상태 알림으로 전달하고, 폼 제출 실패 시 첫 번째 잘못된 입력으로 초점을 이동한다. route 전환 뒤에는 화면 제목부터 읽기 흐름을 이어간다.

### Deferred security policy

- 반복 로그인·인증번호 발송의 횟수 제한, 관측 구간, 계정 잠금 여부는 현재 팀 정책과 Backend 구현에서 확인되지 않아 이 feature에서 임의의 수치로 정하지 않는다.
- 운영 배포 전 별도 BE 보안 Task에서 정책·429 오류 계약·로그 및 지표를 합의한다. 014의 FE는 해당 정책이 추가되더라도 공통 오류 envelope를 사용자 조치 가능 안내로 표시할 수 있어야 한다.
- 이메일 발송은 외부 SMTP 지연을 포함하므로 별도 pending 상태와 재시도 동작을 가진다.
- 2026-09-16 Phase 8 검증 시점에도 rate-limit 수치와 계정 잠금 정책은 구현 범위 밖이다. 별도
  `[BE] 인증 요청 남용 방지 정책 수립 및 적용` Jira Task에서 정책·429 계약·관측 기준을 확정해야 한다.

## Social login design

### Provider integration

- Backend에 `spring-boot-starter-oauth2-client`를 추가하고 `oauth2Login()`을 기존 `SecurityFilterChain`에
  붙인다. 별도 인증 서버나 JWT는 도입하지 않는다. 로그인 결과는 이메일 로그인과 같은 `AuthenticatedPrincipal`로
  서버 세션의 `SecurityContext`에 저장하고, `OAuth2User`를 그대로 principal로 두지 않는다.
- 구글은 Spring 기본 OIDC provider를 쓰고 `email`·`email_verified` claim을 읽는다. 카카오·네이버는
  `spring.security.oauth2.client.provider.*`에 authorization·token·user-info URI를 직접 등록한다.
  - 카카오: `id`, `kakao_account.email`, `kakao_account.is_email_verified`, `kakao_account.profile.nickname`
  - 네이버: `response.id`, `response.email`, `response.nickname`. 확인 여부 필드가 없으므로 FR-022의
    Thorp 인증번호 경로로 보낸다.
  - 구글: `sub`, `email`, `email_verified`, `name`
- 제공자별 응답 차이는 제공자마다 하나인 `SocialProfileExtractor`가 흡수해 `SocialProfile(provider,
  providerUserId, email?, emailVerified: TRUE|FALSE|UNKNOWN, nickname?)` 하나로 바꾼다. 이후 판정 로직은
  제공자를 모른다.
- 외부 경로는 기존 규칙대로 `/api`를 붙인다. 시작은 `GET /api/auth/oauth2/authorization/{provider}`, 콜백은
  `GET /api/auth/oauth2/callback/{provider}`이며 proxy가 `/api`를 떼어 내부 `/auth/oauth2/...`로 받는다.
  `redirect-uri`는 `{baseUrl}/api/auth/oauth2/callback/{registrationId}`로 두고, proxy 뒤에서 `baseUrl`이
  외부 주소가 되도록 `server.forward-headers-strategy=framework`와 nginx `X-Forwarded-*` 헤더를 확인한다.
- 이메일 재동의(FR-022 거절 화면의 "다시 동의")는 시작 주소에 `?reprompt=true`를 붙이고, 사용자 정의
  `OAuth2AuthorizationRequestResolver`가 제공자별 파라미터로 바꾼다. 카카오는 `scope=account_email` 추가 동의,
  네이버는 `auth_type=reprompt`, 구글은 `prompt=consent`다.
- 제공자 access token은 사용자 정보 조회에만 쓰고 저장하지 않는다. 기본
  `HttpSessionOAuth2AuthorizedClientRepository` 대신 저장하지 않는 repository를 등록한다(FR-025).
- `state` 검증과 authorization request 보관은 Spring Security 기본(세션 저장)을 쓴다. 불일치·재사용은 실패
  처리기로 간다(FR-026).
- 클라이언트 ID·secret은 환경 변수(`KAKAO_CLIENT_ID`·`KAKAO_CLIENT_SECRET` 등)로만 주입한다. 자격 증명이 없는
  환경에서는 그 제공자를 등록하지 않고, `GET /api/auth/social/providers`가 켜진 제공자 목록만 돌려준다.

### Outcome classification

콜백 성공 처리기는 `SocialLoginService.resolve(SocialProfile)`의 판정에 따라 세션을 만들거나 가입 대기 상태를
세션에 저장하고, 민감하지 않은 FE 경로로만 redirect한다. 인가 코드는 redirect 대상에 남지 않는다.

| 판정 | 조건 | 서버 동작 | FE 이동 |
|---|---|---|---|
| `LOGGED_IN` | `(provider, providerUserId)`가 연결됨, 계정 `ACTIVE` | 세션 ID 교체 후 인증 저장, `lastLoginAt` 갱신 | `/auth/social/complete` |
| `ACCOUNT_UNAVAILABLE` | 연결됐지만 계정이 `ACTIVE`가 아님 | 인증하지 않음 | `/login?social=unavailable` |
| `EMAIL_MISSING` | 제공자 이메일 없음 | 대기 상태 만들지 않음 | `/auth/social/email-required?provider={p}` |
| `EMAIL_VERIFICATION` | 이메일 확인 여부 `FALSE`·`UNKNOWN` | 대기 상태(stage=`EMAIL_VERIFICATION`) | `/auth/social/verify-email` |
| `PROVIDER_CONFLICT` | 이메일로 찾은 계정에 같은 제공자의 다른 계정이 연결됨 | 대기 상태 만들지 않음 | `/auth/social/already-linked?provider={p}` |
| `LINK_REQUIRED` | 확인된 이메일이 기존 계정과 같음 | 대기 상태(stage=`LINK`, 대상 계정) | `/auth/social/link` |
| `SIGNUP_REQUIRED` | 연결·겹침 없음 | 대기 상태(stage=`SIGNUP`) | `/signup/social` |

실패 처리기는 `error=access_denied`(사용자 취소)를 `/login?social=cancelled`로, 그 밖의 제공자 오류와 `state`
불일치를 `/login?social=error`로 보낸다. 쿼리에는 제공자 이름과 판정 종류만 싣는다.

### Pending social state

- `PendingSocialLogin`은 Spring Session JDBC 세션 속성 하나로 보관한다(별도 테이블 없음). 생성 10분 뒤 만료되고,
  가입 완료·연결 완료·취소·새 소셜 로그인 시작 중 하나가 일어나면 바로 지운다(FR-025).
- 속성은 직렬화 가능한 record이며 제공자 token을 담지 않는다. 대기 중에는 익명 세션 그대로이고, 가입·연결이 끝나
  인증을 저장할 때 기존 `ChangeSessionIdAuthenticationStrategy`로 세션 ID를 교체한다(FR-018).
- 소셜 경로의 인증번호(FR-022 제공자 이메일 확인, FR-024 기존 계정 이메일 확인)는 기존 `email_verifications`를
  쓰지 않는다. 그 테이블은 "가입되지 않은 이메일" 전용인데 연결 경로는 이미 가입된 이메일로 보내야 하기 때문이다.
  대기 상태 안에 `codeHash`·`codeExpiresAt`·`codeTarget(PROVIDER_EMAIL|LINK_TARGET_EMAIL)`을 두고, 6자리·3분·
  재발송 시 이전 번호 무효 규칙과 기존 `VerificationMailSender`를 재사용한다.
- 제공자 이메일을 Thorp 인증번호로 확인하면 `emailVerified=TRUE`로 바꾸고 판정을 다시 돌린다. 이때 그 이메일이
  연결 대상 계정 이메일과 같으면 `linkTargetEmailVerified=TRUE`로 기록해 FR-024 (2)를 마친 것으로 본다.

### Signup and link rules

- 소셜 가입(`POST /auth/social/signup`)은 닉네임·휴대폰 번호만 받는다. 이메일은 대기 상태의 확인된 이메일을
  정규형으로 쓰고 `password_hash`는 `NULL`로 저장한다. 사용자 생성과 `social_accounts` 연결은 한 트랜잭션이다.
- 휴대폰 번호가 다른 계정에서 쓰이면 계정을 만들지 않는다. 대기 상태를 stage=`LINK`, 대상 계정=번호 소유 계정,
  `linkReason=PHONE`으로 바꾸고 409 `SOCIAL_LINK_REQUIRED`를 돌려준다. 그 대상 계정에 같은 제공자의 다른 계정이
  연결돼 있으면 409 `SOCIAL_PROVIDER_ALREADY_LINKED`다. `linkReason=PHONE`이면 제공자 이메일 확인을 소유 확인으로
  인정하지 않는다.
- 연결 확인(`POST /auth/social/link`)은 `method`로 셋 중 하나를 받는다. `PASSWORD`(대상 계정 비밀번호),
  `EMAIL_CODE`(대상 계정 이메일로 보낸 인증번호), `VERIFIED_EMAIL`(대기 상태에 `linkTargetEmailVerified=TRUE`일
  때만). 이미 연결된 다른 소셜 계정으로 확인할 때는 `GET /api/auth/oauth2/authorization/{p}?intent=link`로 제공자
  로그인을 한 번 더 거친다. 콜백에서 그 소셜 계정이 대상 계정에 연결돼 있으면 대기 중인 연결을 마치고, 다른 계정에
  연결돼 있으면 연결하지 않는다(US7 시나리오 5).
- 비밀번호 오류는 기존 `INVALID_CREDENTIALS`, 인증번호 오류는 기존 `EMAIL_VERIFICATION_*` 코드를 그대로 쓴다.
- `social_accounts`에는 `(provider, provider_user_id)`와 `(user_id, provider)` 두 고유 제약을 둔다. 동시 요청으로
  제약 위반이 나면 연결하지 않고 판정을 다시 돌린다.
- 이메일 로그인(`POST /auth/sessions`)은 `password_hash`가 `NULL`인 계정을 `INVALID_CREDENTIALS`로 거절한다.
  이메일 가입은 이메일이 소셜 전용 계정과 같아도 기존 `DUPLICATE_EMAIL`이다.

### Frontend (social)

- 소셜 버튼은 Axios 호출이 아니라 `window.location.assign('/api/auth/oauth2/authorization/{p}')` 전체 페이지
  이동이다. 같은 탭으로 돌아오므로 `sessionStorage`의 기존 `returnTarget`이 그대로 보존된다.
- 버튼은 제공자 공식 디자인 가이드(카카오 노랑, 네이버 초록, 구글 흰 바탕과 로고)를 따르고
  `GET /api/auth/social/providers`가 켠 제공자만 그린다.
- 새 route: `/auth/social/complete`(현재 사용자 재확인 후 복귀 위치로 이동), `/signup/social`(추가 정보),
  `/auth/social/verify-email`, `/auth/social/link`, `/auth/social/email-required`, `/auth/social/already-linked`.
  대기 상태를 쓰는 화면은 진입 시 `GET /api/auth/social/pending`을 읽고, 404 `SOCIAL_PENDING_NOT_FOUND`면
  "처음부터 다시" 안내를 보여준다. 대기 상태 정보는 메모리에만 둔다.
- `/login`은 `?social=cancelled|error|unavailable`을 읽어 상태 알림 영역에 안내를 띄운 뒤 쿼리를 지운다.
- 인증 Provider의 상태 모델은 바꾸지 않는다. 소셜 로그인 완료는 `/auth/social/complete`에서 `/auth/me`
  재확인으로 `authenticated`가 된다.

### Social error codes

`SOCIAL_PENDING_NOT_FOUND`(404, 대기 상태 없음·만료·이미 사용), `SOCIAL_LINK_REQUIRED`(409),
`SOCIAL_PROVIDER_ALREADY_LINKED`(409), `SOCIAL_ACCOUNT_ALREADY_LINKED`(409, 그 소셜 계정이 다른 Thorp 계정에
연결됨), `SOCIAL_PROVIDER_NOT_ENABLED`(404). 나머지는 기존 코드를 재사용한다.

### Constitution Check (social extension)

| Gate | 판정 | 근거 |
|---|---|---|
| II. 첫 화면 단순성 | PASS | 로그인·가입 화면에 버튼 세 개만 더하고 기존 입력은 늘리지 않는다. 추가 정보는 처음 가입하는 소셜 사용자만 본다. |
| VI. 없는 결과를 만들지 않음 | PASS | 소유 확인 전의 연결·가입 대기 상태를 로그인으로 표시하지 않는다. |
| VIII. 개인정보와 기록 분리 | PASS | 제공자 token을 저장하지 않고 대기 상태는 서버 세션에만 둔다. 저장하는 제공자 정보는 제공자 이름과 사용자 식별자뿐이다. |
| 미합의 인증·인가 영역 | PASS | 기존 서버 세션 결정 위에 OAuth2 client만 더한다. 대안은 research Decision 10~12에 있다. |
| 신규 의존성 | 확인 필요 | `spring-boot-starter-oauth2-client` 추가. constitution의 신규 라이브러리 추가 절차에 따라 MR에 근거를 적는다. |

## Work Partition for Task Generation

`$speckit-tasks`는 다음 작업 묶음을 더 작은 실행 task로 나눈다.

1. **[BE] 인증 기반과 계약**: account 경로 정규화, Spring Security·Session JDBC·CSRF, 오류 코드와 DB migration.
2. **[BE] 세션 API**: 로그인·현재 사용자·로그아웃 구현 및 계약/통합 테스트.
3. **[FE] 인증 API와 상태**: 타입, Axios CSRF/쿠키 설정, AuthProvider, 오류 매핑과 단위 테스트.
4. **[FE] 인증 화면**: 로그인·회원가입·이메일 인증, 접근성·반응형·중복 제출 테스트.
5. **[FE] 보호 경로와 공통 탐색**: `내 기록` 보호, 원래 위치 복귀, 헤더 로그인 상태.
6. **[통합] 사용자 흐름 검증**: 가입→로그인→새로고침→보호 경로→만료/로그아웃 및 실패 시나리오.

각 묶음은 독립적으로 구현·검증·리뷰·롤백할 수 있어야 하며, 해당 Jira Task를 만든 뒤 팀 Git 규칙의 브랜치와 MR을 연결한다.

### Social login work partition (2026-10-01)

7. **[BE] 소셜 공통 기반**: OAuth2 client 의존성, `social_accounts`·`password_hash` 마이그레이션, 판정 서비스,
   대기 상태, 소셜 API와 오류 코드, 비밀번호 없는 계정의 이메일 로그인 거절.
8. **[BE] 카카오**, 9. **[BE] 구글**, 10. **[BE] 네이버**: 제공자 등록·프로필 추출·재동의 파라미터와 제공자별 테스트.
11. **[FE] 소셜 화면**: 버튼, 콜백 이후 화면 6개, 로그인 화면 안내.
12. **[통합] 소셜 흐름 검증**: SC-012~SC-015 증거.

이번 확장은 사용자 결정에 따라 브랜치 하나(`feat/Oauth_create`)에서 진행하고 위 묶음을 커밋 단위로 구분한다.


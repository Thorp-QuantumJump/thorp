# Implementation Plan: 사용자 인증

**Branch**: `014-user-auth` | **Date**: 2026-09-10 | **Spec**: [spec.md](spec.md)

**Input**: `specs/014-user-auth/spec.md`의 로그인·회원가입·로그아웃 및 보호 경로 요구사항

## Summary

기존 Spring Boot의 회원가입·이메일 인증 기능을 보존하고, 누락됐던 로그인·현재 사용자 조회·로그아웃을 서버 관리 세션으로 추가했다. 인증 상태는 PostgreSQL-backed HTTP session과 `HttpOnly` 쿠키로 유지하며 SPA의 상태 변경 요청은 CSRF 토큰으로 보호한다. React는 공용 Axios 클라이언트와 단일 인증 Provider를 통해 `확인 중 / 로그인됨 / 비로그인 / 만료 / 확인 실패 / 로그아웃 미확인` 상태를 표현하고, `내 기록`만 보호한다.

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
| I. 전략 진단 중심 | PASS | 전략 진단·시장 흐름·배우기는 공개로 유지하고 `내 기록`만 보호한다. |
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
- `ProtectedRoute`는 `내 기록` 경로만 막고 로그인 화면으로 이동할 때 내부 복귀 위치를 저장한다.
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

## Work Partition for Task Generation

`$speckit-tasks`는 다음 작업 묶음을 더 작은 실행 task로 나눈다.

1. **[BE] 인증 기반과 계약**: account 경로 정규화, Spring Security·Session JDBC·CSRF, 오류 코드와 DB migration.
2. **[BE] 세션 API**: 로그인·현재 사용자·로그아웃 구현 및 계약/통합 테스트.
3. **[FE] 인증 API와 상태**: 타입, Axios CSRF/쿠키 설정, AuthProvider, 오류 매핑과 단위 테스트.
4. **[FE] 인증 화면**: 로그인·회원가입·이메일 인증, 접근성·반응형·중복 제출 테스트.
5. **[FE] 보호 경로와 공통 탐색**: `내 기록` 보호, 원래 위치 복귀, 헤더 로그인 상태.
6. **[통합] 사용자 흐름 검증**: 가입→로그인→새로고침→보호 경로→만료/로그아웃 및 실패 시나리오.

각 묶음은 독립적으로 구현·검증·리뷰·롤백할 수 있어야 하며, 해당 Jira Task를 만든 뒤 팀 Git 규칙의 브랜치와 MR을 연결한다.

# Quickstart: 사용자 인증 구현 검증

이 문서는 [plan.md](plan.md)와 [API contract](contracts/auth-api.yaml)를 구현한 뒤 FE·BE·통합 동작을 재현하는 검증 가이드다. 2026-09-16 실행 결과는 [verification/](verification/)에 기록했다.

## Prerequisites

- Java 21
- Node.js와 npm
- PostgreSQL(Testcontainers 사용 시 Docker)
- Backend 이메일 발송용 `MAIL_USERNAME`, `MAIL_PASSWORD`
- 로컬 DB 기본 연결: `localhost:5432/thorp`

## 1. Contract와 경로 확인

1. 외부 호출 경로가 `/api/accounts/*`, `/api/auth/*`인지 확인한다.
2. Vite/nginx가 `/api`를 제거한 뒤 Backend의 `/accounts/*`, `/auth/*`로 전달하는지 확인한다.
3. Account controller가 내부 경로에 `/api`를 중복 포함하지 않는지 확인한다.
4. 성공 JSON이 `{ success, data, error }` envelope를 따르고 204에는 body가 없는지 확인한다.

기준 문서: [contracts/README.md](contracts/README.md), [contracts/auth-api.yaml](contracts/auth-api.yaml)

## 2. Backend automated checks

```powershell
cd backend
.\gradlew.bat spotlessCheck
.\gradlew.bat test
```

필수 검증:

- 기존 회원가입·이메일 인증 회귀 tests
- 공백·대소문자가 다른 이메일도 같은 정규형으로 인증·중복 조회·로그인·저장되는지
- 가입·이메일 인증의 사용자 조치가 다른 실패가 contract의 세부 오류 코드로 구분되는지
- 올바른 이메일·비밀번호 로그인과 password mismatch
- 존재하지 않는 이메일·비활성 계정이 같은 `INVALID_CREDENTIALS` 외부 응답을 사용하는지
- 로그인 성공 때 session id가 교체되고 PostgreSQL session row가 생성되는지
- `/auth/me`의 200/401과 응답에 전화번호·password hash·session id가 없는지
- 로그아웃 뒤 server session 무효화와 재접근 401
- 이미 비로그인인 DELETE logout의 204 idempotency
- CSRF 누락/불일치 403 envelope
- 모든 인증 operation의 예상하지 못한 실패가 500 `INTERNAL_ERROR` envelope를 사용하는지
- 30분 비활성 만료와 만료 session 정리
- PostgreSQL migration이 빈 DB에 적용되고 기존 `users` data를 보존하는지

## 3. Frontend automated checks

```powershell
cd frontend
npm install
npm run lint
npm run test
npm run build
```

필수 검증:

- 가입 필드와 인증번호 검증이 [data-model.md](data-model.md)의 규칙과 일치
- 발송·확인·가입·로그인 중 같은 submit이 두 번 호출되지 않음
- 가입 성공 후 login으로 이동하고 이메일만 prefill됨
- boot `checking` 중 보호 화면이 렌더링되지 않음
- `/auth/me` 200/401/network failure가 각각 authenticated/anonymous/error로 전이
- 숨겨졌던 탭의 재활성화와 `내 기록` 진입 직전에 `/auth/me`를 재확인하고 공개 화면은 막지 않는지
- 로그인된 상태에서 API 401을 받으면 expired로 전이
- `logoutUnconfirmed`에서 사용자·내 기록이 숨겨지고 재확인/재시도 가능
- 외부 URL, `//host`, auth 화면 자체를 return target으로 사용하지 않음
- 로그인 사용자의 로그인·회원가입 직접 접근은 `/home`으로 이동
- 로그아웃·만료 뒤 브라우저 방문 기록 또는 page cache로 돌아가도 이전 보호 정보가 복원되지 않음
- 키보드만으로 모든 입력·발송·확인·가입·로그인·로그아웃 가능
- pending/error/success/expired/logoutUnconfirmed 상태가 `role=status` 또는 `aria-live`로 전달되고 제출 실패 시 첫 오류 입력으로 초점이 이동함
- 360px 이상 viewport에서 가로 overflow 없이 흐름 완료

## 4. Local integration run

Backend:

```powershell
cd backend
.\gradlew.bat bootRun
```

Frontend:

```powershell
cd frontend
npm run dev
```

브라우저는 `http://localhost:5173`을 사용한다. FE는 `/api`를 `localhost:8080`으로 proxy하고 Backend에는 접두사가 제거된 경로가 전달된다.

## 5. End-to-end scenarios

### Scenario A: 회원가입

1. 회원가입 화면에서 잘못된 이메일·짧은 비밀번호·잘못된 전화번호를 각각 입력한다.
2. 요청 없이 항목 가까이에 오류가 표시되는지 확인한다.
3. 가입되지 않은 이메일로 인증번호를 요청한다.
4. 잘못된 번호와 만료된 번호가 가입을 허용하지 않고 재입력/재발송을 안내하는지 확인한다.
5. 재발송 후 이전 번호가 실패하고 최신 번호가 성공하는지 확인한다.
6. 가입을 연속 클릭해 서버 요청이 한 건인지 확인한다.
7. 성공 후 로그인 화면에서 이메일만 채워지고 비밀번호는 비어 있는지 확인한다.

### Scenario B: 로그인과 복원

1. 잘못된 이메일 또는 비밀번호로 로그인하고 같은 일반 오류가 표시되는지 확인한다.
2. 올바른 계정으로 로그인한다.
3. 새로고침 후 같은 사용자 표시가 복원되는지 확인한다.
4. browser storage에 password나 session id가 없는지 확인한다.
5. Network panel에서 session cookie가 `HttpOnly`, `SameSite=Lax`이고 unsafe 요청에 CSRF header가 있는지 확인한다.
6. 로그인 전후 session id가 달라지는지 확인한다.

### Scenario C: 보호 경로와 복귀

1. 로그아웃 상태에서 `내 기록`의 하위 위치에 직접 접근한다.
2. 보호 내용이 잠깐도 보이지 않고 로그인 화면으로 이동하는지 확인한다.
3. 로그인한 뒤 정확한 내부 위치로 돌아가는지 확인한다.
4. 로그인 화면을 새로고침한 뒤에도 복귀하는지 확인한다.
5. 외부 URL을 복귀 위치로 주입하면 `/home`으로 이동하는지 확인한다.
6. 로그인하지 않은 상태에서 전략 진단·시장 흐름·배우기는 계속 이용 가능한지 확인한다.

### Scenario D: 만료와 로그아웃

1. `내 기록` 이용 중 server session을 만료시킨다.
2. 보호 정보를 숨기고 로그인 만료 안내를 표시하는지 확인한다.
3. 재로그인 후 만료 전 내부 위치로 돌아가는지 확인한다.
4. 로그아웃 요청을 network error로 실패시킨다.
5. 사용자·보호 정보가 즉시 숨겨지고 “로그아웃 미확인”과 재시도가 표시되는지 확인한다.
6. 재시도 성공 후 `/auth/me`가 401이고 공개 기능은 계속 이용 가능한지 확인한다.

### Scenario E: 다중 탭과 캐시 복원

1. 두 탭을 열고 한 탭에서 로그인 또는 로그아웃한다.
2. 다른 탭을 다시 활성화하거나 `내 기록`으로 진입할 때 현재 사용자를 재확인하는지 확인한다.
3. 확인 중 공개 화면은 이용할 수 있고 보호 화면은 노출되지 않는지 확인한다.
4. 로그아웃·만료 뒤 브라우저 뒤로가기와 페이지 캐시 복원으로 이전 사용자 정보가 나타나지 않는지 확인한다.

## 6. Completion evidence

- FE lint/test/build 결과
- BE spotless/test 결과
- API contract 7개 operation별 성공·실패 결과
- desktop과 360px mobile의 인증 흐름 캡처
- network failure·session expiry·CSRF 실패 검증 기록
- 구현 완료, 사용성 확인, MR 병합을 서로 구분한 Jira/MR 기록

2026-09-16 기준 실행 기록:

- [Frontend lint/test/build](verification/frontend.md)
- [Backend Spotless/PostgreSQL tests](verification/backend.md)
- [7개 API operation 계약 대조](verification/contract.md)
- [통합 흐름과 desktop/360px 화면 증거](verification/integration.md)

현재 알려진 미완료 검증은 실제 SMTP 자격 증명을 사용한 메일 전달과 360px 회원가입 소개 문구의
가로 잘림 수정 후 재검증이다. 반복 로그인·인증번호 발송 제한과 계정 잠금 정책은 별도 보안 Task로
남아 있으며 014가 임의 수치를 정하지 않는다.

Task 완료는 constitution의 구현·테스트·리뷰·MR 병합·문서 수정 다섯 조건을 모두 충족해야 한다.

# Authentication Integration Verification

**실행일**: 2026-09-16  
**경로**: 브라우저 외부 경로 `http://127.0.0.1:5173/api` → Vite proxy → Backend

## 사용자 흐름 결과

| 흐름 | 증거 | 결과 |
|---|---|---|
| 회원가입 | 이메일 확인 완료 상태만 로컬 fixture로 준비한 뒤 `/api/accounts` 201, 정규화된 이메일과 `ACTIVE` 응답 확인 | PASS |
| 로그인 | 가입 계정으로 `/api/auth/sessions` 200 확인. 공백·대문자가 섞인 이메일도 같은 사용자로 로그인 | PASS |
| 상태 복원 | 새 `/api/auth/me` 요청이 동일 사용자로 200 반환 | PASS |
| 내 기록 보호·복귀 | `App.auth.test.tsx`, `ProtectedRoute.test.tsx`, `LoginPage.test.tsx`에서 비로그인 차단, 내부 하위 위치 1회 복귀, 외부 URL 거부 확인 | PASS |
| 세션 만료 | PostgreSQL 세션의 마지막 접근 시각을 만료 범위 밖으로 이동한 뒤 `/api/auth/me` 401 확인 | PASS |
| 로그아웃 | `/api/auth/sessions/current` 204 뒤 `/api/auth/me` 401 확인 | PASS |
| 연결 실패·로그아웃 미확인 | `AuthRecovery.test.tsx`, `LogoutFlow.test.tsx`에서 보호 정보 즉시 제거와 재시도/재확인 확인 | PASS |
| 탭 재활성화·페이지 캐시 복원 | `AuthRevalidation.test.tsx`, `AuthRecovery.test.tsx`에서 재확인과 공개 화면 유지 확인 | PASS |

실제 이메일 발송 성공은 운영 SMTP 자격 증명이 없어 수행하지 않았다. 발송·확인 controller 204와
인증 lifecycle은 자동 테스트로 검증했고, 실제 가입 성공 흐름은 확인 완료 fixture부터 실행했다.
운영 배포 전 실제 SMTP 환경에서 전달·재발송·만료를 한 번 더 확인해야 한다.

## 화면 증거

- [데스크톱 로그인 1440×900](evidence/login-desktop-1440x900.png)
- [모바일 회원가입 360×800](evidence/signup-mobile-360x800.png)

데스크톱 로그인 화면은 폼과 안내가 한 화면 안에 표시됐다. **360px 회원가입 화면은 큰 소개 문구가
오른쪽 viewport 밖으로 잘려 가로 방향 반응형 기준을 충족하지 못했다.** 인증 폼 자체는 세로 흐름으로
표시되지만 quickstart의 “360px 이상에서 가로 overflow 없이 흐름 완료”는 현재 PASS로 판정할 수 없다.
이 검증 문서 작업에서는 화면 CSS를 함께 수정하지 않고 별도 FE 수정 대상으로 남긴다.

## 종합 판정

인증 기능·세션·보호 경로의 자동 및 로컬 API 흐름은 통과했다. T059의 시나리오 실행과 증거 기록은
완료했지만, 360px 시각 검증과 실제 SMTP 전달 검증은 release 전 후속 조치가 필요하다.

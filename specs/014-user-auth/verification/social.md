# Social Login Verification

**실행일**: 2026-10-01
**범위**: spec 014 소셜 로그인 확장(US6·US7, FR-020~FR-026, SC-012~SC-015)
**환경**: Java 21, Docker 29.6.2 PostgreSQL 16(Testcontainers), Node.js `v24.18.0`

## 판정 요약

| 구분 | 판정 | 근거 |
|---|---|---|
| 자동 검증(BE 단위·통합, FE 컴포넌트) | PASS | 아래 표. 실패 0, 소셜 suite skip 0 |
| 실제 제공자 계정 흐름(quickstart 시나리오 F~H) | **미실행** | 카카오·구글·네이버 개발자 앱 자격 증명이 없다. T096을 완료로 표시하지 않는다 |
| 360px·1440px 화면 | PASS(목 API) | 실제 Vite 화면을 CDP 모바일 에뮬레이션으로 측정. 아래 "화면 증거" |

실제 제공자와 통신하는 지점은 두 곳뿐이다(token 교환, 사용자 정보 조회). 통합 테스트는 이 두 지점만
가짜 빈으로 바꾸고, 시작 요청·state 보관·콜백 검증·성공/실패 처리기·세션 저장은 운영과 같은 Spring
Security 필터와 PostgreSQL 세션을 그대로 지난다. 남은 위험은 제공자 응답 필드가 문서와 다를 가능성과
운영 redirect URI 등록이다.

## 자동 검증

| Suite | 종류 | tests | 확인하는 것 |
|---|---|---:|---|
| `SocialLoginServiceTest` | BE 단위 | 15 | 콜백 판정 7종과 `intent=link` 확인. 이메일만 같다고 로그인·연결하지 않는다 |
| `PendingSocialLoginServiceTest` | BE 단위 | 14 | 대기 상태 10분 만료·취소, 인증번호 6자리·3분·재발송 무효, 연결 대상 이메일로 발송, 가린 이메일 |
| `SocialSignupAndLinkServiceTest` | BE 단위 | 16 | 비밀번호 없는 가입, 번호 겹침 → 연결, 소유 확인 3방법, 다른 주소 번호 거부, 같은 제공자 충돌, 거절 후 가입 복귀 |
| `AuthenticationServiceTest` (추가 2) | BE 단위 | 12 | 비밀번호 없는 계정의 이메일 로그인 거절, 숫자 이름의 다른 인증을 사용자로 보지 않음 |
| `KakaoProviderAdapterTest` | BE 단위 | 6 | `id`·`kakao_account.email`·`is_email_valid`+`is_email_verified`·닉네임, `scope=account_email` 재동의 |
| `GoogleProviderAdapterTest` | BE 단위 | 6 | `sub`·`email`·`email_verified`(불리언·문자열), `prompt=consent` |
| `NaverProviderAdapterTest` | BE 단위 | 4 | `response.*`, 확인 여부 언제나 UNKNOWN, `auth_type=reprompt` |
| `SocialLoginCallbackTest` | BE 통합 | 14 | 실제 필터·세션 위의 콜백. 아래 SC 표 참고 |
| `SocialAuthContractTest` | BE 통합 | 9 | 소셜 JSON API 계약(성공·오류 봉투, CSRF 403, 세션 교체) |
| `SocialSignupPage.test.tsx` | FE | 9 | 읽기 전용 이메일·닉네임 미리 채움·형식 오류 초점·중복 제출 1회·번호 겹침 → 연결·만료 안내·이메일 확인 화면 |
| `SocialLinkPage.test.tsx` | FE | 9 | 가린 이메일·쓸 수 있는 방법만 표시·비밀번호/인증번호/확인된 이메일/소셜 확인·거절 경로 |
| `SocialNoticePages.test.tsx` | FE | 9 | 거절 화면 세 행동·같은 제공자 안내·완료 화면 복귀·로그인 `?social=` 안내와 주소 정리·켜진 제공자만 버튼 |
| `socialAuth.test.ts`, `returnTarget.test.ts` | FE | — | 시작 주소, 소셜 화면을 복귀 위치로 저장하지 않음 |

## Success Criteria 대조

| SC | 자동 검증으로 확인한 것 | 남은 것 |
|---|---|---|
| SC-012 | 카카오 경로로 신규 가입·재로그인(연결된 계정)·연결 확인 후 로그인·이메일 미제공 거절·사용자 취소가 각각 정해진 화면으로 끝난다(`SocialLoginCallbackTest`, `SocialAuthContractTest`). 구글·네이버는 어댑터 단위로 같은 `SocialProfile`을 만드는 것까지 확인했다 | 세 제공자의 실제 계정 흐름 |
| SC-013 | 같은 이메일·같은 번호 표본에서 소유 확인 없이 `social_accounts`가 저장된 사례 0(단위 테스트의 `never().save`, 통합 테스트의 DB 대조). 확인 뒤에는 연결된 계정으로 로그인 | 실제 계정 |
| SC-014 | 이메일 미제공 → 대기 상태 없음·로그인 안 됨·거절 화면 세 행동. 확인 여부 UNKNOWN·UNVERIFIED는 인증번호 확인 전 가입이 409 `SOCIAL_PENDING_STAGE_MISMATCH` | 카카오 선택 동의 거부의 실제 응답 |
| SC-015 | 콜백 이동 주소에 `code=`·`state=` 없음. 통합 테스트 로그(두 suite의 XML 출력)에서 인가 코드·access token 문자열 0건. token은 저장하지 않는 저장소를 쓴다. 위조·재사용 state는 로그인 안 됨. 만료·사용한 대기 상태로 계정 생성 0 | 운영 로그 확인 |

### spec 014 analyze G1 (콜백 뒤 남는 OAuth2 인증)

`SocialLoginCallbackTest`가 가입·연결 대기 판정 뒤 `/my-records`와 `/auth/me`가 401임을 확인한다. 구현 중
같은 문제가 한 겹 더 있는 것을 찾았다. 로그인 주체 판정(`AuthenticatedPrincipal`, `CurrentRequester`,
`LearningAdminAccess`)이 인증 이름을 숫자로 읽기만 해서, 카카오처럼 숫자인 제공자 사용자 식별자가 남의
`users.id`로 읽힐 수 있었다. 세 곳 모두 `UsernamePasswordAuthenticationToken`만 받게 고쳤고
`AuthenticationServiceTest`가 그 경계를 고정한다.

## 실제 제공자 확인 절차(남은 일)

1. 카카오·구글·네이버 개발자 콘솔에 앱을 만들고 redirect URI
   `{THORP_PUBLIC_BASE_URL}/api/auth/oauth2/callback/{kakao|google|naver}`를 등록한다.
2. `backend/.env`(로컬)나 `.env.prod`(운영)에 `KAKAO_CLIENT_ID`·`KAKAO_CLIENT_SECRET` 등과 `THORP_PUBLIC_BASE_URL`을 넣는다.
3. 카카오는 [동의항목]에서 카카오계정(이메일)을 켠다. 필수 동의는 비즈 앱 전환이 필요할 수 있다.
4. quickstart 시나리오 F~H를 데스크톱과 360px에서 실행하고 이 문서에 결과와 화면을 더한다.
5. 끝나면 tasks.md의 T096을 완료로 표시한다.

## 2026-10-01 코드·보안 리뷰 반영

`/code-review high`(10건)와 보안 리뷰(신뢰도 8 이상 0건, 보강 3건)를 코드와 대조해 고쳤다.

| 지적 | 판정 | 조치 | 확인 |
|---|---|---|---|
| 연결 단계 인증번호·비밀번호 무제한 대입 → 계정 탈취 | 진짜 | 번호 5회·비밀번호 5회·발송 5회·간격 30초, DB 행 잠금 아래 대조 전 계수 | `parallelCodeGuessesAreCappedUnderRowLock`: 같은 세션 20개 동시 요청 중 대조 정확히 5회, 이후 맞는 번호도 거절, 연결 0건 |
| 위 수정의 연결 풀 교착(바깥 트랜잭션 + REQUIRES_NEW) | 수정 중 발견 | `link`에서 바깥 트랜잭션 제거 | 같은 동시성 테스트(수정 전 30초 타임아웃) |
| 인증번호 메일 폭탄 | 진짜 | 발송 간격·횟수 상한 | `resendTooSoonIsRejected`(429), `sendLimitDropsPending` |
| 상대 redirect가 로컬에서 백엔드 포트로 | 진짜 | 서비스 주소 기준 절대 주소 | 콜백 테스트 전부가 `http://localhost:5173/...`를 단언 |
| 다른 탭 로그인이 콜백으로 지워짐 | 진짜 | 로그인 세션의 콜백은 소셜 필터 앞에서 `/home` | `callbackIntoSignedInSessionKeepsLogin` |
| 경합 재판정에서 Thorp 이메일 확인 유실 | 진짜 | `emailCheckedByThorp`를 대기 상태가 들고 다님 | 단위 테스트 |
| 운영 서비스 주소 누락이 조용히 통과 | 진짜 | 제공자가 켜졌는데 비면 기동 실패 | — |
| 공용 PC에 남은 연결 대기 상태(보안 리뷰) | 보강 | 이메일 로그인 시 대기 상태 제거 | `emailLoginDropsLeftoverPendingLink`, 단위 테스트 |
| 번호로 계정의 가린 이메일·연결 제공자가 보임 | spec이 정한 동작 | 유지. 별도 보안 작업에서 다시 본다 | — |
| 주체 판정·코드 생성·제공자 확인 중복 | 정리 | `SessionUser`, `VerificationCodes`, `existsByUserIdAndProvider` | 전체 테스트 |

남은 위험: 공격자가 제공자 로그인을 처음부터 반복하면 대기 상태마다 새 기록으로 시작한다(번호당 5회 × 발송 5회). 대상 계정 단위
누적 제한은 spec Dependencies의 별도 보안 작업에서 정한다. 이메일 가입 경로(`email_verifications`)의 기존 인증번호에도 대조 상한이
없다 — 이번 변경 전부터 있던 문제라 같은 보안 작업으로 넘긴다.

## 화면 증거 (2026-10-01)

Vite 개발 서버에 목 API(`/auth/social/providers`·`/auth/social/pending`·`/auth/me` 401)를 붙여 실제 화면을 띄우고,
Chrome DevTools Protocol 모바일 에뮬레이션(360×800, `mobile: true`)과 데스크톱(1440×900)에서 8개 화면을 측정·캡처했다.
headless 창 크기로 360px를 지정하면 Chrome의 최소 창 폭 때문에 넓게 그려 잘리므로 에뮬레이션으로 쟀다.

| 화면 | 360px 가로 넘침 | 첫 초점 |
|---|---|---|
| `/login`(소셜 버튼) · `/signup`(소셜 버튼) | 없음 | `H1` |
| `/signup/social` · `/auth/social/verify-email` · `/auth/social/link` | 없음 | `H1` |
| `/auth/social/email-required` · `/auth/social/already-linked` · 만료(`SOCIAL_PENDING_NOT_FOUND`) | 없음 | `H1` |

캡처는 `evidence/social-*-mobile-360.png`, `evidence/social-*-desktop-1440.png`다. 확인 중 로그인·가입 화면의 "또는 간편
로그인" 구분선이 제출 버튼에 붙어 있어 여백을 더했다.

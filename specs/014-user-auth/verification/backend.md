# Backend Verification

**실행일**: 2026-09-16  
**환경**: Java 21, Docker PostgreSQL 16 (`thorp-postgres` healthy)

## 실행 명령

```powershell
$env:THORP_REQUIRE_DOCKER = 'true'
.\gradlew.bat spotlessApply build
```

`THORP_REQUIRE_DOCKER=true`로 실행해 Docker가 없거나 PostgreSQL 기반 검증이 skip되면 빌드가
실패하도록 했다.

## 실행 결과

| 항목 | 결과 |
|---|---|
| Spotless apply/check | PASS |
| Compile/package | PASS |
| 전체 테스트 | 1,272 tests, failures 0, errors 0, skipped 1 |
| Gradle build | PASS (`14m 39s`) |

유일한 skip은 인증과 무관하고 외부 실서비스 키를 요구하는
`com.ssafy.thorp.market.MarketLiveApiTest`다. 인증 및 PostgreSQL 검증은 skip 없이 실행됐다.

| 핵심 suite | tests | 결과 |
|---|---:|---|
| `AccountControllerContractTest` | 9 | PASS |
| `AuthenticationControllerIntegrationTest` | 9 | PASS |
| `LogoutIntegrationTest` | 7 | PASS |
| `AuthSessionLifecycleIntegrationTest` | 9 | PASS |
| `ProxySecureCookieIntegrationTest` | 3 | PASS |
| `FlywayMigrationTest` | 28 | PASS |

위 suite는 계정 내부 경로, CSRF, 로그인과 현재 사용자 복원, PostgreSQL 세션 저장·만료,
로그아웃 멱등성, 운영 Secure cookie, Spring Session 마이그레이션을 실제 PostgreSQL 위에서 검증한다.

T057의 formatting과 PostgreSQL-backed test 검증은 통과했다.

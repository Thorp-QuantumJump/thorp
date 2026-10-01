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

## 2026-10-01 소셜 로그인 확장

**실행일**: 2026-10-01  
**환경**: Java 21, Docker 29.6.2 PostgreSQL 16(Testcontainers)

```powershell
$env:THORP_REQUIRE_DOCKER = 'true'
.\gradlew.bat spotlessCheck build
```

| 항목 | 결과 |
|---|---|
| Spotless check | PASS |
| ArchUnit(`ArchitectureTest`) | PASS, 11 rules |
| 전체 테스트 | 2,305 tests, failures 0, errors 0, skipped 4 |
| Gradle build | PASS (`4m 3s`) |

skip 4건은 외부 실서비스 키나 성능 환경을 요구하는 `AssistantLiveTest`·`MarketLiveApiTest`·`NewsPerformanceIT`이고
소셜·인증 suite에는 skip이 없다. 소셜 suite별 수와 확인 내용은 [social.md](social.md)에 있다. 마이그레이션
`V20261001120000__social_accounts.sql`은 `FlywayMigrationTest`(빈 DB 적용)와 `ddl-auto=validate` 컨텍스트에서 확인됐다.

### 2026-10-01 코드·보안 리뷰 반영 뒤 재실행

| 항목 | 결과 |
|---|---|
| 전체 테스트 | 2,317 tests, failures 0, errors 0, skipped 4(위와 같은 외부 실서비스·성능 suite) |
| Gradle build | PASS (`10m 7s`) |

추가된 확인: 병렬 인증번호 대입 상한(PostgreSQL 행 잠금), 다시 받기 429, 로그인 세션 콜백 보호, 이메일 로그인 시 소셜 대기 상태 제거,
`V20261001150000__social_pending_verifications.sql` 적용. 상세는 [social.md](social.md)의 리뷰 반영 절.

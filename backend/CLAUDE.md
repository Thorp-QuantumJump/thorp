# CLAUDE.md — backend (BACK 파트)

Spring Boot 3.5 / Java 21 / Gradle. 백테스트 Job 생성·상태·결과 API. **백테스트 계산은 하지 않는다** — Job을 브로커(현재 RabbitMQ 예정, Kafka는 설치만)에 넣으면 Worker가 처리. 브로커 결정은 루트 `CLAUDE.md`를 따른다.

## 검증 명령 (작업 후 반드시 실행)

```bash
./gradlew spotlessApply build
```

## 패키지 규칙 (`com.ssafy.thorp`)

근거: [ADR-0001](../docs/adr/0001-be-layered-package-architecture.md). 규칙은 `ArchitectureTest`(ArchUnit)가 검증하며, 위반 시 빌드가 실패한다.

### 도메인별 4계층

도메인 패키지(`backtest/`, `strategy/`, …) 안에 아래 4계층을 둔다. 계층별 상위 폴더는 만들지 않는다.

| 계층 | 하위 패키지 | 역할 |
|---|---|---|
| `presentation` | `controller` `request` `response` | HTTP 입출력. `@RestController`는 여기에만. request→command, result→response 변환은 컨트롤러가 한다 |
| `application` | `service` `command` `result` `repository` `port` | 유스케이스 조합·트랜잭션 경계. `repository`·`port`는 인터페이스만 둔다 |
| `domain` | `model` `vo` `exception` | 순수 Java 도메인 모델과 불변식. Spring·JPA·Jackson import 금지 |
| `infrastructure` | `persistence` `messaging` | `application` 인터페이스의 구현. `@Entity`·Spring Data·브로커 클라이언트는 여기에만 |

### 공통 (`common/`)

- `common/web` 응답 포맷(`ApiResponse`, `ErrorResponse`)·전역 예외 처리(`GlobalExceptionHandler`)
- `common/exception` `BusinessException`, `ErrorCode`
- `common/persistence` `BaseEntity`(createdAt, updatedAt)
- `common/config` 공통 설정(`JpaAuditingConfig` 등). 최상위 `config/`는 두지 않는다.
- `common`은 어떤 도메인 패키지도 참조하지 않는다.

### 의존 방향 (허용되는 화살표만)

```
presentation ──▶ application ──▶ domain
                    ▲                ▲
infrastructure ─────┴────────────────┘
common: 모든 계층에서 참조 가능
```

- 도메인 모델(`domain/model`)과 JPA Entity(`infrastructure/persistence/*JpaEntity`)는 분리하고 `*Mapper`(정적 `toEntity`/`toDomain`)로 변환한다.
- 컨트롤러 DTO(`request`/`response`)를 서비스 시그니처에 쓰지 않는다. 서비스는 `command`/`result`만 받고 돌려준다.

### 새 도메인 추가 체크리스트

1. `com.ssafy.thorp.{domain}/` 아래 `presentation` `application` `domain` `infrastructure` 폴더 생성
2. 리포지토리·외부 발행은 `application/repository`·`application/port`에 인터페이스, `infrastructure`에 구현
3. `./gradlew test`로 `ArchitectureTest` 통과 확인 — 규칙은 `layeredArchitecture()` 기반이라 새 도메인에 자동 적용된다. 별도 등록 불필요
4. 구조를 벗어나야 하면 코드가 아니라 ADR을 먼저 갱신한다

## 규칙

- DTO는 record 우선, 요청 DTO에 `@Valid` 검증 필수
- 포맷은 Spotless(google-java-format AOSP)가 강제 — CI에서 spotlessCheck 실패 시 빌드 실패
- API JSON은 camelCase, 비동기 계약 준수: `POST /backtests` → 즉시 `{backtestId, status: QUEUED}` 반환
- 상태 흐름: `QUEUED → RUNNING → COMPLETED | FAILED` (Worker가 갱신, backend는 조회 제공)
- Job 메시지 스키마 변경 시 `worker/src/worker/models.py`와 반드시 동기화

## 도메인 용어

Job(백테스트 작업 단위), Strategy(전략), BacktestResult(수익률·MDD·Sharpe·거래 횟수)

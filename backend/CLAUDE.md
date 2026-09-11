# CLAUDE.md — backend (BACK 파트)

Spring Boot 3.5 / Java 21 / Gradle. 백테스트 Job 생성·상태·결과 API. **백테스트 계산은 하지 않는다** — Job을 브로커 RabbitMQ(ADR-0002로 확정, Kafka는 설치만)에 넣으면 Worker가 처리. 브로커 결정은 루트 `CLAUDE.md`와 ADR-0002를 따른다.

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
| `application` | `service` `command` `result` `repository` `port` `exception` | 유스케이스 조합·트랜잭션 경계. `exception`은 도메인 규칙 위반이 아닌 유스케이스 실패(외부 시스템 장애 등). `repository`·`port`는 인터페이스만 둔다. `port/message`는 브로커 메시지 DTO(순수 record, Jackson·Spring import 금지 — ArchUnit 규칙 7) |
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
- API JSON은 camelCase, 비동기 계약 준수: `POST /backtests` → 즉시 202 `{backtestId: "BT-20260908-000001", status: "QUEUED"}` 반환.
  `backtestId`는 문자열이며 DB PK(Long)는 노출하지 않는다(ADR-0002 D2). HTTP 계약과 예시는 `docs/specs/backtest-api/`
- 상태 흐름: `QUEUED → RUNNING → COMPLETED | FAILED` (Worker가 갱신, backend는 조회 제공). 메시지 계약 v2에는 `CANCELLED`(부분 결과)도 있으나 실행 모델(spec 004 T003) 전까지 `BacktestEventService`가 거절한다
- 브로커 메시지 계약의 기준은 `docs/specs/backtest-messaging/`(README + `examples/*.json`)이다. 스키마 변경은 픽스처 → `backtest/application/port/message` → `worker/src/worker/contracts/` 순서로 같은 MR에서 함께 고친다. 양쪽 round-trip 테스트가 같은 픽스처를 읽으므로 한쪽만 고치면 빌드가 실패한다
- 큐·exchange·routing key 이름은 `BacktestRabbitConfig` 상수(워커와 합의한 계약값). 프로퍼티로 빼지 않는다
- 메시지 JSON 규칙: 금액·비율은 문자열, null 값은 키 생략(수신은 "키 없음 == null"), `allocation.parameters`의 숫자는 그대로 통과. 직렬화는 `BacktestRabbitConfig.messageObjectMapper()`만 쓴다(웹 MVC ObjectMapper와 분리)
- 결과 저장 규칙(`backtest_result`·`backtest_trade`): **질의(필터·정렬·조인)에 쓰는 값만 컬럼, 표시용 값은 JSONB**. decimal은 예외 없이 JSONB — `NUMERIC` 컬럼은 scale을 임의로 정해야 하고 넘는 값을 조용히 반올림한다. JSON 컬럼 직렬화는 메시지 ObjectMapper가 맡아(`MessageJsonPersistenceConfig`) decimal이 문자열로 저장된다. 결과 도메인 모델은 두지 않고 계약 DTO(`EngineOutput` 등)를 그대로 저장한다
- 결과 Consumer(`BacktestEventListener`): 역직렬화 직후 `BacktestEventValidator`로 계약을 명시 검증한다. 계약 오류는 `AmqpRejectAndDontRequeueException`(재시도 후 DLQ), 일시 오류는 그대로 전파(재시도), 모르는 `runId`(요청/실행 분리 전까지 `backtestId` 값과 같다)·늦은 이벤트는 로그 후 정상 반환(ACK). 멱등성은 상태 전이(`BacktestJob.markRunning/markCompleted/markFailed`)로 보장하며 별도 처리 이력 테이블을 두지 않는다
- 결과 조회 API(`GET /backtests/{id}` + `/timeseries`·`/trades`·`/positions`): 계약과 예시는 `docs/specs/backtest-api/`이고 `BacktestResultApiIT`가 응답과 예시 파일의 동치를 단언한다. 상세는 실패해도 200(`status`·`error`로 전달), 하위 리소스는 결과가 없으면 409(`RESULT_NOT_READY` 재시도 가능 / `BACKTEST_FAILED` 무의미)이며 **빈 배열을 만들어 내지 않는다**. 최종 상태인데 결과 행이 없으면 500이다(저장 트랜잭션 불변식 위반)
- 응답 decimal은 필드마다 `@JsonSerialize(using = PlainBigDecimalSerializer.class)`로 문자열이 된다. **웹 ObjectMapper에 전역 등록하지 않는다** — 다른 도메인(`MarketResponse.value`)이 숫자→문자열로 바뀌어 그 계약이 깨진다. 전역 `NON_NULL`도 금지: 거래의 `exitDate`는 명시적 null이고 시계열의 `benchmarkReturn`만 키를 생략한다
- 결과 조회는 엔드포인트마다 읽을 컬럼이 달라 저장소 조회가 4개다(`findSummary`·`findTimeseries`·`findPortfolioSeries`·`findTrades`). `portfolio_series`는 5년치면 수백 KB이고 `@JdbcTypeCode(JSON)` 기본 타입은 지연 로딩이 없어 엔티티를 읽으면 무조건 딸려온다 — 읽지 않을 컬럼은 select에 넣지 않는다. native alias는 따옴표로 감싼다(PostgreSQL이 소문자로 접는다)
- 거래 응답의 `entryDate`는 저장된 `entryTime`(Instant)을 **거래소 현지(Asia/Seoul)** 기준으로 바꾼 값이다. UTC로 자르면 거래소를 늘릴 때 하루 밀린다
- 스키마 변경은 `db/migration/V{YYYYMMDDHHMMSS}__설명.sql`(PostgreSQL 문법)로 한다. **신규 마이그레이션은 연속 번호가 아니라 작성 시각 타임스탬프를 버전으로 쓴다**(예: `V20260911143000__etf_market_data.sql`). 여러 spec이 동시에 진행되면서 `V3`을 셋이 함께 적어 두는 충돌이 반복됐고, 타임스탬프는 브랜치끼리 겹치지 않는다. **이미 적용된 `V1`~`V3`은 그대로 둔다** — Flyway가 버전을 숫자로 정렬하므로 `1 < 3 < 20260911143000` 순서가 유지되고 기존 이력과 충돌하지 않는다. prod는 Flyway + `ddl-auto: validate`, local은 `ddl-auto: update`. DB 테스트는 `support/PostgresTestSupport`를 상속한다(PostgreSQL 컨테이너 + Flyway + validate). Docker가 꺼져 있으면 조용히 skip되므로 테스트 결과의 `skipped`를 확인한다

## 도메인 용어

Job(백테스트 작업 단위), Strategy(전략), BacktestResult(수익률·MDD·Sharpe·거래 횟수)

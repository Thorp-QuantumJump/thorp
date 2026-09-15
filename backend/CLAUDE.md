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
- 상태 흐름: `QUEUED → RUNNING → COMPLETED | FAILED | CANCELLED` (Worker 이벤트가 갱신, backend는 조회 제공). `CANCELLED`는 취소·실행 시간 한도로 끊긴 실행이며 부분 결과가 붙는다(`partial=true`). **요청(`backtest_request`)과 실행(`backtest_run`)은 다대일이다** — 같은 조건의 요청은 진행 중 실행에 합류하거나 완료 결과를 재사용한다(spec 004 R-02). 접수 경로는 T005에서 그 모델로 바뀌었다 — 사용자가 보는 `backtestId`는 **요청**의 것이고, 이벤트 envelope의 `runId`는 **실행**의 것이라 값이 다르다
- 브로커 메시지 계약의 기준은 `docs/specs/backtest-messaging/`(README + `examples/*.json`)이다. 스키마 변경은 픽스처 → `backtest/application/port/message` → `worker/src/worker/contracts/` 순서로 같은 MR에서 함께 고친다. 양쪽 round-trip 테스트가 같은 픽스처를 읽으므로 한쪽만 고치면 빌드가 실패한다
- 비용 조건(`cost_profile`)·전략 규칙(`strategy_rules`)은 **마이그레이션이 채우는 읽기 전용 등록표**다. 실행은 버전 문자열만 들고 다니고 값은 등록표에서 읽는다. 값이 바뀌면 행을 고치지 말고 새 버전을 넣는다 — 끝난 실행을 나중 값으로 재해석하면 재현이 깨진다. 팀 미확정 전략은 `PENDING_DECISION` 행만 두고 접수에서 422 `RULES_NOT_REGISTERED`
- `BacktestRunJpaEntity`는 마이그레이션이 만든 컬럼 전부를 매핑하지 않는다. 접수·재사용·취소·측정이 쓰는 컬럼은 그 코드와 같은 MR에서 매핑을 붙인다. `ddl-auto=validate`는 매핑된 컬럼만 보므로 **미매핑 컬럼의 존재는 `FlywayMigrationTest`가 지킨다**
- **`AFTER_COMMIT` 안에서 DB를 건드릴 때는 새 트랜잭션(`REQUIRES_NEW`)을 연다.** 그 시점의 원래 트랜잭션은 커밋됐지만 자원이 아직 묶여 있어서, 기본 전파로 열면 끝난 트랜잭션에 참여해 **변경이 다시 커밋되지 않는다**. 조용히 사라지므로 테스트가 없으면 알아채지 못한다
- **한 행을 여러 경로가 갱신하면 컬럼 소유를 가른다.** 실행 행은 이벤트 처리(상태·진행·시작/종료 시각)와 발행 경로(`published_at`·`publish_count`)가 서로 다른 스레드에서 동시에 쓴다. 도메인 객체를 읽어 통째로 저장하면 늦게 저장한 쪽이 상대의 갱신을 되돌린다 — 저장소에 경로별 메서드를 두고 자기 컬럼만 쓴다(`update` / `recordPublishAttempt` / `recordPublished`). 카운터는 읽어서 더하지 말고 DB에서 올린다. **자바 쪽 필드를 가르는 것만으로는 부족하다** — Hibernate 기본 UPDATE는 바꾸지 않은 컬럼까지 함께 쓰므로, 영속성 컨텍스트에 올라온 시점의 옛 값이 그대로 나간다. 그런 엔티티에는 `@DynamicUpdate`를 건다(`BacktestRunJpaEntity`). 재현 테스트는 컨텍스트를 비우지 말 것 — 비우면 저장 시점에 최신 값을 다시 읽어 사고가 숨는다
- **작업 발행은 접수 트랜잭션이 커밋된 뒤에 한다**(spec 004 R-18, `BacktestJobDispatcher`). 트랜잭션 안에서 발행하면 워커의 STARTED가 커밋을 앞질러 도착해 결과 Consumer가 모르는 실행이라며 버린다. 브로커에 닿지 못해도 접수는 202이고 실행은 `QUEUED`·`published_at NULL`로 남아 `JobRepublisher`(30초)가 다시 보낸다. `publish_count`는 **confirm 수가 아니라 발행 시도 수**이며 시도 직전에 올린다 — 실제 메시지 수의 상한이어야 DLQ 종결 판정이 살아 있는 실행을 끊지 않는다
- **동일 버전 키(`DedupKeyCalculator`)는 소수·날짜를 맵에 넣기 전에 문자열로 정규화한다.** 직렬화 설정에 표기를 맡기면 그 설정이 바뀐 날 모든 키가 조용히 바뀌고 진행 중이던 재사용 판정이 어긋난다. 메시지 ObjectMapper를 빌려 쓸 수 없다(infrastructure에 있다). 키에 **실효 워밍업 거래일 수**를 반드시 넣는다 — 빠지면 공통 워밍업이 다른 두 실행이 한 키로 묶인다
- **요청 워밍업이 전략 요구값보다 작으면 `max()`로 올리지 말고 422로 거절한다**(R-15). 올려서 통과시키면 비교 묶음 안 실행들의 `effectiveStart`가 어긋난 채 지나가고, 나란히 놓고 본 숫자가 사실은 다른 구간의 값이 된다
- **요청자 확인은 상세뿐 아니라 하위 리소스에도 적용한다.** 상세만 막고 시계열·거래·보유를 열어 두면 식별자만 알면 남의 결과를 읽을 수 있고, 개발용 헤더를 끈 운영에서도 그 경로만 열려 있게 된다
- **요청자 식별은 `CurrentRequester` 한 곳이다.** 지금은 `thorp.auth.trust-user-id-header`를 켠 환경에서만 `X-User-Id` 헤더를 읽고 기본값은 꺼짐이다 — **헤더를 믿는 것은 인증이 아니다.** spec 014가 들어오면 이 클래스만 인증 주체를 읽도록 바꾼다. 남의 요청은 403이 아니라 404로 숨긴다(R-12)
- **시장 자료 메타데이터(대응표·자료 버전·상장일·거래일 달력)는 `MarketMetadataPort`로만 읽는다.** 원본은 spec 001 소유이고 구현은 001 적재 전까지 픽스처다(`FixtureMarketMetadataAdapter`, 워커 픽스처와 같은 세 종목·평일 달력). 가격은 이 포트로 읽지 않는다 — 가격을 읽는 것은 워커다
- 큐·exchange·routing key 이름은 `BacktestRabbitConfig` 상수(워커와 합의한 계약값). 프로퍼티로 빼지 않는다
- **취소는 요청 단위다**(`BacktestCancelService`, R-04). 이 요청에 `cancelled_at`을 남기고, 같은 실행의 마지막 활성 요청일 때만 실행을 멈춘다 —
  `QUEUED`면 바로 `CANCELLED`, `RUNNING`이면 `run.cancel_requested_at`을 기록하고 커밋 뒤 `BacktestCancelDispatcher`가 CANCEL을 보낸다(작업
  발행과 같은 커밋 후 발행 — 트랜잭션 안에서 보내면 발행됐는데 커밋이 실패해 아무도 취소하지 않은 실행이 멈춘다). 발행 실패는 삼키고 유예 감시(T014)가
  `cancel_requested_at`을 보고 다시 보낸다. **잠금 순서는 요청 → 실행이다**(`BacktestRequestRepository.findByBacktestIdForUpdate` → `BacktestRunRepository.findByIdForUpdate`). 실행만 잠그면
  같은 요청을 두 번 취소할 때 뒤엣것이 잠금 전에 읽어 둔 옛 요청을 보고 "아직 활성"이라 판단해, 남은 요청이 있는데도 실행을 멈춘다. 요청 잠금은 그
  이중 취소를, 실행 잠금은 마지막 활성 요청 판정을 직렬화한다. 합류 요청 하나만 취소하면 스냅샷(`cancelled_last_trading_date`)만 남기고 실행은 계속 돈다.
  **접수도 합류 직전에 같은 실행 행을 잠그고 최신 상태를 다시 본다** — `cancel_requested_at`이 있으면 `409 CANCEL_PENDING`(합류시키면 취소하지 않은
  요청까지 부분 결과로 끝난다), 잠근 뒤 종결된 실행이면 재사용 대상이 아니라 새 실행을 만든다. **`*ForUpdate` 조회는 잠근 뒤 `refresh` 한다** —
  잠금만으로는 영속성 컨텍스트의 1차 캐시가 이겨 그 사이 커밋된 값을 놓친다(`BacktestCancelFlowIT`가 그 순서를 고정한다). **워커 이벤트의 상태 전이도 같은 실행 행을 잠근다**
  (`BacktestRunRepository.findByBacktestIdForUpdate`) — 이벤트끼리는 단일 Consumer라 직렬이지만 취소는 HTTP 스레드라 그 밖에 있어서,
  잠그지 않으면 STARTED가 QUEUED를 읽은 직후 커밋된 취소를 늦은 UPDATE가 RUNNING으로 덮는다. 대기 중
  취소된 실행의 STARTED가 뒤늦게 오면 `handleStarted`가 CANCEL을 다시 보낸다. **`cancel_requested_at`이 있는 RUNNING 실행의 STARTED도
  마찬가지다** — 재전달(R-06)이면 워커가 실행을 끝낼 때 레지스트리의 취소 표시를 지웠으므로, 다시 보내지 않으면 취소한 실행이 끝까지 계산돼
  COMPLETED가 된다. `FINISHED(CANCELLED)`는 부분 결과를 저장하고 실행을 `CANCELLED·partial`로 끝낸다
- **실행 한도는 접수만 막고, 도는 것은 스위퍼가 끊는다**(`ExecutionLimitPolicy`·`RunLivenessSweeper`, R-19). `thorp.backtest.limits.*` 다섯 값은
  모두 `@NotNull`이라 하나라도 비면 기동에 실패하고, **prod는 `${ENV}` 참조에 기본값을 두지 않아** 공통 설정의 임시값을 물려받지 않는다(FR-006을
  설정 누락으로 우회할 수 없게 한 것이다). **두 대기 한도는 `SINGLE`만 센다** — 탐색·경제환경 파생 실행은 다른 큐(`backtest.jobs.bulk`)로
  가고 한도의 대상이 아니다(R-20). 한 큐였을 때는 탐색을 세면 007 묶음이 영구 정지하고(`advance()`는 `BacktestFinished`로만 불린다) 안 세면
  탐색이 큐를 채우는 동안 단건만 429를 받았다 — 값이 아니라 구조 문제라 큐를 나눴다. `ExecutionLimitPolicy`는 비단건이면 잠금도 조회도 없이 통과한다.
  세는 구간은 `pg_advisory_xact_lock`으로 직렬화하고 **잠금 순서는 사용자 키 → 전체 키**다. **동시 실행 상한은 백엔드에 두지 않는다** — 접수
  시점 검사로는 보장되지 않고 실제 상한은 워커 replica × prefetch(1)이다
- **실행 측정은 종결 세 지점이 `ExecutionMetricRecorder.record(run, resource)`를 직접 부른다**(R-10, T012) — `handleFinished`·
  `handleDeadLetter`·`RunLivenessSweeper.settleOne`. `BacktestFinished` 이벤트를 구독하지 않는다: 그 이벤트는 007 을 위한 경계 신호라 FINISHED 의
  `resource`가 없다. 종결 UPDATE 와 같은 트랜잭션이고 `run_id` UNIQUE + `ON CONFLICT DO NOTHING`으로 한 번만 남는다. **호출자는 `finished(now)`까지
  반영한 실행을 넘긴다.** 한도 도달(429·실행 시간 상한 CANCEL)은 실행 행이 없거나 아직 끝나지 않아 `backtest_limit_hit` 별표다. 집계 SQL 은
  `ExecutionMetricRepositoryImpl.SUMMARY_BODY` 하나고 뷰 `v_run_metric_summary`는 그 범위 없는 판이다 — 뷰를 고치면 상수도 고친다
  (`ExecutionMetricsApiIT`가 대조). STARTED `resource.queue`·`role`은 `backtest_run.served_queue`·`worker_role`에 native 전용 UPDATE 로 남고
  종결 때 복사된다. 운영자 경로 `/internal/**`는 `OperatorGate`(`X-Ops-Token` ↔ `thorp.ops.token`, 없으면 404)가 막는다 — spec 014 가 오면 이
  클래스만 바꾼다
- **정체 판정은 `last_event_at`으로 한다.** `updated_at`으로 대신하면 발행 기록이 갱신될 때도 올라가 멈춘 실행이 살아 있는 것처럼 보인다 —
  이 셈이 틀리는 방향은 "안 잡는 쪽"이라 워커가 묶인 것을 영영 못 잡는다. `cancel_reason`도 함께 남긴다: 강제 종결 시점에 조건을 다시 계산해
  사유를 추론하면 그 사이 설정이 바뀌었을 때 사유가 뒤바뀐다. **스위퍼는 건마다 트랜잭션을 연다** — CANCEL 발행이 `AFTER_COMMIT`이라
  트랜잭션이 없으면 이벤트가 조용히 버려진다
- 메시지 JSON 규칙: 금액·비율은 문자열, null 값은 키 생략(수신은 "키 없음 == null"), `allocation.parameters`의 숫자는 그대로 통과. 직렬화는 `BacktestRabbitConfig.messageObjectMapper()`만 쓴다(웹 MVC ObjectMapper와 분리)
- 결과 저장 규칙(`backtest_result`·`backtest_trade`): **질의(필터·정렬·조인)에 쓰는 값만 컬럼, 표시용 값은 JSONB**. decimal은 예외 없이 JSONB — `NUMERIC` 컬럼은 scale을 임의로 정해야 하고 넘는 값을 조용히 반올림한다. JSON 컬럼 직렬화는 메시지 ObjectMapper가 맡아(`MessageJsonPersistenceConfig`) decimal이 문자열로 저장된다. 결과 도메인 모델은 두지 않고 계약 DTO(`EngineOutput` 등)를 그대로 저장한다
- 결과 Consumer(`BacktestEventListener`): 역직렬화 직후 `BacktestEventValidator`로 계약을 명시 검증한다. 계약 오류는 `AmqpRejectAndDontRequeueException`(재시도 후 DLQ), 일시 오류는 그대로 전파(재시도), 모르는 `runId`(요청/실행 분리 전까지 `backtestId` 값과 같다)·늦은 이벤트는 로그 후 정상 반환(ACK). 멱등성은 상태 전이(`BacktestJob.markRunning/markCompleted/markFailed`)로 보장하며 별도 처리 이력 테이블을 두지 않는다
- 결과 조회 API(`GET /backtests/{id}` + `/timeseries`·`/trades`·`/positions`·`/weights`·`/exceptions`): 계약과 예시는 `docs/specs/backtest-api/`이고 `BacktestResultApiIT`가 응답과 예시 파일의 동치를 단언한다. 하위 리소스는 `BacktestResultQueryService.requireResultReady`가 돌려주는 `ResultView` 하나를 관문으로 쓴다 — 먼저 취소한 요청의 스냅샷 자르기(R-04)와 `partial` 판정이 거기 있으니 엔드포인트마다 다시 쓰지 말 것. 상세는 실패해도 200(`status`·`error`로 전달), 하위 리소스는 결과가 없으면 409(`RESULT_NOT_READY` 재시도 가능 / `BACKTEST_FAILED` 무의미)이며 **빈 배열을 만들어 내지 않는다**. 최종 상태인데 결과 행이 없으면 500이다(저장 트랜잭션 불변식 위반). **예외는 `progress.lastTradingDate`가 없는 CANCELLED 하나뿐이다**
  (`CANCELLED_BEFORE_START` — 워커가 한 거래일도 돌지 않았다). 계산한 거래일이 있는 CANCELLED를 여기 끼워 주면 실제 결과 유실이 200으로 숨는다
- 응답 decimal은 필드마다 `@JsonSerialize(using = PlainBigDecimalSerializer.class)`로 문자열이 된다. **웹 ObjectMapper에 전역 등록하지 않는다** — 다른 도메인(`MarketResponse.value`)이 숫자→문자열로 바뀌어 그 계약이 깨진다. 전역 `NON_NULL`도 금지: 거래의 `exitDate`는 명시적 null이고 시계열의 `benchmarkReturn`만 키를 생략한다
- 결과 조회는 엔드포인트마다 읽을 컬럼이 달라 저장소 조회가 5개다(`findSummary`·`findTimeseries`·`findPortfolioSeries`·`findTrades`·`findBlocks`). 비중·예외 블록은 KB 단위라 `findBlocks` 하나로 함께 읽고, 예외 건수는 `jsonb_array_length`로 요약 조회에 얹는다. `portfolio_series`는 5년치면 수백 KB이고 `@JdbcTypeCode(JSON)` 기본 타입은 지연 로딩이 없어 엔티티를 읽으면 무조건 딸려온다 — 읽지 않을 컬럼은 select에 넣지 않는다. native alias는 따옴표로 감싼다(PostgreSQL이 소문자로 접는다)
- 거래 응답의 `entryDate`는 저장된 `entryTime`(Instant)을 **거래소 현지(Asia/Seoul)** 기준으로 바꾼 값이다. UTC로 자르면 거래소를 늘릴 때 하루 밀린다
- 스키마 변경은 `db/migration/V{YYYYMMDDHHMMSS}__설명.sql`(PostgreSQL 문법)로 한다. **신규 마이그레이션은 연속 번호가 아니라 작성 시각 타임스탬프를 버전으로 쓴다**(예: `V20260911143000__etf_market_data.sql`). 여러 spec이 동시에 진행되면서 `V3`을 둘이 함께 적어 두는 충돌이 실제로 났고, 타임스탬프는 브랜치끼리 겹치지 않는다. **이미 적용된 `V1`~`V4`는 그대로 둔다** — Flyway가 버전을 숫자로 정렬하므로 `1 < 4 < 20260911143000` 순서가 유지되고 기존 이력과 충돌하지 않는다. prod는 Flyway + `ddl-auto: validate`, local은 `ddl-auto: update`
- **운영에 이미 적용된 마이그레이션은 고치지 않는다.** 아직 어느 운영 환경에도 적용되지 않은 파일은 병합 뒤라도 버전을 올리거나 정리할 수 있다(2026-09-11 V3 중복을 V4로 푼 것이 그 경우다). 근거는 constitution "스키마 변경 규칙"
- 스키마 변경·Entity 매핑·검증 테스트는 **같은 MR**에 담는다. prod가 `ddl-auto: validate`라 셋 중 하나만 병합되면 그 시점부터 배포가 실패하고, 원인이 방금 병합한 MR이 아니라 이전 MR에 있어 찾기 어렵다
- 데이터를 지우거나 기존 값을 무효로 만드는 변경(테이블·컬럼 삭제, TRUNCATE, 타입 변경, 기존 행이 위반하는 제약 추가)은 MR 본문에 영향 범위를 적고 백업·승인 뒤 배포한다. 빈 DB 테스트는 이런 변경을 전부 통과시키므로 자동 검증으로 걸러지지 않는다. `flyway clean`은 쓰지 않는다(prod `clean-disabled: true`, `ApplicationProfileConfigTest`가 단언)
- 마이그레이션 검증은 셋이다. **테스트에 버전 번호를 적지 않는다** — 적용 여부는 `support/MigrationFiles`가 읽은 파일 목록과 대조한다. 타임스탬프 버전은 14자리라 손으로 옮겨 적으면 오타가 난다
  - `common/persistence/MigrationFileConventionTest` 파일명 규칙과 버전 중복. **Docker 없이 돈다**
  - `common/persistence/FlywayMigrationTest`·`paramsearch/.../ParamSearchMigrationTest` 빈 DB 적용과 스키마 구조
  - `infra/scripts/check-migration-version.sh` 기준 브랜치 대비 버전 역전(out-of-order). 빈 DB 테스트로는 잡히지 않아 CI가 따로 돌린다. 타임스탬프 버전에서는 이 검사가 중복 검사보다 중요하다 — 파일명이 겹치지 않으니 Git이 조용히 병합하고, 낮은 버전이 뒤늦게 머지되면 운영에서만 기동이 막힌다
- DB 테스트는 `support/PostgresTestSupport`를 상속한다(PostgreSQL 컨테이너 + Flyway + validate). Docker가 꺼져 있으면 조용히 skip되므로 테스트 결과의 `skipped`를 확인한다. CI는 `THORP_REQUIRE_DOCKER=true`로 돌려 `support/RequiredDockerTest`가 그 상태를 실패로 만든다

## 도메인 용어

Job(백테스트 작업 단위), Strategy(전략), BacktestResult(수익률·MDD·Sharpe·거래 횟수)

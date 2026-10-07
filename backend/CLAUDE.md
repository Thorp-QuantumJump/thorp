# CLAUDE.md — backend (BACK 파트)

Spring Boot 3.5 / Java 21 / Gradle. 백테스트 Job 생성·상태·결과 API. **백테스트 계산은 하지 않는다** — Job을 브로커 RabbitMQ(ADR-0002로 확정, Kafka는 설치만)에 넣으면 Worker가 처리. 브로커 결정은 루트 `CLAUDE.md`와 ADR-0002를 따른다.

## 검증 명령 (작업 후 반드시 실행)

```bash
./gradlew spotlessApply build
```

## 로컬 실행 (`./gradlew bootRun`)

`backend/.env`가 필요하다. 없으면 `MAIL_USERNAME` 플레이스홀더를 풀지 못해 기동에 실패한다.
`application.yml`이 읽는 `optional:file:./.env`의 `./`는 `bootRun`의 작업 디렉터리인 `backend/`라서,
저장소 루트의 `.env`는 여기서 읽히지 않는다. 루트 `.env`는 docker-compose가 쓰는 파일이다.

```bash
# backend/.env — gitignore 대상이라 각자 만든다. 항목은 루트 .env.example 참고.
MAIL_USERNAME=...
MAIL_PASSWORD=...
```

메일 발송을 실제로 확인할 게 아니면 더미 값으로 충분하다. 값이 없을 때 기동이 실패하는 것은
의도된 동작이다 — 설정 누락을 조용히 넘기지 않는다.

DB와 브로커는 저장소 루트에서 띄운다.

```bash
docker compose -f docker-compose.dev.yml up -d postgres rabbitmq
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

- `result` 패키지는 공개 토큰 열람 경계다. `backtest`는 `result`를 참조하지 않는다(ArchUnit 규칙 9). 비로그인 요청은 `requester_id`가 NULL이고 `public_token_expires_at`이 필수다.
- 배우기 판(`learning_article_revision`)·게시 기록(`learning_publication_event`)·변경 기록(`learning_release_log`)은 추가만 한다. 저장소에 수정·삭제 메서드를 두지 않는다. 단계 공개일(`learning_stage.released_at`)과 글의 첫 공개 시각(`learning_article.first_released_at`)은 한 번 쓰고 비우지 않는다(단계 공개는 되돌리지 않는다).
- 나의 학습의 경험치 장부(`learning_exp_entry`)·첫 답(`learning_answer`)·받은 배지(`learning_badge_award`)도 추가만 하고(`ON CONFLICT DO NOTHING`), 지우는 경로는 학습 초기화(`deleteAllForUser`) 하나다. 네 테이블은 JPA 엔티티 없이 JdbcTemplate으로 쓰므로 구조는 `LearningMigrationTest`가 지킨다. **나의 학습을 쓰는 요청은 첫 문장에서 사용자 단위 잠금(`LearnerProgressRepository.lockLearner`, `pg_advisory_xact_lock`)을 잡는다.** 잠금 순서는 사용자 잠금 → 진행 행이다. 행 잠금만으로는 학습 초기화가 지우는 사이 다른 요청이 넣은 답·장부·배지가 초기화 뒤에 남는다(013 SC-015). 쓰는 경로를 새로 만들면 이 잠금을 같이 잡는다
- 배우기 게시·게시 취소·용어 넘기기·단계 공개는 글 상태 변경과 게시 용어 색인(`learning_published_term`) 갱신을 같은 트랜잭션에서 한다. 게시할 때는 색인을 새 판으로 다시 쓰고, 게시 취소할 때는 지우며, 넘기기는 옛 소유 글의 그 용어 행을 지운 뒤 새 글의 행을 쓴다. 한순간이라도 어긋나면 도움말 문장과 글 본문이 갈라진다(013 SC-003). **잠금 순서는 단계 행 → 글(게시할 글과 넘겨줄 옛 소유 글을 `articleId` 순서로 한 번에, `ArticlePublisher.lockWithGivers`)이다.** 글마다 게시와 단계 공개가 같은 순서를 지키고, 용어 소유 판정(`TermOwnership`)은 잠근 뒤에 한다. 받는 글을 먼저 잠그고 옛 글을 나중에 잠그면 두 글이 서로의 용어를 넘겨받을 때 교착이 나고, 잠그기 전에 판정하면 그사이 옛 글이 용어를 놓아 소유 글 없는 넘기기가 된다. 글마다 게시가 단계 행을 잠그지 않으면 공개가 도는 동안 그 단계로 옮긴 V1 글이 LEGACY 판으로 공개된 단계에 남는다.
- 배우기 관리 권한 판정은 `LearningAdminPolicy` 한 곳이다. 지금은 `thorp.learning.admin-user-ids` 허용 목록이고, 014가 역할을 주면 `ConfiguredLearningAdminPolicy`만 바꾼다.
- DTO는 record 우선, 요청 DTO에 `@Valid` 검증 필수
- 포맷은 Spotless(google-java-format AOSP)가 강제 — CI에서 spotlessCheck 실패 시 빌드 실패
- API JSON은 camelCase, 비동기 계약 준수: `POST /backtests` → 즉시 202 `{backtestId: "BT-20260908-000001", status: "QUEUED"}` 반환.
  `backtestId`는 문자열이며 DB PK(Long)는 노출하지 않는다(ADR-0002 D2). HTTP 계약과 예시는 `docs/specs/backtest-api/`
- 상태 흐름: `QUEUED → RUNNING → COMPLETED | FAILED | CANCELLED` (Worker 이벤트가 갱신, backend는 조회 제공). `CANCELLED`는 취소·실행 시간 한도로 끊긴 실행이며 부분 결과가 붙는다(`partial=true`). **요청(`backtest_request`)과 실행(`backtest_run`)은 다대일이다** — 같은 조건의 요청은 진행 중 실행에 합류하거나 완료 결과를 재사용한다(spec 004 R-02). 접수 경로는 T005에서 그 모델로 바뀌었다 — 사용자가 보는 `backtestId`는 **요청**의 것이고, 이벤트 envelope의 `runId`는 **실행**의 것이라 값이 다르다
- 브로커 메시지 계약의 기준은 `docs/specs/backtest-messaging/`(README + `examples/*.json`)이다. 스키마 변경은 픽스처 → `backtest/application/port/message` → `worker/src/worker/contracts/` 순서로 같은 MR에서 함께 고친다. 양쪽 round-trip 테스트가 같은 픽스처를 읽으므로 한쪽만 고치면 빌드가 실패한다
- 전략·파라미터 등록표(`registry/parameter-registry.yml`)는 `strategy` 도메인이 소유하고 `paramsearch`는 `ParameterRegistryPort`로만 읽는다. 값이 바뀌면 행을 고치지 말고 버전을 올린다(`cost_profile`·`strategy_rules`와 같은 규칙). **축의 `appliesWhen`(다른 축이 특정 값일 때만 쓰는 축)은 `StrategyRegistration.applicableAxes` 하나로 판정한다** — 조회·접수 관문·007 조합 생성이 각자 축 이름으로 가르지 않는다(003 FR-003·FR-005)
- **`POST /backtests`는 `strategy.registryVersion`이 있는 요청만 003 등록표로 검증한다**(400/409/422, `StrategySettingsValidator`, FR-006 미완료). 003 검증기 안에서는 버전 → 활성 전략 → 축 집합 → 값 범위 → 유효성 규칙 → 시작 금액 순으로 판정하고 값을 자르거나 빠진 축을 기본값으로 채우지 않는다. 위반 문구는 등록표의 `detail`을 쓴다.
  **남은 정합 작업**: 워커는 모멘텀·최소분산 축과 `maxWeight`를 이미 소비하지만, 003의 `rebalancePeriod`와 역변동성 `lookbackDays`는 아직 적용하지 않는다. 004·007 호환 요청에는 등록표 버전이 없는 경우도 있다. 전달 정합과 모든 요청의 등록표 검증 필수화가 끝나기 전에는 FR-006·SC-003을 완료로 보지 않는다. 의존 방향은 `backtest → strategy` 하나다.
- 비용 조건(`cost_profile`)·전략 규칙(`strategy_rules`)은 **마이그레이션이 채우는 읽기 전용 등록표**다. 실행은 버전 문자열만 들고 다니고 값은 등록표에서 읽는다. 값이 바뀌면 행을 고치지 말고 새 버전을 넣는다 — 끝난 실행을 나중 값으로 재해석하면 재현이 깨진다. 팀 미확정 전략은 `PENDING_DECISION` 행만 두고 접수에서 422 `RULES_NOT_REGISTERED`
- **전략 규칙을 바꿀 때는 옛 행을 `SUPERSEDED`로 두고 새 버전 행을 더한다**(spec 004 T029). 한 마이그레이션 안에서 UPDATE(옛 행 status) → INSERT(새 행) 순서다 — 전략마다 대체되지 않은 행은 하나라는 부분 유니크 인덱스(`uk_strategy_rules_current`)가 순서를 강제한다. 같은 MR에 명세 스냅샷(`docs/specs/backtest-messaging/examples/rules/{전략}-{버전}.json`)과 워커 골든을 더하고 옛 것은 지우지 않는다. `StrategyRulesSnapshotTest`가 DB 행과 스냅샷을 대조하므로 등록된 버전을 제자리에서 고치면 빌드가 깨진다. **검사는 둘이다** — 새 요청은 `StrategyRulesService.requireCurrent`(SUPERSEDED 409 `REGISTRY_VERSION_CHANGED`), 이미 접수된 실행(작업 조립·재시도)은 `requireExecutable`(SUPERSEDED 통과). **버전이 이미 고정된 요청** — 실패·취소한 요청의 재시도와 007 묶음 후보 — 은 `BacktestService.createPinned`(`PinnedVersions`)로 들어가 규칙은 `requireExecutable`, 003 등록표는 `StrategySettingsValidator.verifySaved`(지금과 다른 `registryVersion`이면 판정하지 않는다)로 본다. 007 후보를 새 요청(`create`)으로 넣으면 규칙이 대체된 날 후보 발행이 409를 던지고, 그 예외가 결과 반영 트랜잭션을 되돌려 탐색이 멈춘다. 목표 위험 오버레이처럼 요청이 버전을 싣지 않는 규칙은 새 요청만 지금 행을 고르고, 조립·재시도는 실행의 `versions.scenarioRules`(`RunPlan.scenarioRules()`, 표기는 `RulesRef` 한 곳에서 만들고 푼다)를 읽는다
- `BacktestRunJpaEntity`는 마이그레이션이 만든 컬럼 전부를 매핑하지 않는다. 접수·재사용·취소·측정이 쓰는 컬럼은 그 코드와 같은 MR에서 매핑을 붙인다. `ddl-auto=validate`는 매핑된 컬럼만 보므로 **미매핑 컬럼의 존재는 `FlywayMigrationTest`가 지킨다**
- **`AFTER_COMMIT` 안에서 DB를 건드릴 때는 새 트랜잭션(`REQUIRES_NEW`)을 연다.** 그 시점의 원래 트랜잭션은 커밋됐지만 자원이 아직 묶여 있어서, 기본 전파로 열면 끝난 트랜잭션에 참여해 **변경이 다시 커밋되지 않는다**. 조용히 사라지므로 테스트가 없으면 알아채지 못한다
- **한 행을 여러 경로가 갱신하면 컬럼 소유를 가른다.** 실행 행은 이벤트 처리(상태·진행·시작/종료 시각)와 발행 경로(`published_at`·`publish_count`)가 서로 다른 스레드에서 동시에 쓴다. 도메인 객체를 읽어 통째로 저장하면 늦게 저장한 쪽이 상대의 갱신을 되돌린다 — 저장소에 경로별 메서드를 두고 자기 컬럼만 쓴다(`update` / `recordPublishAttempt` / `recordPublished`). 카운터는 읽어서 더하지 말고 DB에서 올린다. **자바 쪽 필드를 가르는 것만으로는 부족하다** — Hibernate 기본 UPDATE는 바꾸지 않은 컬럼까지 함께 쓰므로, 영속성 컨텍스트에 올라온 시점의 옛 값이 그대로 나간다. 그런 엔티티에는 `@DynamicUpdate`를 건다(`BacktestRunJpaEntity`). 재현 테스트는 컨텍스트를 비우지 말 것 — 비우면 저장 시점에 최신 값을 다시 읽어 사고가 숨는다
- **상한 검사가 세는 것은 비중을 받는 종목의 수다**(`BacktestService.weightedCount`, PD-03·T023). 대부분의 전략은 고른 섹터 전부에 비중이 가지만 섹터 모멘텀만 그중 일부를 고른다 — `TOP_N`은 `topN`, `TOP_PERCENT`는 `ceil(N×p/100)`, `ALL`은 N이고 `THRESHOLD`는 접수 때 셀 수 없어 건너뛴다. 세는 N은 **요청의 투자 대상 섹터 수**이지 PD-01의 여덟이 아니다. 유효 상한은 `effectiveWeightCap`이 정한다 — 요청 `parameters.maxWeight`(정수 %)가 있으면 그것이고 없으면 규칙표 `weightCap`이다. **003 등록표에 같은 판정을 두지 않으면 007 묶음이 접수에서 500으로 죽는다**(`CandidateDispatcher`가 예외를 잡지 않는다) — 모멘텀은 `MAX_WEIGHT_TOO_LOW_FOR_SELECTION`(operand `multiplyByAxis`)이 그 자리다
- **작업 발행은 접수 트랜잭션이 커밋된 뒤에 한다**(spec 004 R-18, `BacktestJobDispatcher`). 트랜잭션 안에서 발행하면 워커의 STARTED가 커밋을 앞질러 도착해 결과 Consumer가 모르는 실행이라며 버린다. 브로커에 닿지 못해도 접수는 202이고 실행은 `QUEUED`·`published_at NULL`로 남아 `JobRepublisher`(30초)가 다시 보낸다. `publish_count`는 **confirm 수가 아니라 발행 시도 수**이며 시도 직전에 올린다 — 실제 메시지 수의 상한이어야 DLQ 종결 판정이 살아 있는 실행을 끊지 않는다
- **동일 버전 키(`DedupKeyCalculator`)는 소수·날짜를 맵에 넣기 전에 문자열로 정규화한다.** 직렬화 설정에 표기를 맡기면 그 설정이 바뀐 날 모든 키가 조용히 바뀌고 진행 중이던 재사용 판정이 어긋난다. 메시지 ObjectMapper를 빌려 쓸 수 없다(infrastructure에 있다). 키에 **실효 워밍업 거래일 수**를 반드시 넣는다 — 빠지면 공통 워밍업이 다른 두 실행이 한 키로 묶인다
- **요청 워밍업이 전략 요구값보다 작으면 `max()`로 올리지 말고 422로 거절한다**(R-15). 올려서 통과시키면 비교 묶음 안 실행들의 `effectiveStart`가 어긋난 채 지나가고, 나란히 놓고 본 숫자가 사실은 다른 구간의 값이 된다
- **요청자 확인은 상세뿐 아니라 하위 리소스에도 적용한다.** 상세만 막고 시계열·거래·보유를 열어 두면 식별자만 알면 남의 결과를 읽을 수 있다 — 로그인을 요구하는 경로와 요구하지 않는 경로가 섞이면 그 틈이 곧 우회로다
- **요청자 식별은 `CurrentRequester` 한 곳이다.** spec 014 로그인이 세션에 넣어 둔 주체(`authentication.getName()` = `users.id`)를 읽고, 그 형태가 아니면 로그인으로 보지 않는다. **인증 종류도 본다** — 로그인은 언제나 `UsernamePasswordAuthenticationToken`을 넣으므로 다른 종류는 받지 않는다. 소셜 로그인 필터가 잠깐 넣는 `OAuth2AuthenticationToken`의 이름은 제공자 사용자 식별자이고 카카오는 숫자라, 이름만 보면 남의 `users.id`로 읽힌다(`AuthenticatedPrincipal`·`LearningAdminAccess`·`LearnerAccess`도 같은 규칙). 백테스트 경로는 `SecurityConfig`에서 `permitAll`이라 익명 요청도 컨트롤러까지 오므로 **인증 요구는 이 클래스가 한다** — `requireRequesterId`가 던지는 401은 `GlobalExceptionHandler`를 거쳐 `SecurityConfig` 진입점과 같은 봉투가 된다. 소유자를 기록만 하는 경로(007 미리보기)는 `findRequesterId`로 없어도 진행한다. 남의 요청은 403이 아니라 404로 숨긴다(R-12)
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
- **실행 한도는 접수만 막고, 도는 것은 스위퍼가 끊는다**(`ExecutionLimitPolicy`·`RunLivenessSweeper`, R-19). `thorp.backtest.limits.*` 여섯 값은
  모두 `@NotNull`이라 하나라도 비면 기동에 실패하고, **prod는 `${ENV}` 참조에 기본값을 두지 않아** 공통 설정의 임시값을 물려받지 않는다(FR-006을
  설정 누락으로 우회할 수 없게 한 것이다). **두 대기 한도는 `SINGLE`만 센다** — 탐색·경제환경 파생 실행은 다른 큐(`backtest.jobs.bulk`)로
  가고 한도의 대상이 아니다(R-20). 한 큐였을 때는 탐색을 세면 007 묶음이 영구 정지하고(`advance()`는 `BacktestFinished`로만 불린다) 안 세면
  탐색이 큐를 채우는 동안 단건만 429를 받았다 — 값이 아니라 구조 문제라 큐를 나눴다. `ExecutionLimitPolicy`는 비단건이면 잠금도 조회도 없이 통과한다.
  세는 구간은 `pg_advisory_xact_lock`으로 직렬화하고 **잠금 순서는 사용자 키 → 전체 키**다. **비로그인 요청(spec 005 B01)은 사용자 키 자리에서
  비로그인 합계 키를 잡고 `max-queued-anonymous`를 센다** — 사용자별 한도만 건너뛰면 비로그인 클라이언트 하나가 전체 한도를 채워 로그인 사용자까지
  429 를 받는다. 이 몫은 비로그인 요청이 **새로 만든**(`result_source='NEW'`) 대기 실행만 센다. **동시 실행 상한은 백엔드에 두지 않는다** — 접수
  시점 검사로는 보장되지 않고 실제 상한은 워커 replica × prefetch(1)이다
- **접수 트랜잭션 안에서 `BusinessException`을 던지는 `@Transactional` 메서드는 모두 `noRollbackFor = BusinessException.class`다**
  (`BacktestService.create`·`BacktestService.createPinned`·`StrategyRulesService.requireCurrent`·`requireExecutable`·`CostProfileService.resolve`). 하나라도 기본 규칙이면 그 프록시가 바깥
  트랜잭션을 rollback-only로 표시해 `create`의 커밋이 `UnexpectedRollbackException`(500)으로 끝난다 — quickstart S9에서 422 대신 500이 난 원인.
  단위 테스트(목)로는 보이지 않으니 실제 프록시를 지나는 IT(`ExecutionLimitApiIT`)로 고정한다
- **실행 측정은 종결 네 지점이 `ExecutionMetricRecorder.record(run, resource)`를 직접 부른다**(R-10, T012) — `handleFinished`·
  `handleDeadLetter`·`RunLivenessSweeper.settleOne`·`BacktestCancelService`(실행 전 취소). 종결 경로를 새로 만들면 그곳도 불러야 한다 — 빠진 종결은
  늦은 FINISHED 가 최종 상태라 무시되어 측정에서 영영 빠진다. 큐·워커 역할은 FINISHED `resource.queue`·`role`이 먼저고(중복 실행에서 마지막 STARTED 의
  워커와 결과를 낸 워커가 다를 수 있다) STARTED 값은 FINISHED 없는 종결의 대체다. `BacktestFinished` 이벤트를 구독하지 않는다: 그 이벤트는 007 을 위한 경계 신호라 FINISHED 의
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
- 메시지 JSON 규칙: 금액·비율은 문자열, null 값은 키 생략(수신은 "키 없음 == null"), `allocation.parameters`의 숫자는 그대로 통과. 직렬화는 `BacktestRabbitConfig.messageObjectMapper()`만 쓴다(웹 MVC ObjectMapper와 분리). **사례 연결(011, `regimelink`)은 별도 계약이다** — 기준은 `docs/specs/regime-link-messaging/`이고 직렬화는 `RegimeLinkMessageJson.objectMapper()`를 쓴다. 백테스트 메시지 매퍼를 빌려 쓰지 않는다
- 결과 저장 규칙(`backtest_result`·`backtest_trade`): **질의(필터·정렬·조인)에 쓰는 값만 컬럼, 표시용 값은 JSONB**. decimal은 예외 없이 JSONB — `NUMERIC` 컬럼은 scale을 임의로 정해야 하고 넘는 값을 조용히 반올림한다. JSON 컬럼 직렬화는 메시지 ObjectMapper가 맡아(`MessageJsonPersistenceConfig`) decimal이 문자열로 저장된다. 결과 도메인 모델은 두지 않고 계약 DTO(`EngineOutput` 등)를 그대로 저장한다
- **개별 체결(`backtest_fill`)은 `fills`가 온 결과만 저장하고 `backtest_result.fills_recorded`로 표시한다**(spec 011 DEP-004-011). `fills`가 없는 결과(이 기능 전의 실행, 옛 워커)를 `[]`로 보정하지 않는다 — `findFills`는 그런 실행에 빈 Optional을, 기록했지만 그 구간에 체결이 없으면 빈 리스트를 돌려준다. 둘을 섞으면 구간 비용이 0으로 계산된다. 조회 API에는 노출하지 않는다
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
- DB 테스트는 `support/PostgresTestSupport`를 상속한다(PostgreSQL 컨테이너 + Flyway + validate). Docker가 꺼져 있으면 조용히 skip되므로 테스트 결과의 `skipped`를 확인한다. CI는 `THORP_REQUIRE_DOCKER=true`로 돌려 `support/RequiredDockerTest`가 그 상태를 실패로 만든다. **예외는 Spring 컨텍스트 없이 Flyway API로 마이그레이션만 적용한 DB를 보는 테스트다**: 스키마 제약만 보는 `EtfMarketDataSchemaIT`(공용 컨테이너의 커넥션 여유가 없다)와, 특정 마이그레이션 직전 버전까지 적용한 DB에 나머지를 올려 보는 `LearningStagesMigrationUpgradeIT`(시나리오마다 Flyway `target`을 정한다). 이들은 전용 컨테이너를 띄우고 커넥션을 하나만 열며, 같은 skip 규칙(`@Testcontainers(disabledWithoutDocker = true)`)을 따른다. 버전 번호가 필요하면 이 경우에도 `support/MigrationFiles`로 찾는다

- **내 기록(`myrecord`)은 계산 결과를 복제하지 않고 `backtest_request`·`param_search`·`regime_search_log`를 가리킨다. 삭제는 `my_record.deleted_at`만 바꾼다.** 상태(완료·실패·진행 중)는 컬럼이 아니라 읽을 때 원본을 조인해 파생하고(`MyRecordViewQuery`), 목록 응답에 수익률·하락 폭·비용을 싣지 않는다(원칙 III). 항목 생성은 004·007 접수 이벤트(`BacktestRequestAccepted`·`ParamSearchAccepted`)를 `MyRecordRegistrar`가 듣는 방식이라 접수 서비스가 006을 부르지 않는다 — 방향은 `myrecord → paramsearch` 하나다(ArchUnit 규칙 11). 탐색 묶음 저장 상한은 `SaveSlotGuard` 포트로 접수 직전에 판정하고 409 `RECORD_LIMIT_REACHED`다. 시간 기반 자동 삭제 배치를 만들지 않는다(spec 006 PD-13)
- **소셜 로그인(spec 014)은 `account/infrastructure/oauth`에 있다.** 제공자 차이는 `SocialProviderAdapter` 구현 하나에서 끝나고 판정은 `SocialLoginService`가 한다. 자격 증명은 `thorp.auth.social.*`(환경 변수)이며 client id·secret이 둘 다 있는 제공자만 켜진다 — `spring.security.oauth2.client.*`를 쓰지 않는다(값이 비면 기동 실패). 콜백 성공 처리기는 판정마다 세션 인증을 직접 정한다: 로그인이면 이메일 로그인과 같은 `startSession`, 그 밖은 `dropAuthentication`. 필터가 먼저 저장한 OAuth2 인증을 남기면 `.authenticated()` 경로가 가입 대기 사용자를 통과시킨다. 가입·연결 대기 상태는 세션 속성(`PendingSocialLogin`)이고 제공자 token은 저장하지 않는다

## 도메인 용어

Job(백테스트 작업 단위), Strategy(전략), BacktestResult(수익률·MDD·Sharpe·거래 횟수)

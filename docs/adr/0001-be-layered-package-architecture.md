# ADR-0001: 백엔드 패키지 구조 — 도메인별 계층형 아키텍처

- 상태: 승인 (2026-09-03)
- 대상: `backend/` (`com.ssafy.thorp`)
- 관련 이슈: #87

## 배경

백엔드는 `com.ssafy.thorp` 아래 `backtest/`(컨트롤러 1개, 요청 DTO 1개)와 빈 `common/`만 있는
스캐폴딩 상태다. 기존 `backend/CLAUDE.md`는 "도메인 패키지 안에 controller·service·repository·dto를
함께 둔다"고만 정해 두었고, 계층 간 의존 방향은 규정하지 않았다.

앞으로 도메인이 backtest → strategy, user, notification 순으로 늘어나고, 알고리즘도 20개+로
확장된다(v0.1.2). 기능 개발이 시작되기 전에 패키지 경계와 의존 방향을 확정해 두지 않으면
도메인마다 구조가 달라지고, 컨트롤러 DTO가 서비스 시그니처에 새거나 도메인 객체가 JPA에
묶이는 문제가 생긴다.

아키텍처 원칙(루트 `CLAUDE.md`)상 백엔드는 계산하지 않고 Job을 브로커에 넣기만 하며, 브로커는
아직 확정하지 않았다(현재 RabbitMQ 예정, Kafka는 설치만). 저장소도 Parquet 등으로 바뀔 여지를
남겨야 한다. 외부 기술에 대한 의존을 한곳에 격리할 필요가 있다.

## 결정

도메인 패키지 안에 `presentation / application / domain / infrastructure` 4계층을 둔다.
공통 요소는 `common`에 모으고, 의존 방향은 ArchUnit 테스트로 강제한다.

| 항목 | 결정 | 이유 |
|---|---|---|
| 계층 분리 단위 | 도메인 패키지 안에 4계층 (`backtest/presentation`, `backtest/application`, …) | 도메인이 늘어날 때 도메인 단위로 응집. 계층별 상위 폴더로 나누면 도메인 하나를 읽을 때 4곳을 오가야 한다 |
| 리포지토리 위치 | 인터페이스는 `application/repository`, 구현체는 `infrastructure/persistence` | 도메인·응용 계층이 JPA에 의존하지 않게 함. Parquet/다른 저장소로 교체 여지 |
| 도메인 모델 vs JPA Entity | 분리. `domain/model`은 순수 Java, `infrastructure/persistence`에 `*JpaEntity` + `*Mapper` | 도메인 객체에 `@Entity`가 붙으면 계층 분리가 형식에 그친다 |
| 외부 발행(메시징) | `application/port` 인터페이스(`BacktestJobPublisher`) → `infrastructure/messaging` 구현 | 브로커 결정 유보 원칙과 맞물림. 브로커를 바꿔도 application 이상은 수정 없음 |
| DTO 경계 | `presentation/request·response`는 HTTP 전용. `application/command·result`는 유스케이스 입출력. 컨트롤러가 request→command, result→response 변환 | 컨트롤러 DTO가 서비스 시그니처에 새는 것을 차단 |
| 공통 요소 | `common/web`(응답 포맷·전역 예외 처리), `common/exception`, `common/persistence`(`BaseEntity`), `common/config` | 최상위 `config/`는 두지 않고 `common` 아래로 흡수 |
| 루트 패키지 | `com.ssafy.thorp` | 조직·서비스 순서의 일반 관례. `api` 세그먼트는 의미가 없어 제거 |
| 의존 강제 | ArchUnit (`archunit-junit5`), `layeredArchitecture()` 기반 | 규칙이 문서에만 있으면 무너진다. 도메인이 늘어도 규칙에 자동 포함되게 한다 |

### 의존 방향 (허용되는 화살표만)

```
presentation ──▶ application ──▶ domain
                    ▲                ▲
infrastructure ─────┴────────────────┘

common: 모든 계층에서 참조 가능. common은 어떤 도메인 패키지도 참조하지 않음.
domain: Spring·JPA·Jackson 등 프레임워크 import 금지 (java.*, 자신의 패키지, common.* 만)
```

### 각 계층의 역할

| 계층 | 하위 패키지 | 역할 |
|---|---|---|
| `presentation` | `controller`, `request`, `response` | HTTP 입출력. `@RestController`는 여기에만 둔다. request→command, result→response 변환 |
| `application` | `service`, `command`, `result`, `repository`, `port` | 유스케이스 조합과 트랜잭션 경계. 저장소·외부 시스템은 인터페이스로만 안다 |
| `domain` | `model`, `vo`, `exception` | 순수 Java 도메인 모델과 불변식. 프레임워크에 의존하지 않는다 |
| `infrastructure` | `persistence`, `messaging` | `application`의 인터페이스 구현. `@Entity`, Spring Data, 브로커 클라이언트는 여기에만 둔다 |

### 목표 패키지 트리 (backtest 기준)

```
com.ssafy.thorp
├── ThorpApplication.java
├── backtest
│   ├── presentation
│   │   ├── controller/BacktestController.java
│   │   ├── request/BacktestJobRequest.java
│   │   └── response/BacktestJobResponse.java
│   ├── application
│   │   ├── service/BacktestService.java
│   │   ├── command/CreateBacktestCommand.java
│   │   ├── result/BacktestJobResult.java
│   │   ├── repository/BacktestJobRepository.java       (인터페이스)
│   │   └── port/BacktestJobPublisher.java              (인터페이스)
│   ├── domain
│   │   ├── model/BacktestJob.java
│   │   ├── model/BacktestStatus.java
│   │   ├── vo/BacktestPeriod.java
│   │   └── exception/BacktestNotFoundException.java
│   └── infrastructure
│       ├── persistence
│       │   ├── BacktestJobJpaEntity.java
│       │   ├── BacktestJobJpaRepository.java
│       │   ├── BacktestJobRepositoryImpl.java
│       │   └── BacktestJobMapper.java
│       └── messaging/                                  (브로커 결정 후 구현)
└── common
    ├── web
    │   ├── response/ApiResponse.java
    │   ├── response/ErrorResponse.java
    │   └── advice/GlobalExceptionHandler.java
    ├── exception
    │   ├── BusinessException.java
    │   └── ErrorCode.java
    ├── config/JpaAuditingConfig.java
    └── persistence/BaseEntity.java
```

### ArchUnit으로 강제하는 규칙

1. `..domain..`은 `..application..`, `..infrastructure..`, `..presentation..`, `org.springframework..`,
   `jakarta.persistence..`, `com.fasterxml..`에 의존하지 않는다.
2. `..application..`은 `..infrastructure..`, `..presentation..`에 의존하지 않는다.
3. `..presentation..`은 `..infrastructure..`에 의존하지 않는다.
4. `..common..`은 어떤 도메인 패키지(`com.ssafy.thorp.(?!common).*`)에도 의존하지 않는다.
5. `@Entity` 클래스는 `..infrastructure.persistence..`에만 위치한다.
6. `@RestController` 클래스는 `..presentation.controller..`에만 위치한다.

## 결과

**장점**

- 새 도메인을 추가할 때 구조를 고민하지 않는다. 4계층 폴더를 만들면 ArchUnit 규칙에 자동 포함된다.
- 브로커(Kafka/RabbitMQ)와 저장소(JPA/Parquet) 결정을 미루거나 바꿔도 `infrastructure`만 수정한다.
- 도메인 모델을 프레임워크 없이 단위 테스트할 수 있다.

**단점 / 감수하는 비용**

- 도메인 모델과 JPA Entity를 분리하므로 Mapper 보일러플레이트가 생긴다. Mapper는 정적 메서드
  `toEntity`, `toDomain` 2개로 제한한다. MapStruct 도입은 도메인이 3개를 넘을 때 재검토한다.
- 유스케이스 하나에 request/command/result/response 4종의 DTO가 생긴다. MVP 규모에서는 과할 수
  있으나, DTO 경계가 무너진 뒤에 되돌리는 비용이 더 크다고 판단했다.
- 브로커 구현체가 비어 있는 동안 컨텍스트 기동을 위해 임시 로깅 구현체(`LoggingBacktestJobPublisher`)를
  둔다. 브로커 결정 티켓의 완료 조건에 "이 클래스 삭제"를 포함한다.

**이 결정에서 다루지 않는 것**

- 백테스트 Job 생성/조회의 실제 비즈니스 로직, 트랜잭션 경계와 publish 실패 처리.
- 메시지 브로커 선택. 루트 `CLAUDE.md`(RabbitMQ)와 `application.yml`(Kafka)의 불일치는 브로커 결정
  티켓에서 의존성·설정을 함께 정리한다.
- `strategy`, `user`, `notification` 도메인 폴더 생성. 해당 기능 티켓에서 이 ADR의 구조를 따라 만든다.
- Flyway 도입, 인증/보안 설정.

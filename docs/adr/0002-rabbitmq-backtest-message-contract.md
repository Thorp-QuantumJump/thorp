# ADR-0002: 백테스트 브로커 RabbitMQ 확정과 메시지 계약의 미결 항목 결정

- 상태: 승인 (2026-09-08) — D1~D4 팀 확인 완료
- 대상: `backend/`, `worker/`, `docs/specs/backtest-messaging/`
- 관련 이슈: #102
- 선행: [ADR-0001](0001-be-layered-package-architecture.md) "브로커 결정 티켓에서 의존성·설정을 함께 정리한다"

## 배경

설계 문서 `NautilusTrader 설계(input, output)`의 "RabbitMQ 연동 및 실시간 그래프 설계" 절이 브로커를 RabbitMQ로,
작업 메시지를 `EngineInput` 전체로, 이벤트를 STARTED / PROGRESS / FINISHED로 정했다. 큐 이름, 저장·ACK 책임, SSE 규격까지
정해져 있어 계약 정의는 완료로 본다.

그러나 설계 문서와 다른 문서·코드 사이에 네 가지가 갈라져 있어 DTO를 작성하기 전에 정해야 한다. 또한 코드에는
Kafka 의존성·설정이 남아 있고 임시 발행기(`LoggingBacktestJobPublisher`)만 있다.

## 결정

### D1. Job 대기 상태 이름은 `QUEUED`

- 현재: 코드 `BacktestStatus.QUEUED`, 설계 `PENDING`, 루트 CLAUDE.md `QUEUED`.
- 결정(2026-09-08 정정): 코드에 이미 쓰이는 `QUEUED`로 통일한다. Job 상태는 `QUEUED → RUNNING → COMPLETED | FAILED`. `EngineOutput.run.status`는 `COMPLETED | FAILED`만.
  설계 문서의 `PENDING`과 계약 README의 `Job(PENDING)` 표기를 `QUEUED`로 정정한다.
- 이유: 설계 §1 "코드에서 QUEUED를 사용 중이면 구현 전에 이름을 통일하고 두 값을 혼용하지 않는다". 두 값 중 하나를 고르는 문제이며,
  이미 코드·루트/backend CLAUDE.md·프론트 타입이 모두 `QUEUED`다. 문서 두 곳만 고치면 끝나므로 리네임 비용과 FE 파급이 없는 쪽을 택한다.
- 적용: 계약 README와 이 문서를 `QUEUED`로 정정했고 코드·프론트는 그대로다. HTTP 계약 문서에도 같은 상태 흐름을 적었다.

### D2. `backtestId`는 문자열 `BT-{yyyyMMdd}-{6자리 이상 순번}`

- 현재: 코드 Long IDENTITY PK를 그대로 노출, 설계 `"BT-20260831-000123"`.
- 결정: 외부 식별자(`backtestId`)는 문자열. DB PK는 Long을 유지하고 `backtest_id` UNIQUE 컬럼을 추가한다(발행 구현 시).
- 이유: 메시지 `correlation_id`, SSE 경로, 프론트 노출값이 모두 이 값이다. PK를 노출하지 않고 형식을 바꿔도 저장 구조에 영향이 없다.
- 적용(요청 API·발행 구현): `domain/vo/BacktestId`가 `BT-{yyyyMMdd(KST)}-{PK %06d}`를 만들고 `^BT-\d{8}-\d{6,}$`를 검증한다.
  순번은 PK라 유일성이 공짜이며 999,999를 넘으면 자릿수가 늘어난다. 그래서 "6자리"가 아니라 **6자리 이상**이다. 수신 측은 길이를 가정하지 않는다.
  PK 전략을 IDENTITY에서 SEQUENCE로 바꿔 INSERT 전에 `backtest_id`(UNIQUE, NOT NULL)를 채운다. HTTP 계약은
  `docs/specs/backtest-api/`에 정리했다.

### D3. `initialCash`는 사용자 입력 (기본값 10,000,000 KRW)

- 현재: 기획서·BT_001 "초기 자본은 공통 엔진 규칙으로 동일 적용"(고정), 설계 `BacktestRequest.portfolio.initialCash` 필수(입력).
- 결정: 설계 INPUT 스펙을 따라 사용자 입력. 프론트가 기본값을 채워 보낸다.
- 이유: 설계 문서가 더 최신·상세하며 `EngineInput`이 이미 이 필드를 요구한다. 전략 비교(BT_006) 공정성은 비교 화면이 같은
  값을 강제해 보장한다. 고정으로 되돌려야 하면 백엔드가 요청값을 무시하고 상수를 채우면 되므로 계약은 바뀌지 않는다.

### D4. 대상 시장은 KRX, 식별자는 `{종목코드}.XKRX`, 통화 `KRW`

- 현재: 요구사항 "국내 주식·ETF·지수", 설계 예시 `AAPL.XNAS`, `SPY.ARCA`, `USD`.
- 결정: `instrumentId = "005930.XKRX"` 형식, `currency = "KRW"`, `marketData.datasetId = "KRX_DAILY_V1"`, 벤치마크 예시 `069500.XKRX`(KODEX 200).
- 이유: Nautilus `InstrumentId`의 `symbol.venue` 규약과 호환되고 데이터 파트(pykrx)가 KRX 일봉을 수집한다. 해외 시장 추가는 venue 값만 달라진다.

### 부수 결정

| 항목 | 결정 | 이유 |
|---|---|---|
| 큐·exchange 선언 주체 | 백엔드 애플리케이션(Spring `Declarables`) | 인프라 선행 작업 없이 로컬·CI에서 동작. 인프라가 미리 만들어도 동일 속성이면 무해 |
| Kafka 의존성·설정 | 유지, `application.yml`에 미사용 주석만 추가 | 루트 CLAUDE.md "설치만, 전환·병행은 실측 후 결정". 브로커 없이도 지연 연결이라 기동에 영향 없음 |
| 메시지 DTO 위치 | `backtest/application/port/message` (순수 record, 프레임워크 import 없음) | 발행(application)과 소비(infrastructure)가 모두 참조. 직렬화 규칙은 infrastructure의 메시지 전용 ObjectMapper가 담당 |
| 금액·비율 JSON 표현 | 문자열 decimal | 설계 규약. 부동소수 오차 회피. 수신 측은 숫자도 수용 |
| 수수료 정책 상수 | `commission.broker=KIWOOM`, `version=2026-09` | 모의투자(SIM_001)가 키움 API 연동 예정. 실제 요율표는 엔진 쪽 정책 파일로 관리 |
| `EngineOutput.run.error` | 설계에 없던 필드를 보충. 실패 시 `{code, message}`, 성공 시 null | 설계의 ErrorResponse 코드 집합을 엔진 결과에서도 그대로 쓰기 위함 |
| internal API 명세서 `/engine/backtests/*` | 폐기. 이 메시지 계약으로 대체 | HTTP 동기 호출 안은 RabbitMQ 설계 이전 산출물 |
| 픽스처 위치 | `docs/specs/backtest-messaging/examples/` 한 벌. 백엔드·워커 테스트가 상대 경로로 같은 파일을 읽음 | 테스트 리소스로 복사하면 어긋난다 |
| null 표현 | **키 자체를 생략**한다(`null` 값도 쓰지 않음). 수신 측은 "키 없음 == null"로 읽고 명시 `null`도 같은 뜻으로 수용 | 원본 설계의 "명시 null" 표기를 확장한 결정. Jackson record는 명시 null과 키 없음을 구분하지 못해 한 규칙이어야 픽스처와 정확 동치 round-trip이 가능. 백엔드 `NON_NULL`, 워커 `exclude_none`, 픽스처 생성기 세 곳이 같은 규칙 |
| `strategy.parameters` 숫자 | 정수·소수를 JSON 숫자로 그대로 통과. 문자열 변환 금지 | 자유 형식 객체는 전략 팀 소유. 금액·비율 문자열 규칙은 선언된 decimal 필드에만 적용 |
| 필수 필드 검증 | DTO 생성자가 아니라 수신 Consumer가 역직렬화 직후 명시 검증(실행 Consumer·결과 Consumer) | NPE에 맡기면 쓰지 않는 필드의 누락이 묻히고 프로그램 버그를 계약 오류로 오인한다 |
| 엔진 진행 콜백 단위 | 거래일 1개. 20거래일 묶음 PROGRESS 발행은 실행 Consumer 책임 | 묶음 정책이 Mock·Nautilus 엔진 양쪽에 중복되지 않게 한 곳에 둔다 |

## 결과

- D1~D4는 팀 확인을 거쳐 승인되었다. 이후 변경은 별도 ADR 또는 이 문서의 개정으로 다룬다.
  D1~D4가 바뀌면 메시지 DTO 구조는 유지되지만 enum·픽스처 외에 요청 API·DB 컬럼·엔진 정책(수수료·데이터셋)까지 영향받는다. 변경 시 별도 이슈로 영향 범위를 정리한다.
- 발행 구현 시 `backtest_id` 컬럼 추가와 `LoggingBacktestJobPublisher` 삭제를 수행한다(D1 정정으로 상태 리네임은 없다).
- 워커 실행 Consumer와 엔진의 경계는 `worker/src/worker/contracts/engine.py`의 `BacktestEngine` Protocol이다. 엔진 담당은 이 인터페이스만 구현한다.
  (기존 `worker/engine.py` 모듈과 이름이 충돌하므로 `engine/` 패키지는 만들지 않는다. 기존 모듈은 워커 런타임 교체 때 정리한다.)

## 이 결정에서 다루지 않는 것

- outbox 테이블 구조와 재발행 스케줄(발행 구현), 결과 테이블 구조(결과 Consumer 구현), SSE 다중 인스턴스 전파(설계 §6, 후순위).
- 파라미터 탐색(Optimization) 메시지. 설계 문서도 별도 범위로 두었다.
- Kafka 제거 시점. 실측 후 별도 chore.

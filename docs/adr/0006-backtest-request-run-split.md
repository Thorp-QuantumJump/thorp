# ADR-0006: 백테스트 실행 관리 — 요청/실행 분리, 제어 채널, quorum 큐, 큐 분리, 측정

- 상태: 승인 (2026-09-15) — spec 004 T001~T015 구현 완료 시점에 research 결정을 ADR로 옮김
- 대상: `backend/src/main/java/com/ssafy/thorp/backtest/`, `worker/src/worker/{consumer,rabbitmq,control}.py`,
  `docs/specs/backtest-messaging/`, `infra/docker-compose.prod.yml`
- 선행: [ADR-0002](0002-rabbitmq-backtest-message-contract.md) 브로커·메시지 계약,
  [ADR-0005](0005-param-search-dispatch-window.md) 자동 탐색 발행 창
- 근거 문서: [spec 004 research](../../specs/004-backtest-execution/research.md) R-02·R-04·R-06·R-10·R-18·R-19·R-20,
  [data-model](../../specs/004-backtest-execution/data-model.md)

## 배경

ADR-0002 시점의 백테스트는 "요청 하나 = Job 하나 = 실행 하나"였고, 워커는 단일 종목 모멘텀 DEMO였다. spec 004(백테스트 실행)는
그 위에 사용자 기능(취소·재시도·진행 조회)과 운영 요구(같은 조건의 중복 계산 방지, 실행 한도, 측정)를 얹어야 했다. 구현하면서 되돌리기
어려운 결정이 일곱 번 있었고, 그 근거는 research.md에 결정 번호(R-xx)로 남아 있다. 이 문서는 그중 **구조를 정한 것**만 골라
"무엇을, 왜, 무엇을 버렸나"를 한 곳에 모은다. 세부 규칙·수치·예외 처리는 research.md와 `backend/CLAUDE.md`가 원본이다.

## 결정

### D1. 요청(`backtest_request`)과 실행(`backtest_run`)을 다대일로 나눈다 (R-02·R-03)

- 사용자가 보는 `backtestId`는 **요청**의 것이고, 워커가 계산하는 단위는 **실행**이다. 같은 조건(전략·파라미터·기간·비용 조건·규칙·자료
  버전·실효 워밍업을 canonical JSON으로 만든 SHA-256 **동일 버전 키**)의 요청은 진행 중 실행에 **합류(JOINED)**하거나 완료 결과를
  **재사용(REUSED)**한다. 취소는 요청 단위이고, 마지막 활성 요청의 취소만 실행을 멈춘다.
- 버린 대안: 한 테이블에 self-reference로 합류를 표현 — 취소·상태 전파 규칙이 행마다 갈라져 검증이 어렵다. 결과를 요청마다 복사 —
  저장량이 요청 수에 비례해 늘고 "같은 계산"이라는 사실이 사라진다.
- 결과: 계산 절약이 `backtest_reuse_event`로 세어진다(FR-008). 대신 접수는 자문 잠금(`pg_advisory_xact_lock`)으로 같은 키끼리
  직렬화해야 했다 — 부분 유니크 인덱스에 걸리면 PostgreSQL은 그 트랜잭션을 abort 하므로 "잡아서 재조회"가 불가능하다.

### D2. 취소는 fanout 제어 채널 `backtest.control`로 워커에 직접 알린다 (R-04)

- 백엔드가 상태만 CANCELLED로 바꾸고 워커는 끝까지 계산하는 방식은 대기열 자원을 낭비하고 부분 결과 시점을 정할 수 없다(원칙 IX).
  워커마다 exclusive·auto-delete 큐를 fanout에 바인딩하고, 엔진은 거래일마다 자기 runId의 취소 표시를 확인해 그날 평가를 마친 뒤 멈춘다.
  결과는 `CANCELLED·partial=true`와 완료 범위(`lastTradingDate`)다.
- CANCEL은 **취소 트랜잭션이 커밋된 뒤** 보낸다(작업 발행과 같은 방식). 트랜잭션 안에서 보내면 발행됐는데 커밋이 실패해 아무도 취소하지
  않은 실행이 멈춘다. 발행 실패는 삼키고 유예 감시(D6)가 `cancel_requested_at`을 보고 다시 보낸다.
- 워커의 취소 표시는 **활성 실행에 한정**한다 — fanout이라 모든 워커가 모든 CANCEL을 받으므로 남의 실행의 표시를 쌓으면 집합이 끝없이
  자라고, 같은 runId가 재전달됐을 때 옛 취소가 새 실행을 멈춘다.

### D3. 작업 큐는 quorum + `x-delivery-limit=1`, 발행은 커밋 뒤 + 재발행 (R-06·R-18)

- 시스템 장애(워커 크래시)의 자동 재시도는 **한 번**이다(Clarify Q5). 브로커가 unacked 메시지를 재전달하되 두 번째도 끊기면 DLQ로 보내는
  것을 quorum 큐의 `x-delivery-limit`이 해 준다. classic → quorum은 큐 인자 변경이라 운영 전환 절차(`infra/scripts/migrate-jobs-queue.sh`)가
  한 번 필요했다.
- 작업 발행은 접수 트랜잭션 **커밋 뒤**(`@TransactionalEventListener`)에 한다. 트랜잭션 안에서 발행하면 워커의 STARTED가 커밋을 앞질러
  도착해 결과 Consumer가 모르는 실행이라며 버린다. 반대 방향(발행 뒤 커밋 실패)의 고아 메시지는 `published_at` NULL 재발행
  (`JobRepublisher`)이 닫는다 — run 행이 발행 대상과 1:1이라 outbox 테이블 없이 같은 보장을 얻는다.
- `publish_count`는 confirm 수가 아니라 **발행 시도 수**이고 시도 직전에 올린다. 실제 메시지 수의 상한이어야 DLQ 종결 판정
  (`dlq_count >= publish_count`)이 살아 있는 실행을 끊지 않는다. DLQ 집계는 `(run_id, attempt)` PK로 멱등하다 — AMQP `redelivered`
  플래그는 "이미 세었다"가 아니라 "이미 보낸 적 있다"라서 커밋 전에 죽어 다시 온 첫 전달까지 버리는 반대 방향의 오류였다.
- 버린 대안: Kafka 전환(설치만 돼 있고 파티션 수가 동시성 상한이 된다, ADR-0002), 별도 outbox 테이블(테이블·폴러가 하나 더 늘 뿐 보장은 같다).

### D4. 한 실행 행을 여러 경로가 갱신하면 컬럼 소유를 가른다 (R-18 부수 결정)

- 실행 행은 이벤트 처리(상태·진행·시각), 발행 경로(`published_at`·`publish_count`), 취소 경로(`cancel_requested_at`·`cancel_reason`),
  정체 감지(`last_event_at`)가 서로 다른 스레드에서 동시에 쓴다. 도메인 객체를 읽어 통째로 저장하면 늦게 저장한 쪽이 상대의 갱신을 되돌린다.
  저장소에 경로별 UPDATE 메서드를 두고, 카운터는 DB에서 올린다.
- **자바 필드를 가르는 것만으로는 부족했다.** Hibernate 기본 UPDATE는 바꾸지 않은 컬럼까지 함께 쓰므로 `@DynamicUpdate`를 걸었다.
  `*ForUpdate` 조회는 잠근 뒤 `refresh`한다 — 잠금만으로는 1차 캐시가 이겨 그 사이 커밋된 값을 놓친다.

### D5. 실행 한도는 장치와 값을 분리하고, 접수만 막는다 (R-19)

- FR-006 "상한을 정하기 전에 무제한 실행을 허용하지 않는다". 값(PD-06)은 실측 뒤 팀이 정하지만 장치가 없으면 값이 정해져도 적용할 곳이
  없다. 설정 `thorp.backtest.limits.*` 다섯 값은 prod에서 기본값 없이 필수라 설정 누락으로 우회할 수 없다. 확정 전에는 429 응답에 `provisional`이 실린다.
- **동시 실행 상한은 백엔드에 두지 않는다.** 접수 시점 검사로는 보장되지 않고(실행 중이 0이면 모두 통과한 뒤 워커가 동시에 소비), 실제
  상한은 워커 replica × prefetch(1)라는 배치 값이다. 실행 시작 시 슬롯 예약은 워커↔백엔드 동기화(워커 DB 접근 금지)와 충돌해 버렸다.
- 접수 거절 429는 호출자의 트랜잭션을 되돌리지 않는다(`noRollbackFor`). 007이 후보 정산과 다음 접수를 한 트랜잭션에서 하므로, 기본 롤백
  규칙이면 이미 끝난 후보의 완료 처리까지 지운다. 안전한 근거는 검사가 어떤 쓰기보다 먼저 일어난다는 것이다.

### D6. 멈춘 실행은 백엔드가 판단해 거둔다 (R-19 스위퍼)

- 워커는 `prefetch=1`이라 Job 하나를 잡고 멈추면 그 워커가 영원히 묶이는데, 브로커는 "일하는 중"과 "멈춘 것"을 구분하지 못한다.
  `RunLivenessSweeper`가 실행 시간 상한·정체(`last_event_at`이 오래됨)에 CANCEL을 보내고, 어떤 CANCEL이든 유예 안에 FINISHED가 없으면
  `FAILED(partial=false)`로 강제 종결한다(결과 행 없음 — 부분 결과가 없는데 있다고 하지 않는다).
- 정체 판정은 `updated_at`이 아니라 `last_event_at`이다 — 발행 기록이 갱신될 때도 `updated_at`이 올라가 멈춘 실행이 살아 있는 것처럼
  보이고, 그 오류의 방향은 "안 잡는 쪽"이다. `cancel_reason`을 따로 남겨 강제 종결 사유가 그 사이 바뀐 설정에 흔들리지 않게 한다.
- 스위퍼는 **건마다 트랜잭션**을 연다 — CANCEL 발행이 `AFTER_COMMIT`이라 트랜잭션이 없으면 이벤트가 조용히 버려진다.

### D7. 단건과 벌크는 큐를 나누고, 워커는 단건 전용 + 벌크 우선 공용으로 배치한다 (R-20)

- D5의 한도를 한 큐에 걸자 어느 정책도 성립하지 않았다. 탐색(007) 후보를 전체 한도에서 거절하면 007이 완료 이벤트로만 다음 후보를
  내보내는 탓에 마지막 진행 후보 뒤의 거절이 **묶음을 영구 정지**시키고, 거절하지 않으면 묶음 수 × 발행 창이 상한을 넘는 동안 **단건만 계속
  429**를 받았다("배치가 붐비면 대화형 접수를 끈다"). 값이 아니라 대화형과 배치가 한 큐·한 정책을 공유하는 구조의 문제였다.
- `backtest.jobs`(단건, **이름 유지**) / `backtest.jobs.bulk`(탐색·경제환경 파생) 둘로 나누고 한도는 단건 큐만 본다. 워커 `single` N은
  단건 큐만 push(`basic_consume`, prefetch 1), `flex` M은 벌크 → 단건 순서로 pull(`basic_get`) 한 건씩, 둘 다 비면 1초 대기.
- pull인 이유: quorum 큐는 global QoS를 지원하지 않아 두 큐를 `basic_consume`하며 "합쳐서 1건"을 표현할 수 없고, 컨슈머별 prefetch 1로
  두 큐를 구독하면 처리하지 않은 두 번째 메시지가 unacked로 잡혀 크래시 때 `x-delivery-limit=1`의 유일한 재시도를 실행 없이 태운다.
  큐 이름을 바꾸지 않은 이유: 개명은 quorum 큐 교체 절차를 다시 요구하는데 bulk만 추가하면 전환 절차가 없다.
- 버린 대안: 완전 분리(벌크가 없으면 M이 놀고 4 vCPU에 워커 2개라 절반을 못 놀린다), 단건 우선 공용(벌크가 굶어 `time-limit`에 걸려 PARTIAL),
  두 큐 전역 FIFO(RabbitMQ에 peek이 없어 꺼내 비교하고 requeue해야 하는데 순서와 재전달 횟수가 꼬인다), 우선순위 큐(quorum 미지원).
- 수용한 한계: flex 워커의 단건 응답은 최대 폴링 간격(1초)만큼 늦다. 실측(T012)에서 문제가 되면 single은 consume·벌크는 get의 하이브리드가 다음 단계다.

### D8. 측정은 종결 지점이 직접 기록하고, 값은 뷰와 같은 SQL로 집계한다 (R-10)

- PD-06 한도 값과 D7의 N/M 비율은 실측으로 정한다. 종결(FINISHED 셋·DLQ·강제 종결·실행 전 취소) 네 지점이 같은 트랜잭션에서
  `backtest_run_metric` 한 행을 남기고(`run_id` UNIQUE + `ON CONFLICT DO NOTHING`), 한도 도달(429·실행 시간 상한 정지)은 실행 행이 없거나
  미종결이라 `backtest_limit_hit` 별표다. `BacktestFinished` 이벤트 구독은 그 이벤트가 007을 위한 경계 신호라 FINISHED의 `resource`가 없어 버렸다.
- 어느 큐·어느 워커가 결과를 냈는지는 FINISHED `resource.queue`·`role`이 먼저다 — 중복 실행(D3)에서 마지막 STARTED의 워커와 다를 수 있다.
  `peakRssMb`는 프로세스 누적 최고값(`ru_maxrss`)이 아니라 이 실행 동안 표본으로 잰 최댓값이다.
- 집계 SQL은 저장소 상수 하나이고 뷰 `v_run_metric_summary`는 그 범위 없는 판이다 — 뷰에는 인자를 줄 수 없고 두 SQL이 갈라지면 "API = 뷰"가
  깨진다. 운영자 경로는 로그인(spec 014) 전까지 `X-Ops-Token`으로 잠그고 토큰이 없으면 404다.

## 결과

- (+) 같은 조건의 요청이 계산을 공유하고, 취소가 실제로 계산을 멈추고, 워커 크래시가 한 번은 자동 복구되며, 멈춘 실행이 사람 없이 정리된다.
  한도와 워커 배치를 정할 자료가 쌓인다.
- (−) 실행 행 하나에 갱신 경로가 넷이라 규칙(컬럼 소유·`@DynamicUpdate`·`refresh`)을 알아야 손댈 수 있다 — `backend/CLAUDE.md`에 규칙으로 적었다.
- (−) 워커가 두 역할, 큐가 둘, 운영 환경변수가 아홉 개 늘었다(`.env.prod.example`).
- (제약) 새 종결 경로를 만들면 측정 기록을 부르고, 실행 행에 새 갱신 경로를 만들면 전용 UPDATE로 쓴다. 자료 버전은 아직 백엔드 상수
  (`FixtureMarketMetadataAdapter`)라 진짜 KRX 자료로는 접수가 그 버전을 가리키지 못한다 — 001 T019와 함께 풀 후속이다.

## 검증

`specs/004-backtest-execution/quickstart.md` S1~S13. 실행 기록은
[`docs/guides/backtest-execution-verification-2026-09.md`](../guides/backtest-execution-verification-2026-09.md).

# 작업 큐 quorum 전환과 워커 실행 환경 (spec 004 T002)

백테스트 메시지 계약 v2(spec 004 T001)부터 백엔드는 작업 큐 `backtest.jobs`를 **quorum 큐 +
`x-delivery-limit=1`** 로 선언한다(research R-06). 워커가 실행 중에 죽어 메시지가 되돌아오면 브로커가
한 번만 다시 배달하고, 두 번째도 끊기면 DLQ로 보낸다 — 이것이 "시스템 장애 1회 자동 재시도"의 구현이다.

**문제는 기존 환경이다.** classic → quorum은 큐 인자 변경이라 같은 이름으로 재선언하면
`PRECONDITION_FAILED - inequivalent arg 'x-queue-type'`으로 선언이 실패하고, 큐는 classic 그대로 남아
delivery-limit이 적용되지 않는다. 새 볼륨에서 시작하는 환경은 해당 없다. **RabbitMQ 볼륨을 유지하는 모든
환경(개발 PC의 `rabbitmq-data`, 운영 브로커)은 v2 백엔드를 띄우기 전에 큐를 한 번 지워야 한다.**

## 전제조건 — 이것을 만족할 때만 지운다

| 조건 | 이유 |
|---|---|
| 접수가 멈춰 있다(백엔드 정지) | 지우는 사이 새 작업이 들어오면 잃는다 |
| `messages_ready = 0` | 대기 중인 작업이 있으면 잃는다. **미ACK만 보는 것으로는 부족하다**(plan 리뷰) |
| `messages_unacknowledged = 0` | 워커가 처리 중인 작업이 있으면 그 결과가 붙을 메시지가 사라진다 |

```bash
docker compose -f infra/docker-compose.prod.yml --env-file infra/.env.prod exec rabbitmq \
  rabbitmqctl list_queues name type messages_ready messages_unacknowledged
```

두 숫자가 0이 아니면 **워커만 올려 비운다.** 백엔드가 내려가 있어도 워커의 결과 이벤트는 `backtest.events`
큐에 쌓이고 백엔드가 올라오면 처리하므로 결과를 잃지 않는다.

## 운영 전환 절차

`infra/scripts/migrate-jobs-queue.sh`가 아래를 순서대로 하고, 실패하면 되돌리는 방법을 출력한다.

1. 큐 상태 확인. 큐가 없거나 이미 quorum이면 아무것도 하지 않고 끝난다.
2. 접수 중단: `backend`·`worker` 컨테이너 정지.
3. `ready`·`unacked` 모두 0 확인. 아니면 `backend`·`worker`를 다시 올리고 중단한다(전환 포기).
4. `rabbitmqctl delete_queue backtest.jobs`.
5. `backend` 기동 → 최대 60초 안에 `type=quorum` 확인 → `worker` 기동.

```bash
cd "$DEPLOY_DIR/infra"
./scripts/migrate-jobs-queue.sh
```

**배포 순서와의 관계.** 이 전환은 계약 v2 백엔드(T001 이후 dev)를 **처음 배포하는 그 단계**에서 한 번 실행한다.
`deploy.sh`는 시작 시 `backtest.jobs`가 classic이면 배포를 멈추고 이 스크립트를 먼저 실행하라고 알린다.
백엔드와 워커는 같은 배포에서 함께 올린다 — v1 워커는 v2 작업 메시지를 계약 오류로 거절하고(DLQ),
v2 백엔드는 v1 이벤트를 거절한다(계약 README §3).

**되돌리기.** 5단계에서 quorum 재선언이 확인되지 않으면 백엔드 로그를 보고, 이전 버전 백엔드 이미지로
`up -d backend` 하면 classic 큐가 다시 선언된다. 그 사이 워커는 올리지 않는다. 지운 큐의 메시지는 3단계에서
0을 확인했으므로 잃은 것이 없다.

## 개발 환경

`docker-compose.dev.yml`은 `rabbitmq-data` 볼륨을 유지하므로 T001 이전에 한 번이라도 백엔드를 띄운 PC는
전부 해당된다. 백엔드(`bootRun` 또는 compose)와 워커를 내린 뒤:

```bash
./infra/scripts/migrate-jobs-queue.sh --dev
```

개발 모드는 컨테이너를 대신 내리지 않고 확인·삭제만 한다. 이후 백엔드를 띄우면 quorum으로 선언된다.
확인:

```bash
docker compose -f docker-compose.dev.yml exec rabbitmq rabbitmqctl list_queues name type arguments
# backtest.jobs  quorum  [{"x-queue-type","quorum"},{"x-delivery-limit",1},{"x-dead-letter-exchange","backtest.dlx"},...]
```

볼륨을 통째로 버려도 된다면 `docker compose -f docker-compose.dev.yml down -v`가 더 빠르다(PostgreSQL 데이터도 사라진다).

## 워커 실행 환경

| 항목 | 값 | 근거 |
|---|---|---|
| `NUMBA_NUM_THREADS` `OMP_NUM_THREADS` `OPENBLAS_NUM_THREADS` `MKL_NUM_THREADS` | `1` | 결정성(research R-14). 수치 라이브러리가 호스트 vCPU 수만큼 스레드를 띄우면 합산 순서가 달라져 같은 입력에 다른 결과가 나올 수 있고, 워커 2개 × 4스레드가 4코어에서 경쟁한다. dev·prod compose 모두 적용 |
| `MARKET_DATA_URL` | 시장 자료 읽기 전용 접속 | research R-09. 워커는 결과 DB에 쓰지 않는다(계약 §1). 시장 자료 공급자(T004)가 이 변수를 읽는다. 읽기 전용 role은 T003 마이그레이션이 만들며 그 전까지 dev는 일반 계정을 가리킨다 |
| `WORKER_REPLICAS` × prefetch 1 | **동시 실행 상한** | research R-19. 백엔드는 접수 시 동시 실행 상한을 검사하지 않는다 — 검사해도 보장이 안 된다. competing consumers 구조에서 동시 실행 수는 정확히 replica × prefetch(1)이므로 이 값이 상한이다. 운영 4 vCPU에서 2 |

## 장애 시나리오 확인 (quickstart S6)

절차만 여기 두고, 실측 결과는 T013 검증 기록(`docs/guides/backtest-execution-verification-2026-09.md`)에 적는다.

1. 워커 2 replica, RUNNING 중인 실행 하나.
2. 그 실행을 처리 중인 워커 컨테이너를 `docker kill`(SIGKILL). 기대: 브로커가 메시지를 다른 워커에 재배달, 새 `executionId`의 STARTED → `attempt=2`, 실행은 완료. 측정 기록 `auto_retried=true`.
3. 다시 RUNNING 중에 두 번째 워커를 `docker kill`. 기대: `x-delivery-limit=1` 초과 → 메시지가 `backtest.jobs.dlq`로 이동 → 백엔드 DLQ 리스너가 `FAILED(SYSTEM_FAILURE)`로 종결(T010).
4. 확인 명령:

```bash
rabbitmqctl list_queues name type messages messages_ready messages_unacknowledged
psql -c "select id, status, attempt, publish_count, dlq_count, failure_code from backtest_run order by id desc limit 3"
```

| 단계 | 기대 | 실측 | 일자 |
|---|---|---|---|
| kill 1회 | 재실행·완료, attempt=2 | (T013) | |
| kill 2회 | DLQ → FAILED(SYSTEM_FAILURE) | (T013) | |

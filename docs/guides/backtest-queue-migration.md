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
| 신규 접수가 막혀 있다(nginx 정지) | 지우는 사이 새 작업이 들어오면 잃는다. **백엔드는 내리지 않는다** — 아래 이벤트 큐를 비워야 한다 |
| `backtest.jobs`의 `messages_ready = 0` | 대기 중인 작업이 있으면 잃는다. **미ACK만 보는 것으로는 부족하다** |
| `backtest.jobs`의 `messages_unacknowledged = 0` | 워커가 처리 중인 작업이 있으면 그 결과가 붙을 메시지가 사라진다 |
| **`backtest.events`도 둘 다 0** | v2 백엔드는 v1 이벤트(`schemaVersion 1`)를 계약 오류로 거절해 DLQ로 보낸다(계약 README §3). 남겨 두면 그 실행들의 결과가 DB에 저장되지 않는다 |
| **v2 이미지가 빌드돼 있다** | 전환 뒤 `up -d`가 예전 v1 이미지를 띄우면 classic 큐가 그대로 되살아난다 |

```bash
docker compose -f infra/docker-compose.prod.yml --env-file infra/.env.prod exec rabbitmq \
  rabbitmqctl list_queues name type messages_ready messages_unacknowledged
```

**결과 이벤트까지 v1 백엔드가 처리해야 한다.** 작업 큐만 비우는 것으로는 부족하다. 워커가 마지막 작업을
끝내면 `backtest.events`에 v1 이벤트를 넣는데, 이것을 소비할 수 있는 것은 v1 백엔드뿐이다. 그래서 드레인
동안 백엔드와 워커를 모두 살려 두고 nginx만 멈춰 신규 접수를 막는다. 두 큐가 모두 0이 되면 그때까지 접수된
실행의 결과가 DB에 반영된 것이다.

숫자가 0이 아닌데 줄지도 않으면 워커가 살아 있는지, 백엔드가 이벤트를 처리 중인지 본다(`logs --tail 50 backend`).
**기존 실행 기록을 버려도 되는 환경이라면** 드레인 없이 큐와 볼륨을 지워도 되지만, 그때는 "결과가 보존된다"고
안내하지 않는다 — 남아 있던 작업과 결과는 사라진다.

## 운영 전환 절차

`infra/scripts/migrate-jobs-queue.sh`가 아래를 순서대로 하고, 실패하면 되돌리는 방법을 출력한다.
**레포가 배포할 버전(v2)으로 동기화된 상태에서 실행한다** — 2단계 빌드가 그 소스를 쓴다. Jenkins는 Sync
단계 뒤에 Deploy를 호출하므로 `deploy.sh`가 멈춘 시점의 `$DEPLOY_DIR`는 이미 v2다.

1. 큐 상태 확인. 큐가 없거나 이미 quorum이면 아무것도 하지 않고 끝난다.
2. **v2 `backend`·`worker` 이미지 빌드**(기존 서비스는 계속 실행). 내리기 전에 빌드해야 5단계에서 v2가 뜬다.
3. 신규 접수 차단: `nginx`만 정지. 백엔드·워커는 남은 일을 끝내야 하므로 살려 둔다.
4. 드레인: `backtest.jobs`와 `backtest.events`가 **모두 0**이 될 때까지 10초 간격으로 확인(기본 상한 30분,
   `DRAIN_TIMEOUT_SECONDS`로 조절). 시간을 넘기면 `nginx`를 다시 올리고 중단한다(전환 포기).
5. `backend`·`worker` 정지 → `rabbitmqctl delete_queue backtest.jobs` → `backend`(v2) 기동 →
   최대 60초 안에 `type=quorum` 확인.
6. `worker`(v2)·`nginx` 기동.

```bash
cd "$DEPLOY_DIR/infra"
./scripts/migrate-jobs-queue.sh
```

드레인 동안 사용자에게는 서비스가 닫혀 있다. 진행 중인 백테스트가 끝나야 하므로 점검 시간은 가장 긴 실행
하나만큼이다.

**배포 순서와의 관계.** 이 전환은 계약 v2 백엔드(T001 이후 dev)를 **처음 배포하는 그 단계**에서 한 번 실행한다.
`deploy.sh`는 시작 시 `backtest.jobs`가 classic이면 배포를 멈추고 이 스크립트를 먼저 실행하라고 알린다.
백엔드와 워커는 같은 배포에서 함께 올린다 — v1 워커는 v2 작업 메시지를 계약 오류로 거절하고(DLQ),
v2 백엔드는 v1 이벤트를 거절한다(계약 README §3).

**되돌리기.** 5단계에서 quorum 재선언이 확인되지 않으면 백엔드 로그를 본다. 이전 버전으로 체크아웃해
`backend` 이미지를 다시 빌드·기동하면 classic 큐가 다시 선언된다(v1 이미지가 남아 있지 않을 수 있으므로
`up -d`만으로는 부족하다). 그 사이 워커는 올리지 않는다. 두 큐는 4단계에서 비운 상태이므로 잃은 메시지가 없다.

## 개발 환경

`docker-compose.dev.yml`은 `rabbitmq-data` 볼륨을 유지하므로 T001 이전에 한 번이라도 백엔드를 띄운 PC는
전부 해당된다. **백엔드와 워커를 켜 둔 채로**(두 큐를 비워야 한다) 새 요청만 보내지 않고 실행한다:

```bash
./infra/scripts/migrate-jobs-queue.sh --dev
```

개발 모드는 이미지 빌드와 nginx 정지를 건너뛰고, 두 큐가 빌 때까지 기다린 뒤 삭제한다. 컨테이너로 띄운
경우가 아니면(`bootRun`·`python -m worker.main`) 정지는 사람이 한다. 이후 백엔드를 띄우면 quorum으로 선언된다.
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
| (`WORKER_SINGLE_REPLICAS` + `WORKER_FLEX_REPLICAS`) × prefetch 1 | **동시 실행 상한** | research R-19. 백엔드는 접수 시 동시 실행 상한을 검사하지 않는다 — 검사해도 보장이 안 된다. competing consumers 구조에서 동시 실행 수는 정확히 replica × prefetch(1)이므로 이 값이 상한이다. 운영 4 vCPU에서 1+1(R-20 큐 분리: 단건 전용 + 벌크 우선 공용) |

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

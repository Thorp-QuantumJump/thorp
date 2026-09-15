# Thorp — 퀀트 전략 분석 플랫폼

> Team Quantum Jump · SSAFY S15P21E205

국내 섹터 ETF의 배분 전략과 설정 조합이 과거 수익·손실·비용에 어떤 차이를 만드는지 같은 조건에서
비교하는 학습·시뮬레이션 플랫폼. 제품 방향과 범위의 원본은 Notion 기획서, 프로젝트 원칙은
[`.specify/memory/constitution.md`](.specify/memory/constitution.md)다.

> **현재 개발 범위**: 다섯 배분 전략(고정 비중·동일 비중·역변동성·섹터 모멘텀·최소분산), 자동 파라미터
> 대량 탐색, 전략과 독립된 분야별 경제환경 검색. **현재 코드 상태**는 아래 "현재 구현 상태"를 본다 —
> 다종목 섹터 ETF 배분 백테스트가 접수부터 결과 조회까지 끝에서 끝까지 붙어 있다.

## 목표 아키텍처

```
사용자 → Frontend → Backend (Job 생성/조회 API)
                        ↓
                    RabbitMQ (Job 전달/분배)
                        ↓
              Backtest Worker × N (Python, 실제 연산)
                        ↓
                    PostgreSQL (결과 저장)
```

- Backend는 백테스트를 직접 계산하지 않고 Job을 만들어 RabbitMQ에 넣는다.
- Worker들이 같은 큐를 구독해 Job을 나눠 처리한다. (competing consumers, 개수를 늘려 분산 처리)
- 백테스트 요청은 비동기: `QUEUED → RUNNING → COMPLETED` 상태를 폴링으로 조회.
- Spark/Hadoop은 **계산량 실측 후** 도입을 결정한다. 그때를 대비해 연산은 Worker 뒤에,
  데이터는 Parquet 친화적으로 격리해 두었다.

### 현재 구현 상태 (2026-09-15, spec 004 T001~T015 병합 기준)

- **끝에서 끝까지 붙어 있다.** `POST /backtests` 접수 → 커밋 뒤 RabbitMQ 발행 → 워커가 001 자료로 계산 →
  `backtest.events`로 STARTED/PROGRESS/FINISHED → 백엔드 결과 Consumer가 PostgreSQL에 저장 → `GET /backtests/{id}`와
  하위 리소스(시계열·거래·보유·비중·예외)로 조회. 계약은 [`docs/specs/backtest-api/`](docs/specs/backtest-api/)와
  [`docs/specs/backtest-messaging/`](docs/specs/backtest-messaging/), 설계 결정은 [ADR-0006](docs/adr/0006-backtest-request-run-split.md).
- **워커는 다종목 리밸런싱 엔진 하나**(`portfolio_execution.py` + `allocation/`)다. 다섯 배분 전략 중 고정 비중·동일 비중·역변동성은
  실행되고, 섹터 모멘텀·최소분산은 계산 규칙이 팀 결정(PD-03·PD-04) 대기라 접수에서 `422 RULES_NOT_REGISTERED`로 거절한다.
  목표 위험 맞춤(`TARGET_RISK`) 오버레이가 있다. 단일 종목 모멘텀 DEMO 엔진은 삭제됐다.
- **요청과 실행은 다대일이다.** 같은 조건의 요청은 진행 중 실행에 합류하거나 완료 결과를 재사용한다. 취소는 요청 단위이고 실행 중 취소는
  fanout 제어 채널로 워커를 실제로 멈춰 부분 결과를 남긴다. 실패·취소한 요청은 재시도할 수 있다.
- **작업 큐는 둘**이다 — `backtest.jobs`(단건)·`backtest.jobs.bulk`(자동 탐색·경제환경 파생). quorum 큐 + `x-delivery-limit=1`로 워커
  크래시는 한 번 자동 재시도되고 두 번째는 DLQ다. 워커는 단건 전용(`WORKER_ROLE=single`)과 벌크 우선 공용(`flex`) 두 역할로 배치한다.
- **실행 한도 장치**(사용자별·단건 큐 전체 대기 상한 → 429, 실행 시간 상한·정체 → CANCEL, 취소 무응답 → 강제 종결)와 **측정 기록**
  (`backtest_run_metric`, 뷰 `v_run_metric_summary`, 운영자 API `GET /internal/execution-metrics`)이 있다. 한도 **값**은 임시값이며
  측정 자료로 팀이 정한다(PD-06).
- **아직 없는 것**: 로그인(spec 014 — 개발 환경은 `X-User-Id` 헤더, 운영자 경로는 `X-Ops-Token`), 실시간 진행 푸시(폴링으로 조회),
  진짜 KRX 자료 버전을 접수가 가리키는 것(백엔드 자료 메타데이터가 아직 픽스처 상수 — 001 T019와 함께 풀 후속).
- 브로커는 RabbitMQ(ADR-0002). Kafka는 설치만 돼 있고 Job 전달에 쓰지 않는다.

처음 코드를 읽는다면 [`backend/CLAUDE.md`](backend/CLAUDE.md)·[`worker/CLAUDE.md`](worker/CLAUDE.md)의 규칙과
[ADR-0006](docs/adr/0006-backtest-request-run-split.md)을 먼저 본다. 끝에서 끝까지 직접 돌려 보려면
[`specs/004-backtest-execution/quickstart.md`](specs/004-backtest-execution/quickstart.md)를 따른다.

## 폴더 구조

| 폴더        | 파트  | 스택                          | 설명 |
|-------------|-------|-------------------------------|------|
| `frontend/` | FRONT | React + Vite + TypeScript     | 알고리즘/파라미터 선택 UI, 결과 시각화 |
| `backend/`  | BACK  | Spring Boot 3 (Java 21, Gradle) | 백테스트 Job 생성·상태·결과 API |
| `worker/`   | BACK/AI | Python 3.11 + pandas        | RabbitMQ에서 Job을 받아 백테스트 실행 |
| `data/`     | DATA  | Python 3.11 + pykrx/pandas    | KRX 시세 수집·정제 파이프라인 |
| `ai/`       | AI    | Python                        | (예약) 유사 국면 탐색·GDELT 등 확장 |
| `infra/`    | INFRA | Docker, Compose               | Dockerfile, 배포/운영 스크립트 |
| `docs/`     | 공통  | Markdown                      | ADR, 스펙 문서 |

## 빠른 시작

```bash
# 0. 환경변수 준비
cp .env.example .env

# 1. 인프라(PostgreSQL + RabbitMQ) 실행
docker compose -f docker-compose.dev.yml up -d postgres rabbitmq
#    기존 rabbitmq-data 볼륨이 있으면(메시지 계약 v2 이전에 백엔드를 띄운 적 있음) 작업 큐를 1회 전환한다:
#    ./infra/scripts/migrate-jobs-queue.sh --dev     (docs/guides/backtest-queue-migration.md)

# 2. 백엔드
cd backend && ./gradlew bootRun

# 3. 워커
cd worker
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt -r requirements-dev.txt
python -m worker.main

# 4. 프론트엔드
cd frontend && npm install && npm run dev

# 5. 데이터 수집 (예시)
cd data
pip install -r requirements.txt
python scripts/ingest_krx.py --start 2021-01-01 --end 2026-08-26
```

전체를 컨테이너로 띄우려면:

```bash
docker compose -f docker-compose.dev.yml --profile app up -d --build
```

## 컨벤션

프로젝트 원칙은 [constitution](.specify/memory/constitution.md)이 최상위이고, 브랜치/커밋/MR 컨벤션과
AI 협업 규칙은 [CLAUDE.md](./CLAUDE.md)와 팀 Notion(Git Convention·Jira Convention)을 따른다.
MR 본문 양식은 `.gitlab/merge_request_templates/Default.md`가 자동 적용된다.

- 브랜치: `{type}({position})/{JIRA_NUMBER}-{description}` (예: `feat(be)/12-kakao-oauth`)
- 커밋: `feat(be): 작업 내역 요약`
- 매주 금요일 버전 릴리즈

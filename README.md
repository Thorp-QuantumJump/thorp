# Quantum Jump — 퀀트 백테스트 플랫폼

과거 시장 데이터로 퀀트 전략을 백테스트하고 비교할 수 있는 학습·시뮬레이션 플랫폼.

> **MVP v0.1.1**: KRX API로 데이터를 받아 알고리즘 1개(파라미터 고정, 기간 5년)를 백테스트하는 DEMO

## 아키텍처

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

### 공통 Python 실행 도구

팀의 공통 Python 실행 도구로 [uv](https://docs.astral.sh/uv/)를 사용한다. 개발 환경과 Spec Kit
자동화를 실행하는 CI에는 `uv`가 설치되어 있어야 한다. 최초 한 번 Python 3.11을 준비한다.

```bash
uv python install 3.11
```

프로젝트의 Spec Kit 스킬은 사용자의 Python 설치 경로를 저장하지 않고 다음과 같이 실행한다.

```bash
uv run --python 3.11 python .specify/scripts/python/check_prerequisites.py --json --paths-only
```

`specify integration install` 또는 `specify integration upgrade` 후에는 생성된 스킬에 개발자별 절대
경로가 다시 포함되지 않았는지 다음 명령으로 확인한다.

```bash
rg -n --hidden -S '([A-Za-z]:\\Users\\|/Users/|/home/)' .agents/skills .claude/skills
specify integration status
```

```bash
# 0. 환경변수 준비
cp .env.example .env

# 1. 인프라(PostgreSQL + RabbitMQ) 실행
docker compose -f docker-compose.dev.yml up -d postgres rabbitmq

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

브랜치/커밋/MR 컨벤션과 AI 협업 규칙은 [CLAUDE.md](./CLAUDE.md)와 팀 Notion을 따른다.

- 브랜치: `{type}({position})/{JIRA_NUMBER}-{description}` (예: `feat(be)/12-kakao-oauth`)
- 커밋: `feat(be): 작업 내역 요약`
- 매주 금요일 버전 릴리즈

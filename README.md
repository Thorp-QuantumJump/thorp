# Thorp — 퀀트 전략 분석 플랫폼

> Team Quantum Jump · SSAFY S15P21E205

Thorp는 국내 섹터 ETF의 배분 규칙과 설정을 **같은 기간·금액·비용 조건에서 과거 데이터로 비교**하는
전략 진단 플랫폼입니다. 수익이 가장 높았던 전략을 추천하는 대신, 선택한 규칙이 언제 어떻게 움직였고
수익·손실·거래비용에 어떤 차이를 만들었는지 확인하도록 돕습니다.

제품 방향과 범위의 원본은 팀 Notion 기획서이며, 저장소에서 지켜야 할 원칙은
[`constitution`](.specify/memory/constitution.md)에 있습니다.

## 우리가 풀려는 문제

투자 전략은 이름만으로 비교하기 어렵습니다. 같은 전략도 대상 섹터, 리밸런싱 주기, 비용, 분석 기간이
달라지면 결과의 의미가 달라집니다. 설정 조합이 늘어나면 유리한 사례만 골라 보거나 서로 다른 조건의
결과를 같은 기준처럼 비교하기도 쉽습니다.

Thorp는 다음 세 가지 질문에 답할 수 있는 분석 흐름을 만듭니다.

1. **같은 조건에서 규칙을 바꾸면 결과가 어떻게 달라지는가?**
2. **전체 수익뿐 아니라 가장 크게 줄었던 폭과 거래비용은 어떠한가?**
3. **과거의 경제환경과 당시 뉴스를 함께 보면 전략의 행동을 어떻게 해석할 수 있는가?**

## Thorp의 접근 방식

- **비교 조건을 고정합니다.** 후보 전략은 같은 섹터 집합·평가 기간·금액·비용·정보 기준으로 계산합니다.
- **복잡한 계산은 시스템이 맡습니다.** 사용자는 섹터와 전략을 고르고, 계산 작업은 비동기 Worker가
  처리합니다.
- **결과와 근거를 함께 남깁니다.** 사용한 데이터·전략·비용·모델 버전과 자료 부족 여부를 기록해 같은
  분석을 다시 설명할 수 있게 합니다.
- **진단과 추천을 구분합니다.** 기본값은 입력을 돕는 출발점일 뿐 최적값이나 투자 권고가 아닙니다.

## 핵심 기능

### 전략 진단

- 국내 섹터 ETF 여러 종목을 한 포트폴리오로 구성
- 고정 비중·동일 비중·역변동성 배분 규칙 실행
- 비용 차감 후 성과, 최대 하락 폭, 거래비용, 시계열, 거래·보유·비중·예외 기록 조회
- 비동기 실행 상태 조회와 취소·재시도·부분 결과 처리
- 같은 조건의 진행 중 실행 합류 및 완료 결과 재사용

### 설정 조합 자동 비교

- 실행 전에 후보 수와 임시 실행 한도를 확인
- 등록 범위를 벗어난 조합을 계산 전에 제외하고 이유를 기록
- 대량 후보를 별도 작업 큐로 실행하고 진행·취소·부분 완료·실패 후보 재시도 지원
- 사전에 정한 기준으로 표시 후보를 좁히되, 순위나 추천으로 표현하지 않음
- 미사용 검증 기준이 확정되지 않은 결과는 `UNVALIDATED`로 명시

### 분석 맥락과 사용자 흐름

- KOSPI·KOSDAQ·환율·변동성·금리·경기·물가·수출·통화량 시장 지표 API
- 섹터 목록과 대표 ETF 자료·출처 조회
- RSS·GDELT 기반 뉴스 수집, 묶음 생성, 현재·과거 뉴스 조회 API
- 회원가입·이메일 인증·로그인·세션 복원과 보호된 내 기록 진입점

## 현재 구현 수준

아래 표는 **코드와 검증 기록을 기준으로 한 현재 상태**입니다. 기능 구현, 사용자 사용성 확인, 투자 판단에
도움이 된다는 효과 입증은 서로 다른 단계이며, 하나를 다른 단계의 근거로 사용하지 않습니다.

| 영역 | 현재 상태 | 확인된 범위와 남은 경계 |
|---|---|---|
| 백테스트 실행 경로 | **구현·E2E 동작 확인** | Backend 접수 → RabbitMQ → Worker → PostgreSQL → 결과 조회가 실제 프로세스로 검증됐습니다. |
| 배분 엔진 | **일부 전략 구현** | 고정 비중·동일 비중·역변동성은 실행됩니다. 섹터 모멘텀·최소분산은 계산 규칙 확정 전이라 접수에서 거절합니다. |
| 백테스트 화면 | **UI 프로토타입** | `/backtest`의 차트와 비교 화면은 예시 데이터를 사용하며 실제 백테스트 API와 아직 연결되지 않았습니다. |
| 자동 설정 비교 | **Backend·Frontend 구현** | 확인·접수·진행·선정·취소·재시도 화면과 API가 있습니다. 평가 분할이 미등록이라 결과는 현재 모두 `UNVALIDATED`입니다. |
| ETF·시장 자료 | **파이프라인·조회 API 구현, 연동 보완 중** | KRX 수집과 조회 기능이 있으나 백테스트 접수의 데이터 버전은 아직 로컬 검증용 픽스처를 가리킵니다. 외부 지표는 API 키와 제공기관 상태에 의존합니다. |
| 시장 흐름 | **API·일부 화면 구현** | 시장 지표 조회와 화면 구성이 있습니다. 분야별 경제환경 유사 구간 검색과 전략 비교 연결은 후속 범위입니다. |
| 뉴스 | **Backend API 구현** | 수집·필터·묶음·조회와 실패 격리를 검증했습니다. 사용자 화면 통합은 후속 범위입니다. |
| 사용자 인증 | **일부 흐름 구현** | 회원가입, 이메일 인증, 로그인, 세션 복원과 화면 접근 보호가 있습니다. Backend 로그아웃 API와 백테스트의 개발용 사용자 헤더를 세션으로 교체하는 작업은 남아 있습니다. |
| 내 기록 | **진입 경계만 구현** | 로그인 후 보호된 진입 화면까지 있으며 기록 목록·다시 보기·보관 기능은 아직 구현되지 않았습니다. |
| 배우기 | **명세 단계** | 입문자를 위한 학습 페이지와 진단 연결은 제품 범위에 포함되지만 아직 화면으로 구현되지 않았습니다. |

백테스트 실제 실행 검증 범위와 제약은
[`backtest-execution-verification-2026-09.md`](docs/guides/backtest-execution-verification-2026-09.md)에서 확인할 수
있습니다. 전체 기능의 범위·우선순위·미확정 항목은 [`specs/README.md`](specs/README.md)가 기준입니다.

## 백테스트 서비스 실행 구조

```text
사용자
  │
  ▼
Frontend/API client ── 요청·상태 조회 ──▶ Backend
                                  │  요청 검증·실행 기록
                                  ▼
                     RabbitMQ ── 작업 큐 분배
                                  │
                                  ▼
                       Python Worker × N
                                  │  시장 자료 조회·백테스트 계산
                                  ▼
                     RabbitMQ ── 진행·결과 이벤트
                                  │
                                  ▼
                              Backend
                                  │  상태·결과 저장
                                  ▼
                            PostgreSQL
```

- Backend는 백테스트를 계산하지 않고 요청을 검증한 뒤 작업을 발행합니다.
- 아래 Backend–Worker 경로는 구현돼 있지만 현재 백테스트 UI는 아직 이 API client 경로에 연결되지 않았습니다.
- `backtest.jobs`는 단건 분석, `backtest.jobs.bulk`는 자동 탐색과 파생 실행을 처리합니다.
- Worker는 같은 코드를 역할별로 실행하며 RabbitMQ의 competing consumers 방식으로 작업을 나눕니다.
- 상태는 `QUEUED → RUNNING → COMPLETED | FAILED | CANCELLED`로 변합니다. 현재 화면 갱신 방식은 폴링입니다.
- Kafka는 개발 환경에 설치돼 있지만 백테스트 Job 전달에는 사용하지 않습니다.
- Spark·Hadoop 도입은 확정 사항이 아닙니다. 실행 측정으로 병목을 확인한 뒤 결정합니다.

메시지와 HTTP 계약은 각각 [`backtest-messaging`](docs/specs/backtest-messaging/)과
[`backtest-api`](docs/specs/backtest-api/)에 있으며, 요청과 실행을 분리한 설계 결정은
[`ADR-0006`](docs/adr/0006-backtest-request-run-split.md)에 있습니다.

## 기술 구성

| 영역 | 주요 기술 | 역할 |
|---|---|---|
| Frontend | React 19, TypeScript, Vite | 전략·시장·인증 화면과 결과 시각화 |
| Backend | Java 21, Spring Boot 3.5, Gradle | API, 실행 조정, 상태·결과 저장 |
| Worker | Python 3.11, pandas, NautilusTrader | 다종목 리밸런싱과 성과 계산 |
| Data | Python 3.11, HTTP client | KRX 가격·기업행위·섹터 자료 수집·정제 |
| Messaging | RabbitMQ | 단건·대량 작업 분배와 진행·결과 이벤트 전달 |
| Storage | PostgreSQL 16 | 사용자 요청, 실행 상태, 결과와 재현 정보 저장 |
| Infra | Docker Compose, Nginx, Jenkins | 로컬·운영 실행과 CI/CD |

## 빠른 시작

### 준비물

- Docker와 Docker Compose
- 프론트엔드를 실행하려면 Node.js와 npm
- 서비스별로 직접 개발하려면 Java 21, Python 3.11, `uv`가 추가로 필요합니다.

### 1. 환경변수 준비

macOS/Linux:

```bash
cp .env.example .env
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

기본 PostgreSQL·RabbitMQ 계정은 로컬 개발용입니다. `.env`는 커밋하지 않습니다. 외부 API 키가 없어도
핵심 컨테이너는 실행할 수 있지만, KRX·ECOS·KOSIS·공공데이터 시장 조회와 이메일 발송은 각각의 키나
계정이 필요합니다. 필요한 변수와 발급 조건은 [`.env.example`](.env.example)의 주석을 확인하세요.

### 2. Backend와 Worker 실행

```bash
docker compose -f docker-compose.dev.yml --profile app up -d --build
docker compose -f docker-compose.dev.yml ps
```

이 명령은 PostgreSQL, RabbitMQ, 개발용 Kafka, Backend, 단건 Worker와 공용 Worker를 실행합니다.

기존 `rabbitmq-data` 볼륨을 메시지 계약 v2 이전부터 사용했다면 서비스를 올리기 전에
[`backtest-queue-migration.md`](docs/guides/backtest-queue-migration.md)의 안전 조건과 전환 절차를 먼저
확인하세요.

### 3. Frontend 실행

```bash
cd frontend
npm ci
npm run dev
```

- Frontend: `http://localhost:5173`
- Backend health: `http://localhost:8080/actuator/health`
- RabbitMQ 관리 화면: `http://localhost:15672` — 로컬 기본 계정 `thorp` / `thorp`

화면은 기능별 구현 상태에 따라 실제 API와 예시 데이터를 함께 사용합니다. 실제 백테스트 파이프라인을
로컬 픽스처로 재현하려면 [`spec 004 quickstart`](specs/004-backtest-execution/quickstart.md)를 따르세요.

### 4. 종료

```bash
docker compose -f docker-compose.dev.yml --profile app down
```

이 명령은 컨테이너와 네트워크를 내리지만 데이터 볼륨은 보존합니다.

## 저장소 안내

| 경로 | 내용 | 먼저 볼 문서 |
|---|---|---|
| `frontend/` | React 화면과 API 연동 | [`frontend/CLAUDE.md`](frontend/CLAUDE.md) |
| `backend/` | Spring API와 실행 조정 | [`backend/CLAUDE.md`](backend/CLAUDE.md) |
| `worker/` | 백테스트 계산 Worker | [`worker/README.md`](worker/README.md) |
| `data/` | KRX 데이터 파이프라인 | [`data/README.md`](data/README.md) |
| `specs/` | 기능 명세·계획·작업·계약 | [`specs/README.md`](specs/README.md) |
| `docs/adr/` | 주요 설계 결정 | [`docs/adr/0006-backtest-request-run-split.md`](docs/adr/0006-backtest-request-run-split.md) |
| `docs/specs/` | 구현된 API·메시지 계약 | [`docs/specs/backtest-api/`](docs/specs/backtest-api/) |
| `infra/` | 이미지·배포·운영 스크립트 | [`infra/docker-compose.prod.yml`](infra/docker-compose.prod.yml) |

## 개발과 검증

파트별 규칙과 대표 검증 명령은 각 폴더의 `CLAUDE.md`를 따릅니다.

```bash
# Backend
cd backend && ./gradlew spotlessCheck test

# Frontend
cd frontend && npm run lint && npm run build

# Worker
cd worker && uv run ruff check src tests scripts && uv run ruff format --check src tests scripts && uv run pytest

# Data
cd data && uv run ruff check src scripts tests && uv run pytest
```

브랜치·커밋·Jira·AI 협업 규칙은 [`AGENTS.md`](AGENTS.md), 최상위 제품 원칙과 완료 기준은
[`constitution`](.specify/memory/constitution.md)을 따릅니다.

## 주의

Thorp의 출력은 선택한 규칙을 과거 데이터로 진단한 결과입니다. 미래 수익을 보장하거나 특정 전략·ETF의
매매를 추천하지 않습니다. 구현된 기능, 사용성 확인, 효과 검증의 상태는 위 "현재 구현 수준"과 각 spec의
검증 기록을 기준으로 판단합니다.

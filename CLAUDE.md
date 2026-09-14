# CLAUDE.md — Team Quantum Jump

> **최상위 규칙은 [`.specify/memory/constitution.md`](.specify/memory/constitution.md)이다.** 이 문서와
> 파트 CLAUDE.md는 constitution의 범위 안에서 운영 세부 규칙을 정한다. 충돌하면 constitution을 따르고
> 충돌한 문서를 고친다. 제품 방향·범위·결정(D01~D27)의 원본은 Notion 기획서다.

AI(Claude 등)와 협업할 때 이 문서를 기준으로 한다. 코드리뷰는 AI를 통해서만 진행하고,
발견된 문제는 해결한 후 Merge한다.

## 프로젝트 개요

**Thorp**: 국내 섹터 ETF의 배분 전략과 설정 조합이 과거 수익·손실·비용에 어떤 차이를 만드는지 같은
조건에서 비교하는 퀀트 전략 분석 플랫폼. 사용자가 섹터·전략·설정을 고르면 과거 KRX 데이터로
백테스트를 실행하고 결과(비용 차감 후 수익률, 최대 하락 폭, 거래비용 등)를 보여준다.

- 현재 개발 범위(기획서 §8.1): 다섯 배분 전략(고정 비중·동일 비중·역변동성·섹터 모멘텀·최소분산),
  자동 파라미터 대량 탐색, 전략과 독립된 분야별 경제환경 검색(선택형 Thorp 종합 비교), 시장 흐름·뉴스·
  배우기 페이지. 이번 스프린트의 범위와 수치 기본값은 constitution이 아니라 각 spec에 쓴다.
- 현재 코드 상태: 단일 KRX 종목의 일봉 모멘텀 백테스트를 Backend → RabbitMQ → Worker → PostgreSQL
  경로로 실행하는 DEMO 단계다(README "현재 구현 상태" 참고).
- 분산 처리(분산 Worker·Spark)는 병목을 실측한 뒤 판단한다. 기능 확대가 곧 분산 도입 확정을 뜻하지 않는다.

## 아키텍처 원칙

1. **Backend는 계산하지 않는다.** 백테스트는 Job으로 만들어 RabbitMQ에 넣고, Worker가 처리한다.
2. Worker는 같은 코드(`worker/`)를 여러 개 실행해 수평 확장한다.
   RabbitMQ의 competing consumers 구조라 Kafka 파티션 같은 개수 제약이 없고, 병목은 CPU다.
   Kafka는 설치만 해둔 상태이며 현재 Job 전달에 쓰지 않는다. 전환·병행 시점은 실측 후 결정한다.
3. 백테스트 API는 비동기. `POST /backtests` → `{ backtestId, status: QUEUED }` 즉시 반환,
   상태는 `GET /backtests/{id}`로 조회. 상태 흐름: `QUEUED → RUNNING → COMPLETED | FAILED`.
4. **빅데이터 기술(Spark/Hadoop)을 먼저 선택하지 않는다.** 계산량을 실측한 뒤 병목이 확인되면
   도입한다. 단, 도입 가능성을 열어두기 위해 데이터 저장은 Parquet 친화적으로, 연산 로직은
   Worker 내부에 격리한다.

## 모노레포 구조

각 파트 폴더에 파트 전용 CLAUDE.md가 있다. **해당 폴더 작업 시 그 파일의 규칙과 검증 명령을 따른다.**


- `frontend/` React 19 + Vite + TypeScript — FRONT 파트
- `backend/` Spring Boot 3.5, Java 21, Gradle — BACK 파트
- `worker/` Python 3.11, pandas 기반 백테스트 워커 — RabbitMQ consumer
- `data/` Python, pykrx 기반 KRX 데이터 수집 파이프라인 — DATA 파트
- `ai/` (예약) 유사 국면 탐색 등 AI 파트 확장 영역
- `infra/` Dockerfile, 운영 스크립트 — INFRA 파트
- `docs/` ADR(`docs/adr/`), 스펙 문서(`docs/specs/`)

## Git Convention (동결)

브랜치: `{type}({position})/{JIRA_NUMBER}-{description}`

- type: `feat` `fix` `refactor` `chore` `docs`
- position: `fe` `be` `infra` `ai` `de`
- 예: `feat(be)/12-kakao-oauth`

커밋: `feat(be): 작업 내역 요약` + 본문(선택)

MR 템플릿: 작업 내용 요약 / 관련 이슈(close #) / 주요 변경 사항 / 테스트 체크리스트 / 리뷰 포인트

릴리즈: 매주 금요일.

## 코드 컨벤션

- Java: 표준 Spring 계층 구조 (controller → service → repository). DTO는 record 우선.
  포맷은 Spotless(google-java-format AOSP, 4칸 들여쓰기)로 강제한다.
  커밋 전 `./gradlew spotlessApply`, CI/build에서 `spotlessCheck` 자동 수행.
- Python: ruff로 lint/format, 타입 힌트 필수, pytest로 테스트.
- TypeScript: ESLint + Prettier 기본 설정, 컴포넌트는 함수형.
- API JSON 필드는 camelCase.

## AI 협업 규칙

- MR 올리기 전 AI 코드리뷰를 수행하고, 발견된 문제를 해결한 뒤 Merge한다.
- AI가 생성한 코드도 동일한 컨벤션/테스트 기준을 통과해야 한다.
- 스펙이 애매하면 코드를 만들기 전에 `docs/specs/`의 문서를 먼저 확인/갱신한다. (SDD)

## Jira 운영 규칙

최상위 규칙은 [`.specify/memory/constitution.md`](.specify/memory/constitution.md)의 "Git·Jira 규칙"이다.
아래는 AI가 티켓을 다룰 때의 실행 규칙이다.

- **1 티켓 = 1 브랜치 = 1 MR.** 티켓 안에서 작업을 어떻게 나누든 자유지만, 티켓 하나를 여러 브랜치로
  쪼개거나 서로 다른 티켓의 작업을 한 MR에 담지 않는다.
- **작업 시작 전 티켓이 없으면 만든다.** 제목 `[BE|FE|AI|DE|INFRA|DOCS] 작업 내용`, 담당자=작업할 사람,
  스토리 포인트 필수(1·2·3·5·8·13), 스프린트=현재 활성 스프린트, Epic 연결, 구현 대상 Story와
  `relates to` 연결. 확인 후 진행 중으로 전이한다.
  - 담당자는 티켓을 만들기 전에 `atlassianUserInfo`로 현재 계정을 확인하고 이 사람이 맞는지 물어본다.
    대화 중 한 번 확인했으면 다시 묻지 않는다. git config로 추측하지 않는다.
- **하위 티켓(Sub-task) 유형은 만들지 않는다.** 머지·리뷰 같은 진행 사실도 티켓으로 만들지 않는다.
- **MR이 dev에 머지되면 즉시 완료로 전이한다.** 필요한 문서 수정은 머지 전에 끝내고, 남길 거면
  별도 티켓으로 뺀다.
- **스토리 포인트는 작업 전 추정값이다.** 머지 후 스스로 조정하지 않는다.

### Atlassian MCP 필드

- cloudId `ssafy.atlassian.net`, 프로젝트 키 `S15P21E205`
- 스토리 포인트 `customfield_10031`, 스프린트 `customfield_10020`
- 활성 스프린트 id는 적어두지 말고 `project = S15P21E205 AND sprint in openSprints()` 결과 이슈의
  스프린트 필드에서 읽는다.
- 상태 전이 id: 11 해야 할 일 / 21 진행 중 / 31 완료. 다르면 `getTransitionsForJiraIssue`로 확인한다.
- 이슈 유형 id: 10002 작업 / 10001 스토리 / 10000 에픽 — JQL에서 한글 이름은 안 먹으니 id를 쓴다.
- 완료 조회는 `statusCategory = Done`을 쓴다. 완료 status id가 10001이라 스토리 이슈 유형 id와
  숫자가 겹친다.

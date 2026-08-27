# CLAUDE.md — Team Quantum Jump

AI(Claude 등)와 협업할 때 이 문서를 기준으로 한다. 코드리뷰는 AI를 통해서만 진행하고,
발견된 문제는 해결한 후 Merge한다.

## 프로젝트 개요

퀀트 전략 백테스트 플랫폼. 사용자가 전략과 파라미터를 선택하면 과거 시장 데이터(KRX)로
백테스트를 실행하고 결과(수익률, MDD, Sharpe 등)를 보여준다.

- 현재 목표(MVP v0.1.1): KRX 데이터 + 알고리즘 1개(파라미터 고정) + 5년 기간 백테스트 DEMO
- 다음(v0.1.2): 알고리즘 20개+, 파라미터 전 조합 백테스트 → 분산 처리의 당위성 수치 측정

## 아키텍처 원칙

1. **Backend는 계산하지 않는다.** 백테스트는 Job으로 만들어 Kafka에 넣고, Worker가 처리한다.
2. Worker는 같은 코드(`worker/`)를 여러 개 실행해 수평 확장한다. (Kafka Consumer Group)
3. 백테스트 API는 비동기. `POST /backtests` → `{ backtestId, status: QUEUED }` 즉시 반환,
   상태는 `GET /backtests/{id}`로 조회. 상태 흐름: `QUEUED → RUNNING → COMPLETED | FAILED`.
4. **빅데이터 기술(Spark/Hadoop)을 먼저 선택하지 않는다.** 계산량을 실측한 뒤 병목이 확인되면
   도입한다. 단, 도입 가능성을 열어두기 위해 데이터 저장은 Parquet 친화적으로, 연산 로직은
   Worker 내부에 격리한다.

## 모노레포 구조

각 파트 폴더에 파트 전용 CLAUDE.md가 있다. **해당 폴더 작업 시 그 파일의 규칙과 검증 명령을 따른다.**


- `frontend/` React 19 + Vite + TypeScript — FRONT 파트
- `backend/` Spring Boot 3.5, Java 21, Gradle — BACK 파트
- `worker/` Python 3.11, pandas 기반 백테스트 워커 — Kafka consumer
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

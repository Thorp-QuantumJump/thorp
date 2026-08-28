# Git 컨벤션

> 모노레포(`frontend/` `backend/` `worker/` `data/` `ai/` `infra/`) 기준.
> Jenkinsfile이 **변경 경로(changeset)** 로 빌드 스테이지를 나누므로, 한 브랜치·한 커밋은 한 파트만 건드리는 것을 원칙으로 한다.

---

## 1. 브랜치 전략

| 브랜치 | 역할 | 직접 push |
|---|---|---|
| `main` | 배포 가능한 상태만 유지 | 금지 |
| `develop` | 통합 브랜치. 모든 작업 브랜치의 분기점 | 금지 |
| 작업 브랜치 | Jira 이슈 1개 = 브랜치 1개 | 허용 |

- 작업 브랜치는 `develop`에서 분기하고 `develop`으로 PR한다.
- 릴리즈 시점에만 `develop` → `main` PR.
- 머지된 작업 브랜치는 즉시 삭제한다.

## 2. 브랜치 이름

```
{type}({position})/{JIRA}-{desc}
```

- `type` — 아래 커밋 타입과 동일한 값 사용
- `position` — `fe` | `be` | `de` | `ai` | `infra`
- `JIRA` — Jira 이슈 키 (예: `S15P21E205-142`)
- `desc` — 영문 소문자 + 하이픈, 2~4단어

```
feat(be)/S15P21E205-142-backtest-job-api
feat(fe)/S15P21E205-155-strategy-form
fix(de)/S15P21E205-160-pykrx-null-volume
chore(infra)/S15P21E205-171-kafka-compose
```

### position ↔ 디렉토리 매핑

| position | 디렉토리 |
|---|---|
| `fe` | `frontend/` |
| `be` | `backend/` |
| `de` | `data/`, `worker/` |
| `ai` | `ai/` |
| `infra` | `infra/`, `Jenkinsfile`, `docker-compose.yml`, 루트 설정 |

## 3. 커밋 메시지

```
{type}({position}): {JIRA} {한글 요약}

{본문 - 선택, 왜 바꿨는지}
```

```
feat(be): S15P21E205-142 백테스트 Job 생성 API 추가

Job 생성 시 QUEUED로 저장하고 backtest.jobs 토픽으로 발행한다.
상태 전이는 QUEUED -> RUNNING -> COMPLETED / FAILED.
```

규칙

- 제목은 50자 이내, 마침표 없음, **명사형 또는 '~추가/수정/삭제'** 로 끝냄
- 본문은 72자에서 줄바꿈, 제목과 한 줄 띄움
- 한 커밋 = 한 가지 목적. 포맷팅과 로직 변경을 섞지 않는다
- 여러 파트를 동시에 건드려야 하면 커밋을 파트별로 쪼갠다

### 커밋 타입

| type | 사용처 |
|---|---|
| `feat` | 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변화 없는 구조 개선 |
| `perf` | 성능 개선 |
| `test` | 테스트 추가·수정 |
| `docs` | 문서, 주석 |
| `style` | 포맷·세미콜론 등 (로직 변화 없음) |
| `chore` | 빌드 설정, 의존성, 스크립트 |
| `ci` | Jenkinsfile, 파이프라인 |

## 4. Pull Request

- 제목: `{type}({position}): {JIRA} {한글 요약}` — 대표 커밋과 동일 형식
- 본문 템플릿

```markdown
## 작업 내용
-

## 변경 이유

## 확인 방법
- [ ] 로컬 빌드/테스트 통과
- [ ] 영향 범위 확인 (다른 파트 건드리지 않음)

## 관련 이슈
S15P21E205-000
```

- 리뷰 **1인 이상 approve** 후 머지
- 머지 방식: **Squash and merge** (changeset 기반 CI가 커밋 단위로 흔들리지 않게)
- WIP 상태면 Draft PR로 올린다

## 5. 금지 사항

- `main` / `develop` 직접 push, force push
- 시크릿·`.env`·개인 API 키 커밋 (`.gitignore` 먼저 확인)
- 대용량 데이터 파일 커밋 — Parquet, CSV 원본은 레포에 넣지 않는다
- 의미 없는 커밋 메시지 (`수정`, `ㅇㅇ`, `test`, `asdf`)
- 리뷰 없는 self-merge

## 6. 자주 쓰는 흐름

```bash
git switch develop
git pull origin develop
git switch -c feat\(be\)/S15P21E205-142-backtest-job-api

# 작업 후
git add backend/
git commit -m "feat(be): S15P21E205-142 백테스트 Job 생성 API 추가"
git push -u origin HEAD

# 머지 후 정리
git switch develop && git pull origin develop
git branch -d "feat(be)/S15P21E205-142-backtest-job-api"
```

> 브랜치명에 `()` 가 들어가므로 zsh/bash에서는 이스케이프하거나 따옴표로 감싼다.
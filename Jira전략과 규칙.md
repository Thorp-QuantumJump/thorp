# Jira 컨벤션 (S15P21E205)

> 원본: Notion `Jira Convention`, `Git Convention` (Team Quantum Jump). 이 문서는 레포용 요약본이며, 충돌 시 Notion 원본이 우선한다.

- 보드: https://ssafy.atlassian.net/jira/software/projects/S15P21E205
- 스프린트: 1주 단위, 매주 금요일 버전 릴리즈
- 코드 리뷰: AI 리뷰로 진행, 문제 해결 후 Merge. 매일 16:00 팀 MR 리뷰

---

## 1. 이슈 구조

논리 구조는 3depth지만 Jira에서 Story와 Task는 같은 계층이다.

```
Epic
├─ Story        (상위 항목 = Epic)
└─ Task         (상위 항목 = Epic)

Story ↔ Task : '연결된 업무 항목'에서 relates to로 연결
```

- **하위 작업(Sub-task)은 사용하지 않는다.**
- Story와 Task 모두 Epic을 상위 항목으로 지정한다.
- Task를 Story의 하위로 만들지 않는다.

## 2. 이슈 유형 요약

| 항목 | Epic | Story | Task |
|---|---|---|---|
| 역할 | 큰 기능 영역 | 사용자 기능·유스케이스 | 실제 개발 작업 |
| 제목 | 기능 영역 (명사형) | `[대상]은 (목적)하기 위하여 (기능)할 수 있다` | `[분야] 작업 내용` |
| Epic 연결 | — | **필수** | **필수** |
| Story 연결 | — | — | **필수** (relates to) |
| 담당자 | 선택 | 선택 | **필수** (1명) |
| 스토리 포인트 | 사용 안 함 | 사용 안 함 | **필수** |
| 스프린트 | 사용 안 함 | 선택 | **필수** |
| GitLab 이슈 | 생성 안 함 | 생성 안 함 | **자동 생성** |
| 브랜치 | 생성 안 함 | 생성 안 함 | **생성** |

- **1 Task = 1 Branch = 1 MR**
- Story의 스프린트는 관련 Task를 전부 그 스프린트에 끝낼 계획일 때만 지정한다.
- Task가 여러 Story에 걸치면 작업 범위가 큰 것은 아닌지 먼저 확인한다.

## 3. 제목 규칙

**Epic** — 기능 영역을 짧은 명사형으로.

```
O  백테스트
O  시장 분석
X  기능 개발 / 기타 기능        (범위 불분명)
```

**Story** — 구현 방법이 아니라 누가·왜·무엇을 할 수 있는지.

```
[대상]은 (목적)하기 위하여 (활동/작업/기능)할 수 있다

O  사용자는 전략을 비교하기 위하여 동일 기간에 여러 전략을 실행할 수 있다
X  백테스트 기능                (범위가 너무 넓음)
```

**Task** — `[분야] 작업 내용`. 분야는 아래 목록으로 통일한다.

```
[BE]  [FE]  [AI]  [DE]  [INFRA]  [DOCS]

O  [BE] 백테스트 요청 API 구현
O  [DE] pykrx 일봉 수집 배치 작성
X  [BE] 백테스트 기능           (분리 대상)
X  [INFRA] 배포 작업            (범위 불분명)
```

큰 Task는 쪼갠다.

```
분리 전   [BE] 백테스트 기능 구현              → 13 Point 예상
분리 후   [BE] 백테스트 요청 API 구현          → 5
          [BE] 백테스트 엔진 연동              → 8
          [BE] 백테스트 결과 저장 구현         → 3
```

## 4. 스토리 포인트

**Task에만 입력한다.** 절대 시간이 아니라 **담당자 본인 기준의 난이도·규모**로 산정한다.

| 포인트 | 기준 |
|---|---|
| 1 | 매우 단순하고 익숙한 작업. 범위와 방법이 명확 |
| 3 | 일반적인 개발 작업. 약간의 고려나 테스트 필요 |
| 5 | 비교적 복잡. 여러 로직 고려 또는 새 기술·충분한 테스트 필요 |
| 8 | 난이도 높거나 불확실성 큼. 설계·연동·검증을 함께 고려 |

- 사용 값은 `1 / 3 / 5 / 8` 네 개뿐이다. (2, 13 사용 안 함)
- **Task당 최대 8점.** 초과 예상이면 여러 Task로 분리한다.
- **개인별 주간 16점** 기준 (1주 스프린트).
- 고려 요소: 작업량, 구현 복잡도, 담당자 숙련도, 기술적 불확실성, 테스트 범위, 의존성

## 5. 상태 관리

```
해야 할 일 → 진행 중 → 완료
```

**진행 중으로 옮기기 전 체크**

- [ ] 담당자
- [ ] Epic 연결
- [ ] Story 연결 (relates to)
- [ ] 스프린트
- [ ] 스토리 포인트
- [ ] GitLab 이슈 생성 확인
- [ ] GitLab 브랜치 생성

**완료 조건**

- [ ] 기능 구현 완료
- [ ] 테스트 또는 동작 확인 완료
- [ ] 코드 리뷰 완료
- [ ] MR 병합 완료
- [ ] 필요한 문서 수정 완료

## 6. GitLab 이슈 자동화

```
Jira Task 생성 → GitLab Issue 자동 생성 → Jira Task와 연결
GitLab 이슈 제목: [Jira 이슈 키] Jira Task 제목
```

- 이슈 생성이 끝나면 Task에 `gitlab-created` 레이블이 자동으로 붙는다.
- **`gitlab-created`를 임의로 삭제·변경하지 않는다.** 삭제 후 자동화를 다시 실행하면 GitLab 이슈가 중복 생성된다.
- 자동화 이전에 만든 Task는 GitLab 이슈 존재 여부를 먼저 확인하고, 없을 때만 자동화 버튼을 실행한다.
- 레이블이 잘못 지워졌으면 자동화를 재실행하지 말고 기존 이슈를 확인한 뒤 레이블만 복구한다.

## 7. Git 연동

```
브랜치   {type}({position})/{JIRA_NUMBER}-{description}
커밋     {type}({position}): 작업 내역 요약

         커밋 설명 (optional)
```

| 구분 | 값 |
|---|---|
| type | `feat` `fix` `refactor` `chore` `docs` |
| position | `fe` `be` `de` `ai` `infra` |
| JIRA_NUMBER | 팀 키를 뺀 번호만. `12` (O) / `S15P21E205-12` (X) |

```
feat(be)/12-kakao-oauth
feat(be): 카카오 OAuth 로그인 구현
```

커밋으로 Jira 상태를 바꾸려면, 공백 등 변경 후 지라 티켓을 붙여 한 번 더 커밋한다.

```
feat(be): 작업 내역 요약/ S15P21E205-12
```

**MR 템플릿**

```markdown
# 작업 내용 요약

# 관련 이슈
- close #

# 주요 변경 사항
-

# 테스트
- [ ]

# 리뷰 포인트
-
```

## 8. 자주 쓰는 JQL

```jql
# 내가 이번 스프린트에 맡은 일
project = S15P21E205 AND assignee = currentUser() AND sprint IN openSprints() ORDER BY status

# 이번 스프린트 미완료
project = S15P21E205 AND sprint IN openSprints() AND status != 완료

# Epic 미연결 (정리 대상)
project = S15P21E205 AND issuetype IN (스토리, 작업) AND parent IS EMPTY

# 포인트 없는 Task (정리 대상)
project = S15P21E205 AND issuetype = 작업 AND "Story Points" IS EMPTY

# GitLab 이슈 미생성 Task
project = S15P21E205 AND issuetype = 작업 AND labels != "gitlab-created"
```
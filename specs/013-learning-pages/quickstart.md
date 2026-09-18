# Quickstart: 배우기 검증

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-09-16

구현이 spec을 만족하는지 재현하는 검증 가이드다. 계약·필드의 정의는 [contracts/](contracts/)와 [data-model.md](data-model.md)를 본다. 이 문서는 무엇을 실행하고 무엇이 나와야 하는지만 적는다.

B06 실행 결과·검증 환경·미실행 항목은 [validation.md](validation.md)에 따로 기록한다.

## 0. 준비

- Java 21, Node.js·npm, Docker(PostgreSQL Testcontainers와 로컬 DB)
- 로컬 DB `localhost:5432/thorp`와 014 로그인이 동작하는 상태
- 001의 열린 대응표. 없으면 예시 판정은 `SECTOR_MAP_NOT_READY`가 된다(§3-4에서 이 경우를 따로 확인한다)
- 관리 권한을 줄 로그인 계정 하나. 그 계정의 사용자 ID를 `THORP_LEARNING_ADMIN_USER_IDS`로 넘긴다([research](research.md) R5)

**로컬 프로필 주의**: `application-local.yml`은 Flyway를 끄고 Hibernate가 스키마를 만든다. 이 상태에서는 마이그레이션이 넣는 **아홉 글·열네 용어 자리가 생기지 않는다.** 초기 자리를 확인하려면 아래처럼 Flyway를 켠다.

```bash
SPRING_PROFILES_ACTIVE=local SPRING_FLYWAY_ENABLED=true SPRING_JPA_HIBERNATE_DDL_AUTO=validate SPRING_SESSION_JDBC_INITIALIZE_SCHEMA=never THORP_LEARNING_ADMIN_USER_IDS=1 ./gradlew bootRun
```

Flyway가 Spring Session 테이블도 만들므로 이 확인에서는 별도 세션 스키마 초기화를 끈다.
`backend/.env`의 메일 설정도 필요하다([backend/CLAUDE.md](../../backend/CLAUDE.md)). 메일을
보내지 않는 검증에는 더미 값을 쓴다. 아래의 게시·대응표 변경은 합성 자료 전용 로컬 DB에서 한다.

## 1. 자동 검증

### 백엔드

```bash
cd backend && ./gradlew spotlessApply build
```

결과의 `skipped` 수를 확인한다. Docker가 없으면 DB 테스트가 조용히 건너뛰어진다([backend/CLAUDE.md](../../backend/CLAUDE.md)). 건너뛴 테스트를 통과로 세지 않는다.

반드시 포함되어야 하는 검증:

| 무엇 | 확인 | 근거 |
|---|---|---|
| 마이그레이션 | 빈 DB 적용, 아홉 글·열네 용어가 `DRAFT`·빈 본문으로 생김, `status`·`published_revision_id` CHECK | [data-model](data-model.md) §3·§8 |
| `ArchitectureTest` | `learning` 계층 규칙 통과, `sector`가 `learning`을 참조하지 않음 | plan 구조 |
| 공개 조회 계약 | 응답이 `docs/specs/learning-api/examples/`의 픽스처와 같음 | [learning-public-api](contracts/learning-public-api.md) |
| 초안 격리 | 초안만 있는 글은 목록에 없고 단건은 404, 게시된 글을 고쳐 저장해도 공개 응답이 그대로 | SC-006 |
| 게시 전 검사 | 아홉 검사가 각각 걸리는 초안과 모두 통과하는 초안. 여러 검사가 한 번에 걸리면 전부 돌아옴 | R7, FR-017 |
| 게시 원자성 | 게시·게시 취소 뒤 판·이벤트·게시 용어 색인이 한 트랜잭션으로 맞음 | [data-model](data-model.md) §7 |
| 권한 | 비로그인 401, 권한 없는 로그인 404, CSRF 누락 403 | [learning-admin-api](contracts/learning-admin-api.md) §0 |
| 덮어쓰기 방지 | 옛 `expectedDraftVersion`으로 저장·게시하면 409 | R12 |

### 프런트엔드

```bash
cd frontend && npm run lint && npm run build && npm test
```

새 테스트 파일 경로가 `vite.config.ts`의 `test.include`에 걸리는지 확인한다. 걸리지 않으면 실패하지 않고 조용히 빠진다([frontend/CLAUDE.md](../../frontend/CLAUDE.md)).

## 2. 공개 조회 (비로그인)

아래 `curl`은 백엔드 경로를 직접 부른다. 브라우저에서는 `/api` 접두사가 붙는다.

### 2-1. 게시 전 빈 목록

```bash
curl -s localhost:8080/learning/articles
```

**기대**: `success: true`, `data.articles: []`. 오류가 아니다(spec Edge Cases).

### 2-2. 없는 글과 준비 안 된 용어

```bash
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/learning/articles/returns-and-losses
curl -s localhost:8080/learning/terms/max-drawdown
```

**기대**: 첫 줄 `404`, 둘째는 `error.code: LEARNING_TERM_NOT_READY`. 초안 자리가 있어도 공개되지 않는다.

## 3. 관리 흐름 (관리 계정 로그인)

브라우저에서 §0의 계정으로 로그인한 뒤 `/admin/learning`을 연다.

### 3-1. 권한

| 계정 | `/admin/learning` | 기대 |
|---|---|---|
| 비로그인 | 로그인 안내로 이동 | 401 처리(FR-016) |
| 허용 목록에 없는 로그인 계정 | 관리 화면이 그려지지 않음 | access 404. 일반 화면 어디에도 편집 수단이 없음(US5 시나리오 1) |
| 허용 목록의 계정 | 아홉 글 목록, 모두 초안 | |

### 3-2. 검사에 걸리는 게시 (US5 시나리오 3)

`returns-and-losses`를 열어 아래처럼 저장한 뒤 게시한다.

먼저 제목·질문·설명 문단·예시 두 섹터를 채우고, 이 글의 두 용어(`max-drawdown`·
`net-total-return`)에 이름·문장을 넣어 각각 본문의 `term` 블록에 한 번씩 연결한다.
이 준비 없이 초기 초안을 바로 게시하면 아래 세 항목 외에 필수 항목 검사도 걸린다.

- 예시 섹터 두 개, 비중 50.0%·45.0%
- `paragraph` 블록에 `추천 비중` 포함
- 용어 `max-drawdown` 문장에 `-30%` 포함

**기대**: 게시되지 않는다. 화면에 세 항목(`EXAMPLE_WEIGHT_SUM`·`FORBIDDEN_EXPRESSION`·`RESULT_NUMBER_OUTSIDE_HYPOTHETICAL`)이 각각의 위치와 함께 보인다. 공개 조회는 §2-2 결과 그대로다.

### 3-3. 통과하는 게시 (US5 시나리오 2·5)

세 문제를 고친다. 비중은 50.0%·50.0%로 맞추고, 숫자는 `hypothetical` 블록으로 옮기고, 금지 표현은 지운다. `max-drawdown`·`net-total-return` 용어가 본문에 `term` 블록으로 한 번씩 들어가게 한 뒤 게시한다.

**기대**:
- 게시 성공, `revisionNo: 1`
- 로그아웃한 탭에서 `/learn`에 글이 보이고, `/learn/returns-and-losses`의 용어 블록 문장이 `GET /learning/terms/max-drawdown`의 `sentence`와 같다(SC-003)
- 관리 목록에 마지막 게시 기록과 게시한 사용자 ID가 보인다(FR-018)

### 3-4. 대응표가 준비되지 않았을 때

001 대응표가 없는 DB에서 §3-3의 게시를 시도한다.

**기대**: `EXAMPLE_SECTOR_IN_MAP`으로 거절된다. 판정할 수 없는 것을 통과로 세지 않는다(원칙 VI).

### 3-5. 게시된 글을 고치는 동안 (SC-006)

게시된 글의 문단 하나를 고쳐 **저장만** 한다.

**기대**: 공개 조회 응답이 바뀌지 않는다. 관리 목록에 `hasUnpublishedChanges: true`가 보인다.

### 3-6. 덮어쓰기 방지 (R12)

같은 글을 두 탭에서 연다. 한 탭에서 저장한 뒤 다른 탭에서 저장한다.

**기대**: 두 번째 탭이 `LEARNING_DRAFT_CHANGED`를 받고, 입력한 내용이 화면에 남은 채 새로 불러오라는 안내가 보인다.

### 3-7. 편집 중 만료 (spec Edge Cases)

편집 중에 서버 세션을 끝낸다(다른 탭에서 로그아웃). 그 뒤 저장을 누른다.

**기대**: 재로그인 안내가 보인다. 재로그인 뒤 같은 글을 열면 저장하지 못한 내용을 복원할지 묻는다.

### 3-8. 게시 취소 (US5 시나리오 6)

`returns-and-losses`를 게시 취소한다.

**기대**:
- `/learn` 목록에서 사라진다
- `/learn/returns-and-losses` 주소로 들어가면 오류 화면이 아니라 "지금은 볼 수 없는 글" 한 문장과 목록 연결이 보인다
- `GET /learning/terms/max-drawdown`이 `LEARNING_TERM_NOT_READY`다
- 관리 목록의 마지막 기록이 `UNPUBLISH`다

게시 취소를 한 번 더 누른다. **기대**: 200이고 기록이 늘지 않는다.

## 4. 배우기 화면 (비로그인, §3-3 게시 상태)

### 4-1. 목록과 글 (US1)

`/learn`을 연다.

**기대**:
- 설문·숙련도 질문 없이 목록이 바로 보인다(시나리오 5)
- 글을 열면 질문 하나와 설명이 보이고, `hypothetical` 블록에 '가상의 계산 예' 표시가 있다(시나리오 6)
- 예시 비중이 투자 권고가 아니라는 표시가 있다(시나리오 4)
- `sessionStorage`·`localStorage`에 배우기 열람 기록이 생기지 않는다(FR-012)

### 4-2. 불러오지 못함 (US1 시나리오 7, SC-007)

개발자 도구에서 `/api/learning/*` 요청을 막고 `/learn`을 새로 고친다.

**기대**: 빈 목록이 아니라 불러오지 못했다는 한 문장과 다시 시도 버튼이 보인다. 진단 진입 수단은 그대로 있다. 요청 막기를 풀기 전에는 자동으로 다시 요청하지 않는다.

### 4-3. 예시로 진단 시작 (US2)

글에서 '이 예시로 비중 비교하기'를 누른다.

| 먼저 해 둔 것 | 기대 | 근거 |
|---|---|---|
| 아무것도 입력하지 않음 | 섹터·비중 화면에 예시 50.0%·50.0%가 채워지고 예시 표시가 있다 | 002 FR-012 |
| 002에서 다른 섹터를 이미 고름 | 덮어쓸지 먼저 묻는다. 거절하면 기존 값이 남는다 | 002 FR-012 |

### 4-4. 대응표에서 사라진 예시 섹터 (US2 시나리오 3)

게시 뒤 열린 대응표에서 예시 섹터 하나를 뺀 판을 연다.

**기대**: 글은 보이고 연결 버튼 대신 한 문장 안내와 빈 섹터 선택 화면 연결이 보인다. 관리 목록에 그 글이 '갱신 필요'로 보인다.

### 4-5. 키보드와 스크린 리더 (FR-019, SC-008)

마우스를 쓰지 않고 Tab·Enter·Esc만으로 확인한다.

**기대**: 글 이동, '이 예시로 비중 비교하기', 다시 시도에 모두 닿는다. 스크린 리더(NVDA 또는 VoiceOver)가 본문·용어 문장·안내 문장을 읽는다.

## 5. 진단 화면 도움말 (US3)

003의 `DetailSettingsPanel`은 등록표 `axes[].termId`가 있는 축에 공용 `TermHelp`를 붙인다
(T097 구현). `/strategy`의 실제 도움말을 확인한다. 003에 없는 용어는 공용 컴포넌트 테스트로
확인하고, 그 결과를 화면 종단 확인과 구분해 기록한다.

### 5-1. 세 상태 (FR-010)

| 준비 | 도움말을 열면 |
|---|---|
| `weights-and-rebalancing`을 게시한 뒤 `rebalancing` 도움말 | 이름·문장·글 연결 |
| 같은 글을 게시 취소한 뒤 `rebalancing` 도움말 | '설명 준비 중', 연결 없음 |
| `/api/learning/terms/*` 요청을 막음 | 불러오지 못했다는 문장과 다시 시도. 진단 입력·'분석하기'는 그대로 쓸 수 있다 |

### 5-2. 같은 탭 이동과 돌아가기 (FR-009)

요청 차단을 풀고 `weights-and-rebalancing`을 다시 게시한다. `/strategy`에서 전략을 고르고
설정 하나를 바꾼 뒤, 도움말의 글 연결을 누른다.

**기대**:
- 같은 탭에서 글이 열리고 '하던 진단으로 돌아가기'가 보인다
- 누르면 `/strategy`로 돌아오고 고른 전략과 바꾼 설정이 남아 있다
- 같은 글을 배우기 메뉴에서 직접 열면 돌아가기 연결이 없다

### 5-3. 제목을 바꿔도 끊기지 않음 (US3 시나리오 7)

관리 화면에서 `weights-and-rebalancing`의 제목과 `rebalancing`의 문장을 고쳐 게시한다.
§5-1에서 게시 취소했으면 다시 게시한 상태로 확인한다. 이 글의 나머지 용어도 본문에 한 번씩
연결되어 있어야 한다. `max-drawdown`·`returns-and-losses`의 동일 식별자 재게시는 서버 계약
테스트에서도 확인한다.

**기대**: 도움말의 글 연결이 같은 주소로 열리고 새 문장이 보인다.

### 5-4. 키보드 (US3 시나리오 8)

**기대**: Tab으로 도움말 버튼에 닿고, Enter로 열리고, Esc로 닫히며, 닫으면 초점이 버튼으로 돌아온다.

## 6. 이 문서로 확인하지 않는 것

- **SC-002의 충분성.** 금지 표현·결과성 숫자 검사는 패턴 검사다. 검사를 통과했다는 사실이 권고 표현 0건을 증명하지 않는다([research](research.md) R7).
- **SC-005 사용성 관찰.** PD-14가 정하는 관찰 일정에서 기록한다.
- **002·005 화면의 도움말 자리.** 각 spec이 정한 뒤 그쪽 검증에서 확인한다([term-help-ui](contracts/term-help-ui.md) §5).

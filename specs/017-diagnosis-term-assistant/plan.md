# Implementation Plan: 진단 화면 용어 도우미

**Branch**: `017-diagnosis-term-assistant` | **Date**: 2026-10-02 | **Spec**: [spec.md](spec.md)

**Input**: `specs/017-diagnosis-term-assistant/spec.md`의 진단 화면 용어 질문 요구사항

## Summary

015 `assistant` 도메인에 화면 종류 `SECTOR_SELECTION`·`STRATEGY_SETTINGS`를 더한다. 질문 흐름은 종류에 따라 두 갈래로 나뉜다.

- **결과 화면**: 토큰 판정 → 결과 근거. 015 그대로다.
- **진단 화면**: 토큰 없음 확인 → 사이트 콘텐츠 근거.

진단 화면의 근거와 도구는 다음과 같다.

- **처음 팩**: 013의 게시된 용어 설명 전부, 그리고 화면별 짧은 설명(전략 설명, 또는 섹터 목록).
- **도구 셋**: `readArticle`·`readStrategy`·`readSectorList`. 인자는 요청 시점의 닫힌 열거값이다.

답 검증은 화면 종류별 정책을 쓴다. 진단 화면은 인용한 근거 원문에 같은 표기로 있는 숫자만 허용한다. 프롬프트는 종류별 파일로 고른다. 응답에는 글 연결용 `relatedArticles`를 더한다.

프론트는 두 화면에서 `useAssistantPageContext({kind})`를 등록하고, 종류별 문구와 글 연결을 그린다. 플래그는 종류마다 따로 켠다(`THORP_ASSISTANT_ENABLED_SCOPES`).

## Technical Context

**Language/Version**: Frontend TypeScript 5.8·React 19.1, Backend Java 21·Spring Boot 3.5.4 (015와 같다)

**Primary Dependencies**: 새 의존은 없다. 015 도우미 부품, 013 `LearningQueryService`, 003 `ParameterRegistryPort`, 001 `SectorService`를 쓴다.

**Storage**: 없다. 새 테이블·마이그레이션은 없다. 013에는 조회 메서드만 더한다(R8). 질문·답 원문은 저장하지 않는다.

**Testing**:
- Backend: JUnit 5·MockMvc. 가짜 모델 포트를 쓰고, 013 게시 콘텐츠를 심은 `PortMockedIntegrationTestSupport`(Testcontainers) IT를 둔다.
- Frontend: Vitest·RTL.
- 실제 모델 호출은 평가 묶음에서만 한다(`ASSISTANT_LIVE_TEST`).

**Target Platform**: 기존 SPA + Spring 서버. 외부 호출은 015와 같은 GMS 하나다.

**Project Type**: React SPA + Spring Boot web API

**Performance Goals**: 015와 같다. 요청당 2분 상한이고, 라운드는 2회까지다. 처음 팩 조회는 게시 용어 목록 1회와 등록표 또는 섹터 목록 1회다(R10).

**Constraints**:
- 015 계약 §3의 "넘기지 않는 것"에 사용자 선택값·브라우저 저장소를 더한다.
- 서버는 콘텐츠를 요약·계산하지 않는다. 필드를 골라 옮기기만 한다.
- `RESULT`의 동작·응답·프롬프트는 바꾸지 않는다. 015 테스트는 그대로 통과해야 한다.

**Scale/Scope**:
- 엔드포인트: 기존 2개(새 경로 없음)
- 화면 종류: 2개 추가
- 도구: 3개 추가
- 프롬프트: 1개 추가
- 프론트 등록 화면: 2개

## Constitution Check

*GATE: 설계 시점에 통과. 구현 뒤 재검사.*

| 관문 | 판정 | 설계 반영 |
|---|---|---|
| I. 전략 진단 중심 | PASS | 진단 흐름을 멈추지 않게 돕는 보조 기능이다. 002·003의 입력 흐름과 주 버튼을 바꾸지 않는다. |
| II. 첫 화면 단순성 | PASS | 015와 같은 여백 버튼이고, 말풍선은 요청할 때만 연다. 화면 요소는 버튼 하나만 는다. |
| III. 진단이지 추천이 아니다 | PASS | 고르기·성과 우열·최적값·미래 판단은 `OUT_OF_SCOPE`로 처리하고 고정 문장으로 답한다. 프롬프트·금지 표현·표본 세 겹으로 막는다. 기본값을 "추천값"으로 설명하지 않는다(등록표 설명을 인용할 뿐이다). |
| VI. 없는 결과를 만들지 않음 | PASS | 근거는 게시 콘텐츠·등록표·섹터 목록뿐이다. 근거가 없으면 고정 문장으로 답한다. 숫자는 인용 근거 원문에 있는 것만 쓴다(R3). 초안 콘텐츠는 근거에 넣지 않는다(R8). |
| VIII. 재현할 수 있게 기록 | PASS | 평가 문서에 등록표 버전·게시 콘텐츠 상태·모델명을 함께 적는다(SC-005). |
| IX. 한도는 실측으로 | PASS | 한도는 설정값이다. 화면 종류별 IP 키를 새로 두고, 하루 전체는 하나로 공유한다(R5). |
| X. 자료는 이용 조건 안에서 | PASS | 근거는 우리가 작성한 콘텐츠와 001 섹터 목록(이미 공개 화면에 보이는 이름)이다. 상품 상세의 외부 출처 문단은 넣지 않는다(R9). |
| 아키텍처 원칙 1 (Backend는 계산하지 않는다) | PASS | 근거는 원문 필드를 골라 옮긴 것이다. 요약·합성·수치 계산이 없다. |
| 013 단일 원본 (013 FR-008) | PASS | 용어 설명은 013 게시 색인에서만 읽는다. 도우미가 풀어 쓴 답은 도움말 문장을 대체하지 않는 다른 표면이다. 근거로는 원문을 가리킨다. |
| 작업 묶음 | PASS | 5개 묶음(DOCS 1·BE 3·FE 1)이고, 각각 따로 검증하고 되돌릴 수 있다. |

위반이 없다. Complexity Tracking은 해당 없다.

## Project Structure

### Documentation (this feature)

```text
specs/017-diagnosis-term-assistant/
├── spec.md
├── plan.md              # 이 파일
├── research.md          # R1~R11 설계 판단
├── data-model.md        # 타입 변경·근거 모양
├── quickstart.md        # 검증 절차
├── contracts/
│   └── term-assistant-api.md   # 015 계약에 대한 차이(원본)
└── checklists/requirements.md
docs/specs/assistant-api/
├── README.md            # 017 종류·relatedArticles 요약 추가
├── examples/term-*.json # 새 예시(IT 대조 기준)
└── evaluation-017.md    # 평가 묶음이 만든다
```

### Source Code (repository root)

```text
backend/src/main/java/com/ssafy/thorp/assistant/
├── application/result/  # PageKind(+2), PageContext, Evidence(+source), EvidenceSource, ToolSpec(인자 종류), Conversation(+kind), AssistantAnswer(+relatedArticles)
├── application/service/ # AssistantQuestionService(종류별 갈래), AnswerValidator(정책 분기), AssistantRateLimiter(scopeKey), ToolLoop(열거 인자 검증)
├── application/service/term/   # TermEvidenceBuilder, ReadArticleTool, ReadStrategyTool, ReadSectorListTool
├── infrastructure/client/      # OpenAiChatCompletionsClient(종류별 프롬프트·enum 인자)
└── presentation/response/      # relatedArticles, evidence.source
backend/src/main/resources/assistant/prompts/term-ko.txt
backend/src/main/java/com/ssafy/thorp/learning/application/{repository,service}/   # findAllPublished, listPublishedTerms
backend/src/test/java/com/ssafy/thorp/ArchitectureTest.java                         # 규칙 12에 learning 추가
backend/src/test/resources/assistant/term-quality-cases.json

frontend/src/types/assistant.ts                 # 종류 union, relatedArticles
frontend/src/components/assistant/assistantCopy.ts   # 종류별 머리글·안내·예시·입력 안내
frontend/src/components/assistant/AssistantBubble.tsx, AssistantFab.tsx, assistantPageContext.ts
frontend/src/pages/SectorSelectionPage.tsx, StrategySettingsPage.tsx   # 맥락 등록 한 줄씩
```

**Structure Decision**: ADR-0001의 4계층을 그대로 따른다. 새 의존 방향은 `assistant → learning`(`LearningQueryService`) 하나다. `assistant → strategy`·`assistant → sector`는 015에 이미 있다. ArchUnit 규칙 12의 "`assistant`를 참조하지 않는다" 목록에 `learning`을 더한다. 진단 종류의 빌더와 도구는 `application/service/term/`에 모아, 015의 결과 부품(`ResultEvidenceBuilder`·`tools/`)과 섞지 않는다.

## Design Decisions

### 질문 흐름의 두 갈래 (spec FR-001·FR-006·FR-011·FR-012)

- 공통 앞단은 015 순서 그대로다: 플래그(404) → 종류 판정(400) → 종류 켜짐(404) → 질문 길이(400).
- **`RESULT`**: 015 순서 그대로다. 토큰 필수 → 한도(IP·토큰·하루) → 데드라인 → `publicResults.resolve` → `ResultEvidenceBuilder`.
- **진단 두 종류**:
  1. `context`에 `kind` 밖의 키가 있으면 400이다. `publicToken`도 여기에 든다.
  2. 한도(IP·`page:{kind}:{ip}`·하루)
  3. 데드라인
  4. `TermEvidenceBuilder`
- 종류별 차이를 `if (kind == RESULT)` 분기로 서비스에 흩지 않는다. `EvidenceBuilder`에 `prepare(command): PageContext`(검증·한도 키)를 더해 종류별로 둔다. 서비스는 순서만 소유한다.
- 부분 결과 접두 문장은 `bundle.partialUntil()`이 있을 때만 붙는다. 진단 종류는 항상 null이라 그대로 둔다.

### 근거 팩과 도구 (spec FR-003·FR-004·FR-005, research R1·R2·R8·R9·R10)

- **처음 팩**:
  - **두 종류 공통**: `learning.listPublishedTerms()`의 용어마다 근거 1개를 싣는다. 라벨은 `용어: {label}`, 값은 `sentence`, `source=TERM(articleId, articleTitle)`이다.
  - **`STRATEGY_SETTINGS`**: `registry.activeStrategies()`의 전략마다 근거 1개를 싣는다. 라벨은 `전략: {name}`, 값은 `description`, `source=REGISTRY`이다.
  - **`SECTOR_SELECTION`**: 섹터 목록 근거 1개를 싣는다. 값은 `{이름}({산업/테마}, 대표 ETF {ETF 이름}, 기초 지수 {지수 이름})`을 `; `로 이은 것이고, `source=SECTOR`다.
  - 처음 팩 상한은 40이다. 넘으면 화면에 가까운 용어부터 싣는다. 넘친 용어는 "일부 용어 설명은 싣지 않음" 근거로 남긴다.
- **도구** (요청 시점에 열거 집합을 만든다):

  | 도구 | 인자 | 열거 집합 | 반환 근거 |
  |---|---|---|---|
  | `readArticle` | `articleId` | 게시된 글(`listPublished`) | 제목·질문·소제목·문단·용어 블록마다 근거 1개. `hypothetical`·`example`·`sources`는 뺀다 |
  | `readStrategy` | `strategyId` | 활성 전략 | 전략 설명 1개, 설정 항목마다 `{항목 이름}` = description(+선택지 이름 목록) 1개, 규칙 `detail`마다 1개 |
  | `readSectorList` | 없음 | — | 섹터 목록 근거 1개(처음 팩에 이미 있으면 "이미 읽음"으로 돌려준다) |

- 도구 설명에 `id=이름` 대응을 적어, 모델이 이름으로 묻는 질문을 열거값으로 옮길 수 있게 한다. 대응은 요청 시점 목록에서 만든다.
- 글이 요청 사이에 게시 취소되면 `getPublished`가 실패한다. 이때는 그 호출을 "사용할 수 없음: 게시되지 않은 글"로 돌려준다. 이전 판으로 대체하지 않는다.

### 답 검증 (spec FR-007·FR-008, research R3·R4)

- `AnswerValidator.render(draft, facts, policy)`로 바꾼다. `policy`는 종류에서 정해지고, 셋을 정한다.
  - **숫자 규칙**: `RESULT`는 015와 같이 엄격하고, 용어 종류는 인용 원문 대조(R3)다.
  - **자리표시자 규칙**: `RESULT`는 집합이 같아야 하고, 용어 종류는 부분집합이면 된다(R4).
  - **고정 문장 셋**: 아래와 같다.
- 용어 종류의 고정 문장(spec FR-008, 여기서 고정한다):
  - `INSUFFICIENT_EVIDENCE`: "이 사이트 자료로는 확인할 수 없어요."
  - `OUT_OF_SCOPE`: "이 도우미는 이 사이트에 나오는 용어와 설정의 뜻을 설명해요. 무엇을 고를지는 정해 드리지 않고, 백테스트 결과는 결과 화면에서 물어볼 수 있어요."
  - `GREETING`: "안녕하세요. 이 화면에 나오는 용어를 물어보세요. 예: 리밸런싱이 뭐야?"
- 길이 600자·part 5개·금지 표현은 015와 같은 설정·파일을 쓴다. 금지 표현에 "추천드", "고르세요", "가장 좋은", "최적" 같은 고르기 권유 어구를 더할지는 표본에서 정한다(묶음 5).

### 모델 연결 (research R6)

- `Conversation`에 `PageKind`를 싣는다. 어댑터는 종류별 프롬프트 파일을 생성 시점에 모두 읽어 두고 고른다.
- 도구 정의:
  - 열거 인자는 `{type:string, enum:[...]}`이다.
  - 인자가 없는 도구는 `properties:{}`·`required:[]`다.
  - 둘 다 strict다.
- 응답 스키마·`store:false`·`parallel_tool_calls:false`는 015와 같다.

### 한도 (research R5)

- `AssistantRateLimiter.admit(ip, scopeKey, scopeLimit)`로 바꾼다. 015 호출부는 `("token:"+token, perTokenPerMinute)`를 넘긴다.
- 새 설정은 `thorp.assistant.limits.per-page-per-ip-per-minute`(초안 5)이고, 환경 변수는 없다(설정 파일 기본값).

### 진입 UI와 글 연결 (spec FR-001·FR-002·FR-010)

- 타입:
  - `AssistantPageKind = 'RESULT' | 'SECTOR_SELECTION' | 'STRATEGY_SETTINGS'`
  - `AssistantContext`는 판별 union이다. `RESULT`만 `publicToken`을 갖는다.
  - 대화 초기화 키는 `kind + (publicToken ?? '')`다.
- 문구는 `assistantCopy.ts`의 종류별 표 하나에 둔다. 머리글, 첫 전송 전 안내, 예시 질문 2~3개, 입력 안내다. 결과 화면 문구는 지금 값을 그대로 옮긴다. 진단 화면의 예시 질문은 등록표·섹터 목록만으로 답할 수 있는 것만 쓴다(spec FR-002, SC-007). 섹터 이름이 들어가는 예시는 현재 대응표에 있는 섹터로 고르고, 묶음 5 표본에 그대로 넣는다.
- `relatedArticles`가 있으면 답 아래에 `배우기: {title}` 연결을 보인다. 이동은 `/learn/{articleId}`, `state.returnTo`다. 이 규칙은 `TermHelp`와 같고, 013의 '하던 진단으로 돌아가기'를 그대로 쓴다.
- 등록:
  - `SectorSelectionPage`: `useAssistantPageContext({kind:'SECTOR_SELECTION'})`
  - `StrategySettingsPage`: `useAssistantPageContext({kind:'STRATEGY_SETTINGS'})`
- 버튼 배치 확인은 R11을 따른다.

## Work Partition for Task Generation

`$speckit-tasks`가 아래 묶음을 실행 task로 나눈다. 묶음 하나가 브랜치·MR 하나다.

1. **[DOCS] spec·plan·계약**: 이 문서들과 `docs/specs/assistant-api/README.md`·예시 JSON. 브랜치는 `docs(be)/017-assistant-term-spec`.
2. **[BE] 도우미 화면 종류 확장 기반 (모델 없이)**
   - `PageKind` 두 값, 종류별 `prepare`, 한도 `scopeKey`
   - `ToolSpec` 열거·무인자, `ToolLoop` 인자 검증
   - `Evidence.source`, 응답 `relatedArticles`, `AnswerValidator` 정책 분기와 용어 고정 문장
   - `Conversation.kind`와 어댑터의 종류별 프롬프트 선택(프롬프트 파일은 빈 틀)
   - 015 테스트 전부 회귀 통과
3. **[BE] 용어 근거 팩과 읽기 도구 셋**
   - 013 `findAllPublished`·`listPublishedTerms`
   - `TermEvidenceBuilder`, 도구 셋, `term-ko.txt`
   - ArchUnit 규칙 12 갱신
   - 게시 콘텐츠를 심은 IT(게시 취소 반영, 초안 제외, 열거 밖 인자 거절)
4. **[FE] 진단 화면 도우미 연결**
   - 타입 union, `assistantCopy.ts`, 두 화면 등록
   - `relatedArticles` 연결, 화면 이동 시 이력 비움
   - 좁은 화면 버튼 배치 확인
5. **[BE] 용어 도우미 품질 표본·평가 문서**
   - 화면 종류별 표본 30개(`term-quality-cases.json`), 자동 검사, `evaluation-017.md`
   - '전략 선택' 관문 판정(등록표 기반)

선후 관계는 다음과 같다.
- 2와 4는 1(계약)만 있으면 병렬로 할 수 있다. 4는 가짜 응답으로 먼저 개발한다.
- 3은 2 위에서 한다. 5는 3 뒤에 한다.
- `STRATEGY_SETTINGS`는 5의 관문을 넘긴 뒤 켠다. `SECTOR_SELECTION`은 013 게시 뒤 5를 다시 돌려 관문을 넘긴 뒤 켠다(spec FR-014).

## 후속(이번 범위 밖)

- 결과 화면에서 용어 질문 받기(015 범위 변경)
- 배우기·시장 흐름 화면의 도우미
- 많이 물린 용어 집계(원문을 저장하지 않으므로 별도 설계가 필요하다)
- 등록표 설명에 범위·기본값 문장 보강(평가 결과를 보고 003 소유자가 판단한다)

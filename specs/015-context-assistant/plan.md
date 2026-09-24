# Implementation Plan: 화면 맥락 도우미

**Branch**: `015-context-assistant` | **Date**: 2026-09-24 | **Spec**: [spec.md](spec.md)

**Input**: `specs/015-context-assistant/spec.md`의 결과 화면 질문 도우미 요구사항

## Summary

별도 AI 서버 없이 Spring 백엔드의 새 `assistant` 도메인이 서버 키로 GMS 게이트웨이(`api.openai.com` 중계, `gpt-5-mini`)를 부른다. 흐름은 토큰 판정 → 결과 상세 응답 전부를 근거 팩으로 → 모델 호출(질문·팩·서버 실행 도구 5개 목록) → 모델이 적은 `tool_calls`를 서버가 검증·실행해 근거 추가(최대 2라운드) → 최종 답변의 자리표시자 검증·치환이다. 프론트는 `App` 공통 위치의 로봇 아이콘 플로팅 버튼과 말풍선이 질문과 현재 페이지 식별자만 보낸다. 기능은 플래그 `RESULT` 하나로 열고 닫는다.

## Technical Context

**Language/Version**: Frontend TypeScript 5.8·React 19.1, Backend Java 21·Spring Boot 3.5.4

**Primary Dependencies**: 기존만 쓴다 — Frontend `radix-ui` Popover·`lucide-react`·Axios; Backend Spring MVC·Validation, Java `HttpClient`(`market/infrastructure/client/MarketHttpClient` 관례), Jackson. 새 SDK·벡터 DB·채팅 저장소는 없다.

**Storage**: 없다. 질문·답변 원문을 저장하지 않는다. 한도 카운터는 인스턴스 메모리다(다중 인스턴스 전까지). 버튼 위치만 브라우저 `localStorage` `thorp.assistant.fabPosition`.

**Testing**: Backend JUnit 5·MockMvc·`PortMockedIntegrationTestSupport`(PostgreSQL Testcontainers), 모델은 가짜 포트와 로컬 가짜 HTTP 서버. Frontend Vitest·React Testing Library. 실제 모델 호출은 Task 6 평가 문서에만.

**Target Platform**: 기존 SPA + Spring 서버. 외부 호출은 `THORP_ASSISTANT_BASE_URL`(GMS) 하나.

**Project Type**: React SPA + Spring Boot web API

**Performance Goals**: 요청당 서버 15초 상한(도구 라운드 포함). 초기 팩은 상세 응답 한 번으로 만들어 추가 조회가 없고, 도구 라운드마다 하위 자원 조회 1회다.

**Constraints**: `store:false`, `parallel_tool_calls:false`, `tools`에는 서버 실행 도구 5개만, `response_format` strict JSON Schema. 모델에 토큰·`backtestId` 미전달. 서버는 계산하지 않는다(원칙 1) — 응답 값 그대로와 그 안에서 고른 것만. prod는 `${ENV}` 기본값 없음, 키 없으면 비활성.

**Scale/Scope**: 엔드포인트 2개(`GET /assistant/capabilities`, `POST /assistant/questions`), 도구 5개, 화면 부품 2개(버튼·말풍선), 결과 화면 한 곳.

## Constitution Check

*GATE: 설계 시점에 통과. 구현 뒤 재검사.*

| Gate | 판정 | 설계 반영 |
|---|---|---|
| I. 전략 진단 중심 | PASS | 결과 화면의 이해를 돕는 보조 기능이다. 진단 흐름·주 버튼을 바꾸지 않는다. |
| II. 첫 화면 단순성 | PASS | 버튼은 여백의 보조 요소, 말풍선은 요청할 때만 열린다. 결과 화면 요소를 늘리지 않는다. |
| III. 진단이지 추천이 아니다 | PASS | FR-011 금지 표현, 원인 질문에는 관측 사실만. 프롬프트·검증·평가 표본 세 겹. |
| VI. 없는 결과를 만들지 않음 | PASS | 답의 숫자는 서버 치환 값만(FR-009). 근거 없으면 그렇게 말한다. 기간 밖 날짜에 값을 만들지 않는다. |
| IX. 한도는 실측으로 | PASS | 한도는 설정값이고 초안 숫자는 실측 후 조정. 하루 전체 상한은 무분별 사용 방지용 안전핀. |
| 아키텍처 원칙 1 (Backend는 계산하지 않는다) | PASS | 근거는 응답 값과 그 안에서 고른 것. 합계·비율·구간을 새로 만들지 않는다. |
| 005 SC-009 (`backtestId` 비노출) | PASS | 모델·응답·로그 어디에도 없다. 도구는 서버가 결과에 묶어 식별자 인자가 없다. |
| Git·Jira 작업 묶음 | PASS | 6개 묶음(DOCS 1·BE 4·FE 1), 각각 독립 검증·롤백 가능. |

위반 없음. Complexity Tracking 해당 없음.

## Project Structure

### Documentation (this feature)

```text
specs/015-context-assistant/
├── spec.md
├── plan.md              # 이 파일
├── tasks.md
└── contracts/
    └── assistant-api.md # HTTP 계약·모델 스키마·도구 정의 원본
docs/specs/assistant-api/
├── README.md            # 계약 요약(다른 docs/specs/*와 같은 역할)
├── evaluation.md        # Task 6가 만든다
└── examples/*.json      # Task 2 IT가 대조
docs/adr/0008-external-llm-via-gms.md
```

### Source Code (repository root)

```text
backend/src/main/java/com/ssafy/thorp/assistant/
├── presentation/{controller,request,response}/
├── application/
│   ├── service/         # AssistantQuestionService, ToolLoop, AnswerValidator, ResultEvidenceBuilder
│   ├── service/tools/   # EquityOnTool, EquityRangeTool, TradesBetweenTool, PositionsOnTool, DeepestDrawdownWindowTool
│   ├── port/            # AssistantModelPort, AssistantTool, EvidenceBuilder
│   ├── result/          # Evidence, EvidenceBundle, ToolSpec, ToolCall, ModelDraft, AssistantAnswer
│   └── exception/
└── infrastructure/
    ├── client/          # OpenAiChatCompletionsClient, AssistantHttpClient
    └── config/          # AssistantProperties, AssistantModelProperties
backend/src/main/resources/assistant/prompts/answer-ko.txt
backend/src/test/java/com/ssafy/thorp/assistant/**
backend/src/test/resources/assistant/quality-cases.json

frontend/src/components/assistant/
├── AssistantFab.tsx, AssistantBubble.tsx, assistant.module.css
├── assistantPageContext.tsx, fabPosition.ts
└── *.test.tsx
frontend/src/api/assistant.ts, frontend/src/types/assistant.ts
frontend/src/App.tsx (버튼·말풍선 마운트), frontend/src/pages/ResultPage.tsx (페이지 맥락 등록)
```

**Structure Decision**: 백엔드는 ADR-0001의 도메인별 4계층을 그대로 따른다(`domain` 계층은 값 타입이 `result`에 있어 두지 않는다 — 005와 같은 예외). `assistant → result` 의존 하나만 새로 생긴다. 프론트는 `components/assistant/` 한 폴더와 `App` 마운트다.

## Design Decisions

### 근거 팩과 도구 (spec FR-006·FR-007)

- 초기 팩은 `PublicResultService.getDetail` 한 번으로 만든다. 상세 응답은 몇 KB라 전부 넣는 비용이 없다.
- 시계열·거래·보유는 크거나 질문에 따라 달라 도구로 읽는다. 도구는 `AssistantTool` 포트(`spec()`·`run(args)`)이고 요청 시작 때 풀린 결과에 묶여 만들어진다 — 함수 시그니처에 식별자 인자가 없어 프롬프트 인젹션으로 다른 결과를 읽을 경로가 없다.
- 도구는 시계열에서 **고르기만** 한다(첫·끝·최고·최저·가장 낮은 날). 이는 005 화면의 `drawdownWindow.ts`가 하는 일과 같은 종류다. 합산·비율 계산은 하지 않는다.
- 버린 대안: 서버가 질문을 파싱해 날짜를 뽑고 미리 읽는다 — 파싱이 곧 두 번째 분류기가 된다. 브라우저가 계산한 날짜를 보낸다 — 화면 상태를 신뢰 밖에 두는 원칙에 어긋난다.

### 자리표시자와 검증 (spec FR-009)

- 답변 스키마는 `{status, answerParts[{text, factIds}]}` strict. `text`의 값 자리는 `{{Fn}}`만이다.
- `AnswerValidator`가 §계약의 규칙 표대로 검사하고, 통과하면 `Evidence.value`의 표기 함수(금액·비율·날짜·정수)로 치환한다. 그래서 답의 모든 숫자는 서버가 읽은 값이다.
- 금지 표현 목록은 보조 검사다. 실제 방어는 프롬프트와 Task 6 평가 표본이다. 이 한계를 계약 문서에 그대로 적는다.

### 모델 연결 (ADR-0008)

- GMS가 확인해 준 형식은 Chat Completions(`POST {base}/v1/chat/completions`)다. `messages`(developer·user·tool), `tools`(function 5개, strict), `tool_choice:auto`, `parallel_tool_calls:false`, `response_format:{type:json_schema, strict:true}`, `store:false`.
- `store:false`라 라운드마다 이전 `assistant(tool_calls)`·`tool` 메시지를 서버가 다시 담아 보낸다.
- 어댑터는 `AssistantModelPort.step(conversation, facts, tools): ModelTurn` 뒤에 있다. Responses API로 바꾸거나 제공업체를 바꾸면 어댑터만 교체한다.
- 시간 예산 15초는 라운드별로 나눈다(초안: 첫 호출 7초, 이후 4초씩). 넘으면 503.

### 한도와 장애 (spec FR-012·FR-013)

- IP 8/분, 토큰 5/분, 하루 전체 1000, 질문 300자 — 모두 `thorp.assistant.limits.*` 설정. 인스턴스 메모리 구현에 `// ponytail:` 주석으로 다중 인스턴스 시 공유 저장소 필요를 적는다.
- 503 문구 "지금은 질문에 답할 수 없어요. 잠시 후 다시 시도해 주세요." + 재시도 버튼. 429 문구에 다시 가능한 시점. 자동 재시도 없음.

### 진입 UI (spec FR-001~FR-004)

- 버튼·말풍선 상태는 `App`의 `AnimatedRoutes` 바깥. 페이지는 `useAssistantPageContext({kind, publicToken})`로 자기 맥락을 등록하고 언마운트 때 지운다.
- 끌기는 `pointerdown/move/up` 직접 구현(라이브러리 없음). 놓을 때 `side`는 화면 중앙 기준, `y`는 화면 안으로 자른다. 말풍선은 결과 화면의 '비교 대상 고르기'와 같은 `radix-ui` Popover + `useDisclosure`.
- z-index: 말풍선 55(대화상자 60·61, 물음표 도움말 80 아래).

## Work Partition for Task Generation

`$speckit-tasks`가 아래 묶음을 실행 task로 나눈다. 묶음 하나가 Jira Task·브랜치·MR 하나다.

1. **[DOCS] ADR·spec 015·계약** — 이 문서들. (S15P21E205-434)
2. **[BE] 도우미 API 계약·기능 플래그·답변 검증기·도구 실행 틀 (모델 없이)** — 엔드포인트, 요청 검증, 한도, `AnswerValidator`, `ToolLoop`, 포트·타입, 가짜 모델. 플래그 기본 비활성.
3. **[BE] GMS Chat Completions 모델 어댑터 (도구 라운드 포함)** — `OpenAiChatCompletionsClient`, 프롬프트 파일, 로컬 가짜 HTTP 테스트.
4. **[BE] 결과 근거 팩과 읽기 도구 다섯 개** — `ResultEvidenceBuilder`, 도구 5개, `AssistantResultIT`.
5. **[FE] 질문하기 플로팅 버튼과 말풍선** — `AssistantFab`·`AssistantBubble`·API·타입·`App`·`ResultPage` 맥락 등록.
6. **[BE] 도우미 품질 표본·자동 검사·평가 문서** — `quality-cases.json`, `AssistantQualityTest`, `evaluation.md`, 플래그 관문.

2와 5는 1(계약)만 있으면 병렬이다. 3·4는 2의 포트·타입 위에서 병렬이다. 플래그 `RESULT`는 6의 관문을 넘긴 뒤에만 켠다.

## 후속(1차와 무관)

워커가 결과 `analytics`에 `maxDrawdownWindow{peakDate,troughDate}`를 담으면(004 계약 변경, 워커 담당) 005 화면의 `drawdownWindow.ts`와 도구 `getDeepestDrawdownWindow`가 모두 그 값을 읽고 선택 로직을 지운다. 배우기·시장 흐름 확장은 1차를 내보낸 뒤 spec 개정으로 정한다.

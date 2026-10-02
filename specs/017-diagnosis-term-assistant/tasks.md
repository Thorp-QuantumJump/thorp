---

description: "진단 화면 용어 도우미의 실행 작업 목록"
---

# Tasks: 진단 화면 용어 도우미

**Input**: `specs/017-diagnosis-term-assistant/`의 spec.md, plan.md, research.md, data-model.md, contracts/term-assistant-api.md, quickstart.md

**Tests**: spec의 독립 검증 시나리오와 quickstart의 검증 방침에 따라 자동 테스트 작업을 넣는다. 실패하는 테스트를 먼저 쓴다(015와 같은 방식).

**Organization**: 작업은 사용자 스토리별로 묶는다. T번호 하나가 실행 지시 하나다. 하단 작업 묶음 하나가 브랜치·MR 하나에 대응한다(constitution "작업 묶음").

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 선행 작업이 끝난 뒤 다른 파일에서 병렬로 진행할 수 있다
- **[Story]**: spec.md의 User Story 추적 라벨
- 모든 경로는 저장소 루트 기준이다. `…/assistant/`는 `backend/src/main/java/com/ssafy/thorp/assistant/`, 테스트는 `backend/src/test/java/com/ssafy/thorp/assistant/`다

## Existing Seams

| Seam (file:symbol) | 지금 상태 | 이 기능이 하는 일 | Task |
|---|---|---|---|
| `…/assistant/application/result/PageKind.java` | `RESULT` 하나 | `SECTOR_SELECTION`·`STRATEGY_SETTINGS`를 더한다 | T013 |
| `…/assistant/application/service/AssistantQuestionService.java:ask` L81-90 | 토큰 필수, `publicResults.resolve`를 무조건 부름 | 종류별 `prepare`로 나누고 서비스는 순서만 갖는다 | T014 |
| `…/assistant/application/port/EvidenceBuilder.java` | `supports`·`build`만 | `prepare(command): Prepared(context, limitKey, limit)`를 더한다. `ResultEvidenceBuilder`는 지금의 토큰 확인·`resolve`를 옮겨 받는다 | T014 |
| `…/assistant/application/service/AssistantRateLimiter.java:admit(ip, publicToken)` | 키가 `token:` 고정 | `admit(ip, scopeKey, scopeLimit)`로 바꾸고, 사유 `PER_PAGE`를 더한다 | T015 |
| `…/assistant/application/result/ToolSpec.java:dateArguments` | 날짜 인자 이름 목록 | `arguments: List<ToolArgument>`(DATE·ENUM)로 바꾼다. 015 도구 5개를 옮긴다 | T016 |
| `…/assistant/application/service/ToolLoop.java:checkArguments` | 날짜 형식만 검사 | ENUM 값 소속을 검사하고, 인자 없는 도구를 받는다 | T016 |
| `…/assistant/infrastructure/client/OpenAiChatCompletionsClient.java:toolDefinition`·`PROMPT_RESOURCE` | 날짜 인자만, 프롬프트 하나 | `enum` 인자·무인자 정의를 만들고, `Conversation.kind`로 프롬프트를 고른다 | T016·T017 |
| `…/assistant/application/result/Conversation.java` | `(question, exchanges)` | `kind`를 더한다 | T017 |
| `…/assistant/application/service/AnswerValidator.java:render`·고정 문장 상수 | 정책 하나(엄격 숫자, 집합 같음) | `ValidationPolicy`로 나누고 용어 종류의 고정 문장 셋을 둔다 | T018 |
| `…/assistant/application/result/Evidence.java`·`AssistantAnswer.java`, `…/presentation/response/AssistantAnswerResponse.java` | `source`·`relatedArticles` 없음 | 필드를 더한다(`RESULT`는 null·빈 배열) | T019 |
| `docs/specs/assistant-api/examples/answer-*.json` | `relatedArticles`·`evidence[].source` 없음 | 015 IT가 필드 단위로 대조하므로 `relatedArticles: []`, `source: null`을 넣는다 | T019 |
| `…/assistant/presentation/request/AssistantQuestionRequest.java:Context` | `publicToken`이 이름 있는 필드 | 진단 종류에서 `publicToken`이 있으면 400(`extra` 검사로 잡히지 않으므로 따로 본다) | T014 |
| `backend/src/main/java/com/ssafy/thorp/learning/application/repository/PublishedTermRepository.java` | 하나씩 조회(`findPublished`)만 | `findAllPublished()`를 더한다(R8) | T030 |
| `backend/src/test/java/com/ssafy/thorp/ArchitectureTest.java` 규칙 12 | `result`·`backtest`·`sector`·`strategy` → `assistant` 금지 | `learning`을 더한다 | T036 |
| `frontend/src/types/assistant.ts:AssistantPageKind`·`AssistantContext` | `'RESULT'`만, `publicToken` 필수 | 판별 union으로 바꾸고 `relatedArticles`·`source`를 더한다 | T041 |
| `frontend/src/components/assistant/assistantPageContext.ts:AssistantPage` | `publicToken: string` 필수 | 종류별로 선택. 초기화 키는 `kind + (publicToken ?? '')` | T041 |
| `frontend/src/components/assistant/AssistantBubble.tsx` L10-14·L168·L231 | 결과 화면 문구 상수 | `assistantCopy.ts` 종류별 표로 옮긴다 | T042 |
| `frontend/src/components/assistant/AssistantBubble.tsx` L62·L77·L108, `AssistantFab.tsx` L70 | `page.publicToken`에 의존 | 초기화 키·요청 본문을 종류별로 만든다 | T043 |
| `frontend/src/components/assistant/assistantTestSupport.tsx` | `RESULT` 고정 픽스처 | 종류를 받는 픽스처로 넓힌다 | T040 |
| `frontend/src/pages/SectorSelectionPage.tsx`, `StrategySettingsPage.tsx` | 도우미 맥락 등록 없음 | `useAssistantPageContext({kind})` 한 줄씩 | T045 |

모르는 `PageKind` 값이면 기동에 실패하는 `AssistantConfig.assistantSettings`는 enum만 늘리면 되므로 따로 task를 두지 않는다. `TermHelp.tsx` L107의 오래된 주석("note가 없는 곳(002·005)")은 이 기능과 무관해 손대지 않는다.

## Phase 1: 문서 (작업 묶음 1)

**Purpose**: 구현 묶음이 같은 계약을 보고 병렬로 진행하게 한다.

- [x] T001 spec·명료화 `specs/017-diagnosis-term-assistant/spec.md`, `checklists/requirements.md`
- [x] T002 설계 `specs/017-diagnosis-term-assistant/{plan,research,data-model,quickstart}.md`
- [x] T003 계약 `specs/017-diagnosis-term-assistant/contracts/term-assistant-api.md`
- [x] T004 요약·예시 `docs/specs/assistant-api/README.md` §5, `docs/specs/assistant-api/examples/term-*.json`
- [x] T005 색인 `specs/README.md`에 017 행과 관계 한 줄
- [x] T006 작업 목록 `specs/017-diagnosis-term-assistant/tasks.md`(이 파일)

**Checkpoint**: 머지되면 묶음 2와 4가 병렬로 시작한다.

---

## Phase 2: 기반 — 화면 종류 확장 (작업 묶음 2, 모델 없이)

**Purpose**: 모든 스토리가 함께 쓰는 경계를 만든다. 종류별 요청 검증, 한도 키, 열거 인자 도구, 검증 정책, 응답 필드가 여기에 든다. 실제 근거 빌더와 모델은 없다. **015 테스트가 하나도 바뀌지 않고 통과해야 한다**(예시 JSON 필드 추가만 허용).

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 묶음 3을 시작하지 않는다.

- [ ] T010 [P] 실패 테스트 `backend/src/test/java/com/ssafy/thorp/assistant/presentation/AssistantControllerTest.java`에 진단 종류 사례를 더한다. 확인할 것:
  - `context:{kind:"STRATEGY_SETTINGS"}` 200
  - `context`에 `publicToken`이 있으면 400
  - `kind` 밖의 키가 있으면 400
  - 켜지지 않은 종류는 404, `capabilities.scopes`는 켜진 것만 enum 순서로
  - `page:{kind}:{ip}` 분당 한도 초과는 429 `details.reason=PER_PAGE`
  - IP 분당·하루 한도는 `RESULT`와 함께 셈
  - 응답 모양이 `docs/specs/assistant-api/examples/term-answer-insufficient.json`과 같음
  - 기존 `RESULT` 사례는 그대로 통과
- [ ] T011 [P] 실패 테스트 `backend/src/test/java/com/ssafy/thorp/assistant/application/service/AnswerValidatorTest.java`에 `QUOTED`·`SUBSET` 정책 표를 더한다. 확인할 것:
  - 근거 "최대 40%까지"를 인용한 part의 "40%"는 통과, 같은 part의 "50%"는 거절
  - 근거 "20거래일"인데 답이 "20%"면 거절
  - 다른 part가 인용한 근거의 숫자를 빌려 쓰면 거절
  - "KODEX 200"을 근거 원문 그대로 쓰면 통과
  - 전각 숫자는 반각과 같게 본다
  - 자리표시자 없는 `ANSWERED` part(`factIds` 있음)는 통과, `factIds`가 비면 거절, 자리표시자 ⊄ `factIds`면 거절
  - URL·`₩`는 거절
  - 한글 수사 + 수량 단위: 근거에 없는 "두 섹터"·"스무 거래일"은 거절, 근거 원문에 같은 표기가 있으면 통과, "첫째"·"한편"은 통과(계약 §2.4 판정표)
  - 용어 종류 고정 문장 셋이 계약 §1 표와 같음(인사 예시는 "역변동성은 어떻게 비중을 정해?")
  - `STRICT`·`EXACT` 기존 사례는 그대로 통과
- [ ] T012 [P] 실패 테스트 `backend/src/test/java/com/ssafy/thorp/assistant/application/service/ToolLoopTest.java`에 열거 인자를 더한다. 확인할 것:
  - 열거 밖 값, 낯선 인자 이름, 인자 없는 도구에 인자가 있으면 실행 0회 + "사용할 수 없음"
  - 인자 없는 도구가 빈 인자로 실행됨
  - 열거 집합이 빈 도구는 목록에 없음
  - 기존 날짜 인자 사례는 그대로
- [ ] T013 타입 `…/assistant/application/result/`:
  - `PageKind`에 `SECTOR_SELECTION`·`STRATEGY_SETTINGS`
  - `EvidenceSource(type, articleId, articleTitle)`와 `EvidenceSourceType{TERM, ARTICLE, REGISTRY, SECTOR}`
  - `Evidence`에 `source` 추가(`withId`가 보존)
  - `ToolArgument(name, type, allowedValues)`·`ToolArgumentType{DATE, ENUM}`
  - `RelatedArticle(articleId, title)`
  - `ValidationPolicy`(data-model §7)
- [ ] T014 `…/assistant/application/port/EvidenceBuilder.java`에 `prepare(AskQuestionCommand): Prepared`를 더하고, `…/assistant/application/service/AssistantQuestionService.java:ask`를 종류별 갈래로 바꾼다.
  - 순서: 플래그 → 종류 판정 → 종류 켜짐 → 질문 길이 → `builder.prepare`(식별자 검증) → `limiter.admit` → 데드라인 → `builder.build` → 루프 → 검증
  - `ResultEvidenceBuilder.prepare`는 지금 서비스에 있는 토큰 필수 검사를 옮겨 받고, `resolve`는 `build` 첫머리로 옮긴다. 응답 코드 우선순위는 015와 같다.
  - 진단 종류용 임시 `prepare`는 `publicToken` 금지만 하는 빈 빌더로 둔다(T031이 대체).
  - `AskQuestionCommand`에 `publicToken` 존재 여부가 그대로 실린다.
- [ ] T015 `…/assistant/application/service/AssistantRateLimiter.java`를 `admit(ip, scopeKey, scopeLimit, reason)`으로 바꾼다.
  - `…/assistant/infrastructure/config/AssistantProperties.java`·`…/application/port/AssistantSettings.java`에 `perPagePerIpPerMinute`(5)와 `termInitialFacts`(40)를 더한다.
  - `backend/src/main/resources/application.yml`의 `thorp.assistant.limits.*`에 기본값을 둔다.
  - `// ponytail:` 주석은 유지한다.
- [ ] T016 `ToolSpec`을 `arguments: List<ToolArgument>`로 바꾸고 `…/assistant/application/service/tools/*Tool.java` 다섯 개의 `spec()`을 DATE 인자로 옮긴다. 이어서:
  - `ToolLoop.checkArguments`: ENUM 소속 검사, 무인자 도구
  - `OpenAiChatCompletionsClient.toolDefinition`: `enum` 배열, 무인자면 `properties:{}`·`required:[]`, strict 유지
  - `OpenAiChatCompletionsClientTest`에 enum·무인자 정의 직렬화 사례를 더한다
- [ ] T017 `Conversation`에 `kind`를 더하고 `ToolLoop.run`이 넘긴다. `OpenAiChatCompletionsClient`는 생성 시 `assistant/prompts/answer-ko.txt`와 `assistant/prompts/term-ko.txt`를 읽고 `kind`로 고른다.
  - 이 묶음에서는 `backend/src/main/resources/assistant/prompts/term-ko.txt`를 계약 §2 규칙의 뼈대만 담은 틀로 만든다. 본문은 T034가 채운다.
  - `RESULT` 요청 본문이 이전과 바이트 단위로 같은지 `OpenAiChatCompletionsClientTest`로 고정한다.
- [ ] T018 `AnswerValidator.render(draft, facts, policy)`를 구현한다(research R3·R4).
  - 숫자 토큰 정규식과 단위 목록, 한글 수사 + 수량 단위 판정표는 계약 §2.4 표 그대로다.
  - 고정 문장은 정책이 갖는다.
  - 서비스가 종류로 정책을 고른다.
- [ ] T019 응답 `…/assistant/presentation/response/AssistantAnswerResponse.java`에 `relatedArticles`와 `evidence[].source`를 더한다.
  - `AssistantAnswer`는 쓰인 근거에서 `articleId`를 중복 없이 처음 순서로 최대 3개 모은다.
  - `EvidenceBundle`에 `registryProvisional`을 더하고(`RESULT`는 false), 서비스가 true이고 쓰인 근거에 `REGISTRY`가 있으면 검증 뒤 "이 설정 설명은 아직 확정 전의 임시 내용입니다. "를 붙인다(spec FR-009b). `AssistantControllerTest`에 사례를 더한다.
  - `docs/specs/assistant-api/examples/answer-*.json`에 `relatedArticles: []`·`source: null`을 넣는다.
  - `backend/src/main/resources/application-prod.yml`·`.env.example`·`infra/.env.prod.example`의 `THORP_ASSISTANT_ENABLED_SCOPES` 주석에 새 값 둘을 적는다.
  - `./gradlew spotlessApply build`를 통과시킨다.

**Checkpoint**: 플래그가 꺼진 상태로 머지할 수 있다. 진단 종류를 켜도 근거가 비어 `INSUFFICIENT_EVIDENCE`까지만 흐른다.

---

## Phase 3: User Story 1·2 — 용어 근거 팩과 읽기 도구 (작업 묶음 3) 🎯 MVP 서버

**Goal**: 사이트 콘텐츠(013 게시 용어·글, 003 등록표, 001 섹터 목록)만으로 근거를 만들고, 없는 용어와 범위 밖 질문을 정직하게 처리한다.

**Independent Test**: 글 둘을 게시하고 하나는 초안으로 둔 IT를 가짜 모델로 돌린다. 처음 팩·도구 열거 집합·반환 근거가 계약 §2.1·§2.2와 같은지, 초안·게시 취소 내용이 어느 근거에도 없는지 확인한다.

- [ ] T030 [P] [US2] 013 `backend/src/main/java/com/ssafy/thorp/learning/application/repository/PublishedTermRepository.java`에 `findAllPublished()`를 더하고, 구현체(`learning/infrastructure/persistence/PublishedTermRepositoryImpl.java`와 `LearningPublishedTermJpaRepository.java`의 native query)에 반영한다.
  - 조건은 `findPublished`와 같다: 글 `PUBLISHED`, 색인 판 = 게시 판.
  - 정렬은 읽는 순서 → `term_key`다.
  - `LearningQueryService.listPublishedTerms()`를 더한다.
  - 테스트는 `backend/src/test/java/com/ssafy/thorp/learning/infrastructure/persistence/LearningRepositoryTest.java`와 `learning/application/service/LearningQueryServiceTest.java`에 더한다: 초안·게시 취소 제외, 판 어긋남 제외, 정렬.
- [ ] T031 [P] [US1] 실패 테스트 `backend/src/test/java/com/ssafy/thorp/assistant/application/service/term/TermEvidenceBuilderTest.java`. 확인할 것:
  - 두 종류의 처음 팩이 계약 §2.1 표와 같음(용어마다 1개, `STRATEGY_SETTINGS`는 전략 설명, `SECTOR_SELECTION`은 섹터 목록 1개)
  - 라벨이 data-model §4 표와 같음
  - `source`가 맞음
  - 용어 41개 픽스처에서 상한 40을 지키고 화면 우선순위대로 실음 + "일부 용어 설명은 싣지 않음" 근거
  - 게시 용어 0개여도 오류가 아님
  - 등록표·섹터 목록·용어 색인 조회가 예외를 던지면 503이고 모델 포트 `verifyNoInteractions`
  - 근거 어디에도 `range`·`unit`·사용자 선택값 키가 없음
  - 등록표 `provisional()`이 true면 `registryProvisional=true`
- [ ] T032 [P] [US1] 실패 테스트 `backend/src/test/java/com/ssafy/thorp/assistant/application/service/term/*ToolTest.java`.
  - `readArticle`:
    - 열거 집합이 요청 시점 게시 글
    - 블록마다 근거 1개, `hypothetical`·`example`·`sources` 없음
    - 처음 팩에 있는 용어 문장은 다시 싣지 않음
    - 요청 사이 게시 취소되면 "사용할 수 없음: 게시되지 않은 글"
  - `readStrategy`:
    - 설정 항목 근거 = description + 선택지 이름, 규칙 `detail`마다 1개
    - 활성 전략만 열거
  - `readSectorList`: 처음 팩에 있으면 "이미 근거에 있습니다"
  - 공통: 어느 도구 근거에도 식별자 원문 외의 내부 필드가 없음
- [ ] T033 [US1] `…/assistant/application/service/term/TermEvidenceBuilder.java`(`supports`는 진단 두 종류)를 구현한다.
  - `prepare`: `publicToken` 금지, 한도 키 `page:{kind}:{ip}`
  - `build`: 처음 팩과 도구 셋을 요청 시점 목록으로 묶는다
  - 의존: `LearningQueryService`, `ParameterRegistryPort`, `SectorService`
  - T014의 임시 빈 빌더를 지운다.
- [ ] T034 [US1] 도구 `…/assistant/application/service/term/ReadArticleTool.java`·`ReadStrategyTool.java`·`ReadSectorListTool.java`를 구현한다(계약 §2.2). 도구 설명에 `id=이름` 대응을 적는다.
- [ ] T035 [US2] 프롬프트 `backend/src/main/resources/assistant/prompts/term-ko.txt` 본문을 쓴다. 규칙:
  - 근거 밖 지식 금지, 근거 문장 풀어 쓰기 허용, 숫자는 근거 표기 그대로
  - 고르기·성과 우열·최적값·미래·사용자 입력값 판단은 `OUT_OF_SCOPE`, 결과 값 질문도 `OUT_OF_SCOPE`
  - 동의어 맞추기는 근거가 있을 때만
  - 두 원천이 다른 말을 하면 섞지 말고 각각 말하되 출처를 근거 종류 이름("배우기 글에서는", "이 화면의 설정 설명에서는")으로 밝히기
  - 기본값은 "처음 채워 둔 값"으로만 말하고 "추천값·적정값·무난한 값"으로 말하지 않기(spec FR-009a)
  - 전략의 방식 차이는 답하고 성과 차이는 `OUT_OF_SCOPE`(spec FR-009)
  - 수량은 근거의 숫자 표기 그대로 쓰고 한글 수사로 바꾸지 않기(spec FR-007(다))
  - 라벨(문단 번호 등)을 답에 쓰지 않기
  - 도구는 목록 id로만 부르기
  - `GREETING` 기준
- [ ] T036 [US1] `backend/src/test/java/com/ssafy/thorp/ArchitectureTest.java` 규칙 12의 대상에 `learning`을 더한다(`learning`은 `assistant`를 참조하지 않는다).
- [ ] T037 [US2] IT `backend/src/test/java/com/ssafy/thorp/assistant/AssistantTermIT.java`(`PortMockedIntegrationTestSupport`, 가짜 모델). 확인할 것:
  - 게시 글 둘·초안 글 하나를 심고 끝까지 흐름
  - 응답이 `examples/term-answer-answered.json` 모양
  - 초안 글 id로 `readArticle` 요청 시 실행 0회
  - 요청 사이 게시 취소가 다음 처음 팩에 반영됨(캐시 없음)
  - 모델 포트에 간 직렬화 본문에 `publicToken`·`weightTenths`·`selected` 문자열 0회(SC-004)
  - `RESULT` IT(`AssistantResultIT`)는 불변
  - `./gradlew spotlessApply build`

**Checkpoint**: 가짜 모델로 진단 종류가 끝까지 흐른다. 플래그는 꺼져 있다.

---

## Phase 4: User Story 3 — 진단 화면 말풍선과 글 연결 (작업 묶음 4)

**Goal**: 두 화면에서 버튼으로 말풍선을 열고, 화면별 문구·예시로 묻고, 근거 글로 이동한다.

**Independent Test**: 가짜 API로 두 화면의 버튼 유무, 문구, 글 연결, 화면 전환 시 이력 비움, 결과 화면 불변을 확인한다.

- [ ] T040 [P] [US3] `frontend/src/components/assistant/assistantTestSupport.tsx`를 종류를 받는 픽스처로 넓힌다. 실패 테스트 `frontend/src/components/assistant/AssistantBubble.test.tsx`에 진단 종류 사례를 더한다. 확인할 것:
  - 머리글 `용어 도우미`, 안내 "이 화면의 용어에 대해 질문해보세요.", 화면별 예시 질문, 입력 안내
  - 요청 본문 `context`가 `{kind}`뿐
  - `relatedArticles` 1~3개가 `배우기: {title}` 링크로 `/learn/{articleId}`(state `returnTo`)에 감
  - `relatedArticles`가 비면 링크 없음
  - 종류가 바뀌면 이력이 비고 진행 중 요청을 `abort`함
  - `RESULT` 문구·동작 사례는 그대로
- [ ] T041 [P] [US3] `frontend/src/types/assistant.ts`를 바꾼다.
  - `AssistantPageKind` union, `AssistantContext` 판별 union(`RESULT`만 `publicToken`)
  - `AssistantAnswer.relatedArticles`, `AssistantEvidence.source`, `tab` nullable
  - `frontend/src/components/assistant/assistantPageContext.ts`의 `AssistantPage`를 종류별로 바꾼다.
- [ ] T042 [US3] `frontend/src/components/assistant/assistantCopy.ts`를 만든다.
  - 종류별 `{header, notice, examples, placeholder}`
  - 결과 화면 값은 지금 상수 그대로
  - 진단 예시는 등록표·섹터 목록만으로 답할 수 있는 질문(spec FR-002·SC-007). 섹터 이름은 현재 대응표에 있는 섹터로 고른다.
  - `AssistantBubble.tsx`가 이 표를 읽는다.
- [ ] T043 [US3] `AssistantBubble.tsx`·`AssistantFab.tsx`의 초기화 키와 요청 본문을 종류별로 바꾼다. 답 아래 `relatedArticles` 링크를 `assistant.module.css`로 꾸민다(최대 3개, 좁은 화면 바닥 시트에서 답 아래).
  - 링크 `state`는 `TermHelp.tsx`와 같은 `LearningReturnState`(`frontend/src/types/learning.ts`)다.
- [ ] T044 [P] [US3] `frontend/src/api/assistant.ts`의 응답 파서가 `relatedArticles`·`source`를 읽게 한다. 없으면 빈 배열·null이다. 테스트는 새 파일 `frontend/src/api/assistant.test.ts`에 둔다(`vite.config.ts` `test.include`의 `src/api/*.test.ts`에 이미 든다).
- [ ] T045 [US3] `frontend/src/pages/SectorSelectionPage.tsx`에 `useAssistantPageContext({kind:'SECTOR_SELECTION'})`, `frontend/src/pages/StrategySettingsPage.tsx`에 `useAssistantPageContext({kind:'STRATEGY_SETTINGS'})`를 등록한다.
  - 두 화면의 기존 테스트(`SectorSelectionPage.test.tsx`·`StrategySettingsPage.test.tsx` 등)가 플래그 꺼짐에서 그대로 통과해야 한다(SC-006).
- [ ] T046 [US3] `npm run lint && npm run build && npm test`(skipped 수 확인). preview로 확인할 것:
  - 넓은 화면 끌기·붙기
  - 좁은 화면(≤600px) '전략 선택'에서 고정 버튼이 주 행동 버튼·하단 콘텐츠를 가리지 않음(research R11). 가리면 `AssistantFab.tsx`의 기본 세로 위치만 조정
  - 글 연결로 갔다가 '하던 진단으로 돌아가기'로 오면 002·003 입력 유지

**Checkpoint**: 서버 플래그를 켜면 두 화면에서 동작한다. 꺼져 있으면 버튼이 없다.

---

## Phase 5: 품질 관문 (작업 묶음 5)

**Goal**: 화면 종류마다 따로 켤 근거를 만든다(spec FR-014).

- [ ] T050 `backend/src/test/resources/assistant/term-quality-cases.json`을 만든다.
  - 화면 종류마다 30개: 근거 있는 질문 20, 사이트에 없는 용어·초안 용어 5, 고르기·성과·입력값 판단 5
  - '전략 선택'은 013 게시 전이면 근거 있는 질문을 등록표 항목으로 채운다.
  - 화면 예시 질문 전부를 넣는다(SC-007).
  - 행: `kind`, `question`, `publishedContent`(none|seeded), `expectedStatus`, `expectedSourceTypes`, `forbiddenClaims`
- [ ] T051 `backend/src/test/java/com/ssafy/thorp/assistant/AssistantTermQualityTest.java`(가짜 모델·기록된 초안 재생, CI). 확인할 것:
  - 상태 일치
  - 숫자 규칙(인용 원문 대조)
  - 근거 출처가 요청 시점 게시 콘텐츠·등록표·섹터 목록
  - 사용자 선택값·식별자 비노출
  - 예시 질문이 게시 콘텐츠 0개에서 `ANSWERED`이고 근거가 `REGISTRY`·`SECTOR`뿐
- [ ] T052 `docs/specs/assistant-api/evaluation-017.md`를 쓴다.
  - 실제 모델(`ASSISTANT_LIVE_TEST=true`, `AssistantLiveTest` 방식)로 종류별 30개를 돌린다.
  - 기록: SC-001·SC-003·SC-005·SC-007, 등록표 버전, 게시 글·용어 수, 모델명, 날짜, 라운드 수, 토큰, 지연
  - 원문은 테스트 산출물에만 둔다.
  - 금지 표현에 고르기 권유 어구를 더할지 여기서 판단하고, 더하면 `backend/src/main/resources/assistant/forbidden-phrases.txt`를 고친다.
- [ ] T053 관문 판정. '전략 선택'이 SC-001~SC-004·SC-007을 충족하면 배포 설정 `THORP_ASSISTANT_ENABLED_SCOPES`에 `STRATEGY_SETTINGS`를 넣는다. 두 명 독립 채점(SC-002)을 마친 뒤다. '투자 대상 고르기'는 013 게시 뒤 T050~T052를 다시 돌려 판정한다. 미달이면 원인을 기록하고 플래그를 유지한다.

---

## Phase 6: 마무리

- [ ] T060 [P] `specs/README.md`·`docs/specs/assistant-api/README.md`를 구현 결과와 대조한다. 한도 값, 고정 문장, 예시 파일 목록을 확인한다.
- [ ] T061 [P] `specs/017-diagnosis-term-assistant/quickstart.md` 절차를 끝까지 한 번 돌려 보고, 어긋난 명령·기대값을 고친다.
- [ ] T062 015 회귀를 확인한다. `RESULT`만 켠 설정에서 `AssistantResultIT`·`AssistantQualityTest`·결과 화면 테스트가 이 기능 전과 같은 결과인지 본다.

---

## Dependencies & Execution Order

- Phase 1 → Phase 2 → Phase 3 → Phase 5
- Phase 4는 Phase 1 뒤 언제든 할 수 있다(계약과 예시 JSON만 필요). 서버 연결 확인은 Phase 2·3 머지 뒤다.
- Phase 5의 실제 모델 평가는 3·4가 모두 머지된 뒤다. '투자 대상 고르기' 판정은 013 운영 게시가 추가 선행 조건이다.
- 스토리 사이: US1과 US2는 같은 서버 부품(Phase 2·3)을 함께 쓴다. US2의 정직한 처리(고정 문장·숫자 규칙·초안 제외)는 US1과 같은 묶음에서 끝나야 내보낼 수 있다(spec "P1 스토리 1과 함께"). US3는 화면이다.

### 병렬 실행 예

- 묶음 2: T010·T011·T012(테스트 파일이 서로 다름) → T013 → T014~T019
- 묶음 3: T030(learning)·T031·T032(assistant/term 테스트)를 동시에 → T033·T034 → T035·T036 → T037
- 묶음 4: T040·T041·T044를 동시에 → T042 → T043 → T045 → T046

## Implementation Strategy

- **MVP**: 묶음 2·3·4로 '전략 선택' 화면 하나를 끝까지 만든다. 등록표만으로 동작하므로 013 게시를 기다리지 않는다. 그 뒤 묶음 5에서 '전략 선택'만 먼저 관문을 넘겨 켠다.
- **다음**: 013 운영 게시 → '투자 대상 고르기' 표본을 다시 돌려 판정하고 켠다.
- 각 묶음은 플래그가 꺼진 상태로 머지할 수 있어, 중간 묶음이 배포돼도 사용자 화면은 바뀌지 않는다.

## Recommended Work Batches

| 묶음 | 제목 | 브랜치 | T번호 | 선행 |
|---|---|---|---|---|
| 1 | `docs(be): 진단 화면 용어 도우미 spec 017을 추가한다` | `docs(be)/017-assistant-term-spec` | T001~T006 | — |
| 2 | `feat(be): 도우미 화면 종류 확장 기반을 만든다` | `feat(be)/017-assistant-page-kinds` | T010~T019 | 1 |
| 3 | `feat(be): 용어 근거 팩과 읽기 도구 셋을 더한다` | `feat(be)/017-assistant-term-evidence` | T030~T037 | 2 |
| 4 | `feat(fe): 진단 화면에 용어 도우미를 연결한다` | `feat(fe)/017-assistant-diagnosis-pages` | T040~T046 | 1 |
| 5 | `feat(be): 용어 도우미 품질 표본과 평가를 더한다` | `feat(be)/017-assistant-term-quality-gate` | T050~T053 | 3·4 |
| — | 마무리 | 각 묶음 MR 안에서 | T060~T062 | 해당 묶음 |

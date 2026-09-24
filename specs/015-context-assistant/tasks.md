---

description: "결과 화면 질문 도우미의 실행 작업 목록"
---

# Tasks: 화면 맥락 도우미

**Input**: `specs/015-context-assistant/`의 spec.md, plan.md, contracts/assistant-api.md

**Tests**: spec의 독립 검증 시나리오와 plan의 검증 방침에 따라 자동화 테스트 작업을 포함한다. 실패하는 테스트를 먼저 쓴다.

**Organization**: 작업은 사용자 스토리별로 구성한다. 개별 T번호는 실행 지시이며, 하단의 작업 묶음 하나가 Jira Task·Branch·MR 하나에 대응한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 선행 작업이 끝난 뒤 다른 파일에서 병렬 진행 가능
- **[Story]**: spec.md의 User Story 추적 라벨
- 모든 경로는 저장소 루트 기준

## Existing Seams

| Seam (file:symbol) | Current state | What this feature does | Task |
|---|---|---|---|
| `backend/.../result/application/service/PublicResultService.java:resolve/getDetail/getTimeseries/getTrades/getPositions` | 005 공개 조회. 그대로 있음 | 그대로 부른다. 바꾸지 않는다 | T020·T030 |
| `frontend/src/App.tsx:AnimatedRoutes` | 라우트 전환 시 페이지를 다시 그림 | 그 바깥에 버튼·말풍선을 마운트한다 | T041 |
| `frontend/src/pages/ResultPage.tsx` | 결과 화면 | `useAssistantPageContext({kind:'RESULT', publicToken})` 등록 한 줄 | T042 |
| `frontend/src/components/result/drawdownWindow.ts:findDrawdownWindow` | 화면에서 하락 구간 날짜를 고른다 | 같은 정의를 서버 도구 `DeepestDrawdownWindowTool`이 갖는다(두 벌, 후속에서 워커 값으로 통일) | T034 |
| `backend/.../market/infrastructure/client/MarketHttpClient.java` | 외부 HTTP 관례 | 같은 모양으로 `AssistantHttpClient`를 만든다 | T025 |
| `frontend/src/components/common/InlineHelp.tsx` (z 80), `comparison.module.css .popover` (z 61) | 층 순서 | 말풍선은 55에 둔다 | T043 |
| `frontend/src/components/result/ResultTabs.tsx:ResultTabs` | `defaultTab`만 받는 비제어 탭 | 바깥에서 탭을 바꾸는 제어 prop(`activeTab`/`onActiveTabChange`)을 더한다. 근거 카드 → 탭 이동에 필요 | T044 |
| `backend/.../result/application/service/PublicResultService.java:getWeights` | 비중은 상세에 없고 별도 조회 | 초기 팩의 두 번째 조회 | T032 |
| `backend/src/test/java/com/ssafy/thorp/ArchitectureTest.java` 규칙 8~11 | 도메인 간 금지 의존 | 규칙 12 `result`·`backtest`·`sector` → `assistant` 금지 추가 | T017 |
| `backend/.../sector/application/service/SectorService.java:getSectors` | 001 섹터 목록(id·이름) | `sectorId → 섹터 이름` 라벨 원천. 013 `SectorServiceSectorMapAdapter`와 같은 방식 | T032 |

## Phase 1: 문서 (작업 묶음 1 · S15P21E205-434)

**Purpose**: 구현 묶음이 같은 계약과 안전 규칙을 보고 병렬로 가게 한다.

- [x] T001 `docs/adr/0008-external-llm-via-gms.md` — 제공업체(GMS 게이트웨이)·모델(`gpt-5-mini`)·API 형식·자료 처리 조건·한도·키·장애 시 동작·버린 대안
- [x] T002 `specs/015-context-assistant/{spec,plan,tasks}.md` — spec-kit 형식
- [x] T003 `specs/015-context-assistant/contracts/assistant-api.md` — HTTP 계약, 모델 요청·응답 스키마, 도구 5개 정의, 검증 규칙 표
- [x] T004 `docs/specs/assistant-api/README.md`와 `examples/*.json` — 계약 요약과 IT가 대조할 예시
- [x] T005 `specs/README.md`에 015 행과 흐름 한 줄

**Checkpoint**: 머지되면 묶음 2와 5가 병렬로 시작한다.

---

## Phase 2: 기반 — API 계약·검증기·도구 실행 틀 (작업 묶음 2, 모델 없이)

**Purpose**: 모든 스토리가 공유하는 경계 — 요청 검증, 토큰 판정 순서, 한도, 자리표시자 검증, 도구 루프. 실제 모델은 없다.

**⚠️ CRITICAL**: 이 단계가 끝나기 전에는 묶음 3·4를 병합하지 않는다.

- [ ] T010 [P] 실패 테스트 `backend/src/test/java/com/ssafy/thorp/assistant/presentation/AssistantControllerTest.java` — 301자·공백 질문 400, 낯선 `kind` 400, `RESULT`에 토큰 없음 400, 식별자 둘 동시 400, CSRF 누락 403, 플래그 비활성 404(`capabilities`는 200), IP 한도 429, 결과 토큰별 한도 429, 하루 상한 429, 응답 모양이 `docs/specs/assistant-api/examples/*.json`과 같음
- [ ] T011 [P] 실패 테스트 `.../assistant/application/service/AnswerValidatorTest.java` — 모르는 `factId` 거절, `text` 안 아라비아 숫자·`%`·`원`·날짜·URL 거절, 한글 수사("두 번") 통과, `{{F1}}`↔`factIds` 불일치 거절, `ANSWERED`인데 `factIds` 빈 part 거절, 길이·part 수 상한, 금지 표현 거절, 치환 결과가 `Evidence.value` 표기와 같음
- [ ] T012 [P] 실패 테스트 `.../assistant/application/service/ToolLoopTest.java`(가짜 모델·가짜 도구) — 목록에 없는 도구 이름 실행 0회, 스키마 밖 인자(식별자 필드 포함) 거절 후 "사용할 수 없음" 반환, 3번째 라운드 요청은 끊고 `INSUFFICIENT_EVIDENCE`, 팩 60개 초과 시 뒤를 잘라 "일부만 읽음" 근거, 15초 예산 초과 시 503
- [ ] T013 타입·포트 `.../assistant/application/{port,result}/` — `Evidence(id,label,value,asOf,tab)`, `EvidenceBundle(facts, tools)`, `ToolSpec`, `ToolCall(name, arguments)`, `ModelTurn`(ToolCall | ModelDraft), `ModelDraft(status, parts[{text,factIds}])`, `AssistantAnswer(status, answer, evidence, page)`, `AssistantModelPort.step(conversation, facts, tools)`, `AssistantTool{spec(), run(args)}`, `EvidenceBuilder{supports(kind), build(context)}`
- [ ] T014 `AssistantQuestionService.ask(question, context)` — 순서: 플래그 → 요청 검증 → 한도 → 페이지 판정(`RESULT`는 `PublicResultService.resolve`) → 근거 빌더(첫 호출 `getWeights`가 005 `requireResultReady`(private)의 409를 그대로 던진다 — `RESULT_NOT_READY`/`BACKTEST_FAILED`/`NO_PARTIAL_RESULT`, 따로 판정하지 않는다) → `ToolLoop`(전체 데드라인 15초 우선, 라운드 예산은 그 안에서) → `AnswerValidator` → 치환. 이 묶음에서는 빈 근거 빌더와 가짜 모델로 `INSUFFICIENT_EVIDENCE`까지 흐른다
- [ ] T015 `AnswerValidator`(금지 표현은 `backend/src/main/resources/assistant/forbidden-phrases.txt`, 한 줄에 하나·`#` 주석)·`ToolLoop`(근거 알갱이: 점 1개·거래 1건·보유 1종목 = 근거 1개, 상한 초과 시 도구 결과부터 자르고 초기 팩 보존)·`RateLimiter`(`thorp.assistant.limits.*`, 인스턴스 메모리, `// ponytail:` 주석) 구현
- [ ] T016 `AssistantController` — `GET /assistant/capabilities`, `POST /assistant/questions`; `ErrorCode`에 `ASSISTANT_UNAVAILABLE`(503)·`ASSISTANT_LIMIT`(429); `application.yml`·`application-prod.yml`·`.env.example`에 `THORP_ASSISTANT_*` — prod도 **빈 기본값**(`${THORP_ASSISTANT_API_KEY:}`)으로 두어 미설정 시 기동은 되고 기능만 비활성
- [ ] T017 `ArchitectureTest`에 규칙 12 추가 — `result`·`backtest`·`sector`는 `assistant`를 참조하지 않는다(규칙 8~11 관례). `./gradlew spotlessApply build` 통과. 로그에 토큰·식별자·원문 없음 확인

**Checkpoint**: 플래그 꺼진 상태로 머지 가능. 3·4·5가 이 포트·타입 위에서 병렬로 간다.

---

## Phase 3: User Story 1·2 — 모델 어댑터 (작업 묶음 3)

**Goal**: 실제 GMS Chat Completions에 도구 라운드를 포함해 붙인다.

**Independent Test**: 로컬 가짜 HTTP 서버가 `tool_calls`를 한 번 돌려주고 두 번째에 최종 답변을 주는 시나리오에서 요청 본문·이력·응답 파싱이 계약대로인지 확인한다.

- [ ] T020 [P] [US1] 실패 테스트 `.../assistant/infrastructure/client/OpenAiChatCompletionsClientTest.java`(가짜 HTTP) — 요청에 `model`·`store:false`·`parallel_tool_calls:false`·`response_format` strict·`tools` 5개만(내장 도구 없음); developer·user 메시지가 질문과 근거 팩을 분리; 응답 `tool_calls`를 `ToolCall`로 읽고 `arguments`가 JSON이 아니면 검증 실패로 넘김; 두 번째 라운드 요청에 이전 `assistant(tool_calls)`·`tool` 메시지가 들어 있음; `refusal`·빈 `content`·스키마 불일치·시간 초과 → `ASSISTANT_UNAVAILABLE`; 직렬화 결과 어디에도 토큰·`backtestId` 문자열 없음
- [ ] T021 [US1] `AssistantHttpClient`(`MarketHttpClient` 관례, 전체 데드라인의 남은 시간과 라운드 예산 중 작은 쪽)·`OpenAiChatCompletionsClient implements AssistantModelPort`·`AssistantModelProperties`(`THORP_ASSISTANT_API_KEY`·`THORP_ASSISTANT_BASE_URL`·`THORP_ASSISTANT_MODEL`, 비어 있으면 비활성). 응답의 `usage` 토큰 수·라운드 수·지연을 운영 로그에 남긴다(원문·식별자는 없이)
- [ ] T022 [US1] 프롬프트 `backend/src/main/resources/assistant/prompts/answer-ko.txt` — 자리표시자 규칙, 도구 사용 지침(모르는 날짜는 묻지 말고 도구로 확인, 기간 밖은 없다고 답, 최대 2회), 부분 결과 접두, 헌장 III 금지 문구, 범위 밖 안내 문구
- [ ] T023 [US1] 키·주소·모델명 중 하나라도 비어 있으면 어댑터 빈이 뜨지 않고 `capabilities.enabled=false`이며 **컨텍스트 기동은 성공**하는지 설정 테스트로 고정(prod 프로파일의 빈 기본값 포함). `spotlessApply build`

---

## Phase 4: User Story 1·2 — 결과 근거 팩과 도구 (작업 묶음 4)

**Goal**: 상세 응답 전부를 근거로 만들고, 시계열·거래·보유를 도구로 읽는다.

**Independent Test**: 완료·부분·계산 중·실패·만료 결과 픽스처로 근거 팩과 도구 5개의 검증·반환을 확인한다. 모델은 가짜다.

- [ ] T030 [P] [US1] 실패 테스트 `.../assistant/application/service/ResultEvidenceBuilderTest.java` — 400/404/410 토큰이면 모델 포트 `verifyNoInteractions`; 계산 중·실패·시작 전 취소는 409; 초기 팩이 계약 §2.1 포함 표와 정확히 같고(라벨은 표의 한국어, 필드명 아님; 섹터 라벨은 `SectorService` 이름, 못 찾으면 `sectorId` 원문) 섹터 8개 픽스처에서도 60개 이하이며 상관 행렬은 없고 위험·손실 기여가 빠지면 "일부 분석 값은 싣지 않음" 근거가 있음; 실제 비중은 `getWeights` 마지막 점의 `actual`이고 `asOf`가 그 날짜; `getWeights`가 409를 던지면 모델 포트 `verifyNoInteractions`; `publicToken`·`expiresAt`·`versions`·`progress`·`cancelRequested`·실행 시각·`failure`는 근거에 없음; 표기 결과가 `frontend/src/components/result/formatResult.ts`의 `formatMoney`·`formatRatio` 출력과 같은 픽스처 쌍(예: `17078.49`→`17,078원`, `0.166652`→`16.67%`, 경계값 `0.12345`→`12.35%` HALF_UP)으로 대조; 날짜는 ISO 원문 그대로(탭과 같다); `partial=true`면 완료 끝 날짜 근거와 접두 문장; 근거·도구 인자 어디에도 `backtestId`·토큰 없음; `tab`이 결과 탭 id 중 하나
- [ ] T031 [P] [US2] 실패 테스트 `.../assistant/application/service/tools/*ToolTest.java` — `getEquityOn` 기간 밖 거절·휴장일은 직전 거래일과 라벨 표시; `getEquityRange` 1년 초과·뒤집힌 구간 거절, 첫·끝·최고·최저 넷만(새 점 없음); `getTradesBetween` 거래 1건=근거 1개, 라운드 상한 30을 넘으면 뒤를 자르고 "일부 거래" 근거; 시계열 점의 `benchmarkReturn`·`cashFlows`는 어느 도구 근거에도 없음; 도구 결과에 `backtestId` 문자열 없음(record 통째 복사 금지); `getPositionsOn` 기간 밖 거절; `getDeepestDrawdownWindow`가 `frontend/src/components/result/drawdownWindow.ts` 예시와 같은 날짜를 고르고 `summary.maxDrawdown`과 어긋나면 빈 결과; 어느 도구에도 식별자 인자가 없음
- [ ] T032 [US1] `ResultEvidenceBuilder implements EvidenceBuilder` — `getWeights`(먼저, 409 판정 포함)와 `getDetail`에서 계약 §2.1 포함 표의 항목만 필드 단위로 `Evidence`로 옮긴다(record 통째 복사 금지). 상관 행렬은 넣지 않고, 60을 넘으면 위험·손실 기여부터 빼고 "일부 분석 값은 싣지 않음" 근거를 남긴다. 섹터 라벨은 `SectorService.getSectors()`의 이름(없으면 `sectorId`). 값은 API 문자열 그대로, 표기 함수(금액·비율·날짜·정수)를 종류별로 붙인다. 설정값 축의 라벨은 003 등록표(`ParameterRegistryPort`)의 라벨을 쓴다. 도구 5개를 이 요청의 토큰에 묶어 `EvidenceBundle.tools`로
- [ ] T033 [US2] 도구 4개 `EquityOnTool`·`EquityRangeTool`·`TradesBetweenTool`·`PositionsOnTool` — `getTimeseries`·`getTrades(from,to)`·`getPositions(date)`를 묶인 토큰으로 읽고 고르기만. 결과 record를 통째로 옮기지 않고 필드 단위로 `Evidence`를 만든다(`backtestId` 제외, 점의 `benchmarkReturn`·`cashFlows` 제외)
- [ ] T034 [US2] `DeepestDrawdownWindowTool` — `drawdownWindow.ts`와 같은 정의(가장 낮은 `drawdown`의 첫날, 그 앞 고점 날, `summary.maxDrawdown` 불일치면 없음). 두 벌임을 주석에 적고 후속(워커 값)으로 연결
- [ ] T035 [US1] `.../assistant/AssistantResultIT.java`(`PortMockedIntegrationTestSupport`, 가짜 모델) — 완료 결과로 끝까지 흐르고 응답이 `examples/answer-answered.json` 모양; `ResultApiIT` 불변 확인; `spotlessApply build`

---

## Phase 5: User Story 3·4 — 플로팅 버튼과 말풍선 (작업 묶음 5)

**Goal**: 결과 화면에서 버튼으로 말풍선을 열어 묻고 답과 근거 카드를 본다.

**Independent Test**: 가짜 API로 버튼·끌기·말풍선·답변·오류·페이지 전환을 검증한다.

- [ ] T040 [P] [US3] 실패 테스트 `frontend/src/components/assistant/AssistantFab.test.tsx` — 접근 이름 `질문하기`, 보이는 글자 없음, `Bot` 아이콘 `aria-hidden`; `capabilities.enabled=false`면 없음; 로그인·가입·관리 경로에 없음; 기본 우측 하단; 끌어 가운데 놓으면 가까운 여백으로 붙고 `thorp.assistant.fabPosition`에 저장; 재렌더 시 저장 자리; ≤600px에서 끌기 무시·우측 하단 고정; Tab·Enter로 열림; `[data-variant="default"]` 버튼은 '비교 대상 고르기' 하나
- [ ] T041 [P] [US3] 실패 테스트 `.../AssistantBubble.test.tsx` — 열면 맥락 표시·예시 질문·입력 하나·전송 안내·입력에 초점; 왼쪽 여백이면 오른쏙으로 펼침; 답과 `factIds`에 쓰인 근거 카드만; 근거 카드 클릭이 해당 탭으로; 페이지 이동 시 `abort`·요청 번호로 이전 답 미표시·말풍선 닫힘; Escape·바깥 누르기로 닫히고 초점 복귀; 429 대기 문구, 503 문구+재시도 버튼(자동 재시도 없음); ≤600px 바닥 시트; `InlineHelp`(z 80)가 위에 뜸
- [ ] T042 [US3] `frontend/src/types/assistant.ts`·`frontend/src/api/assistant.ts`(`fetchAssistantCapabilities`, `askAssistant(question, context, signal)` — `runCsrfProtectedMutation` 경로)·`assistantPageContext.tsx`(`useAssistantPageContext`)·`fabPosition.ts`(`localStorage` try/catch, 테스트는 `fabPosition.test.tsx` — `vite.config.ts` `test.include`가 `.test.ts`를 잡지 않는다)
- [ ] T043 [US3] `AssistantFab.tsx`(pointer 끌기 직접 구현, 붙기, `prefers-reduced-motion`)·`AssistantBubble.tsx`(`radix-ui` Popover·`useDisclosure`, z 55, `role="dialog" aria-modal="false"`, 바닥 시트)·`assistant.module.css`
- [ ] T044 [US3] `App.tsx`에 `AnimatedRoutes` 바깥 마운트, `ResultPage.tsx`에 맥락 등록과 "탭 열기 요청" 연결, **`ResultTabs.tsx`에 제어 prop(`activeTab`·`onActiveTabChange`, 없으면 지금처럼 비제어) 추가**와 `ResultDetailTabs.tsx` 전달, `ResultTabs.test.tsx`·`ResultPage.test.tsx` 갱신 — 근거 카드 클릭이 그 탭을 여는 경로다
- [ ] T045 [US4] 오류 문구 — 503 "지금은 질문에 답할 수 없어요. 잠시 후 다시 시도해 주세요.", 429 다시 가능한 시점, `OUT_OF_SCOPE`·`INSUFFICIENT_EVIDENCE` 카드
- [ ] T046 `npm run lint && npm run build && npm test`(skipped 0, `vite.config.ts` `test.include` 확인). 데스크톱(끌기·붙기·펼침 방향)·모바일(고정·바닥 시트)에서 사람이 배치 확인. 서버 플래그가 꺼져 있으면 버튼이 없음을 확인

---

## Phase 6: 품질 관문 (작업 묶음 6)

**Goal**: 플래그를 켤 근거를 만든다.

- [ ] T050 `backend/src/test/resources/assistant/quality-cases.json` — 결과 질문 20개(절반은 날짜·구간·하락 구간) + 경계 10개(추천·미래·원인·비교 대상·KOSPI·부분 결과·기간 밖 날짜·"다른 결과 보여 줘"). 행: `question`, `expectedTools`, `requiredEvidenceKinds`, `forbiddenClaims`, `expectedStatus`
- [ ] T051 `.../assistant/AssistantQualityTest.java`(가짜 모델·고정 fixture, CI) — 근거 ID 존재, 치환 숫자가 fixture 값과 같음, 토큰·식별자 비노출(도구 인자·이력 포함), 만료 토큰 모델 무호출, 모르는 도구·기간 밖 인자 실행 0회, 429/503 매핑
- [ ] T052 `docs/specs/assistant-api/evaluation.md` — 실제 모델로 30개를 돌려 두 명이 독립 채점(SC-001~SC-003, 도구 사용 여부). 표본·환경·모델명·날짜·라운드 수·토큰·지연 기록. 원문은 테스트 산출물에만
- [ ] T053 관문 판정 — SC-001~SC-004 충족 시에만 배포 설정에 `THORP_ASSISTANT_ENABLED_SCOPES=RESULT`. 미달이면 원인 기록, 플래그 유지

---

## Dependencies & Execution Order

- Phase 1 → Phase 2 → {Phase 3, Phase 4} 병렬 → Phase 6. Phase 5는 Phase 1 뒤 언제든(계약만 필요).
- Phase 6의 실제 모델 평가는 3·4·5가 모두 머지된 뒤다.

## Recommended Work Batches and Jira Tasks

| 묶음 | Jira Task 제목 | 브랜치 | SP | T번호 | 선행 |
|---|---|---|---|---|---|
| 1 | `[DOCS] 화면 맥락 도우미 ADR·spec 015·계약을 적는다` (S15P21E205-434) | `docs/434-assistant-spec` | 3 | T001~T005 | — |
| 2 | `[BE] 도우미 API 계약·기능 플래그·답변 검증기·도구 실행 틀 (모델 없이)` | `feat(be)/{N}-assistant-contract` | 3 | T010~T017 | 1 |
| 3 | `[BE] GMS Chat Completions 모델 어댑터 (도구 라운드 포함)` | `feat(be)/{N}-assistant-model-adapter` | 3 | T020~T023 | 2 |
| 4 | `[BE] 결과 근거 팩과 읽기 도구 다섯 개` | `feat(be)/{N}-assistant-result-evidence` | 3 | T030~T035 | 2 |
| 5 | `[FE] 질문하기 플로팅 버튼과 말풍선` | `feat(fe)/{N}-assistant-bubble` | 5 | T040~T046 | 1 |
| 6 | `[BE] 도우미 품질 표본·자동 검사·평가 문서` | `feat(be)/{N}-assistant-quality-gate` | 2 | T050~T053 | 3·4·5 |

티켓은 브랜치를 딸 때 만든다(`AGENTS.md`). Epic S15P21E205-30, Story S15P21E205-433 `relates to`.

## Notes

- 서버는 계산하지 않는다. 도구가 시계열에서 하는 일은 고르기다.
- 모델에 넘기는 것: 질문, 근거 팩, 도구 목록. 넘기지 않는 것: 토큰, `backtestId`, 계정, 화면 상태.
- 후속(1차와 무관): 워커가 `analytics.maxDrawdownWindow`를 담으면 T034와 005 `drawdownWindow.ts`를 지운다.

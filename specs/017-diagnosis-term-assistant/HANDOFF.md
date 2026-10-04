# 인수인계 — Spec 017 진단 화면 용어 도우미 (2026-10-02)

> 다음 작업자(사람·AI)가 다른 컴퓨터(Mac)와 다른 저장소(GitHub)에서 이어 가기 위한 상태 기록이다. 요구사항 원본은 [spec.md](spec.md), 설계는 [plan.md](plan.md), 실행 목록은 [tasks.md](tasks.md)다. 충돌하면 constitution → spec → plan → tasks 순으로 다시 확인한다. 이 문서는 상태만 적고 규칙을 새로 정하지 않는다.

## 0. 한눈에

| 항목 | 상태 |
|---|---|
| 무엇 | '투자 대상 고르기'(`/diagnosis/sectors`)·'전략 선택'(`/strategy`) 화면에 015 도우미 말풍선을 붙여, 사이트 용어·전략·설정의 뜻을 묻게 한다. 근거는 013 게시 용어·글, 003 등록표, 001 섹터 목록뿐이고 모델 일반 지식은 쓰지 않는다. |
| 문서 | spec·plan·research·data-model·contracts·quickstart·tasks·체크리스트 두 벌이 dev에 머지됐다(GitLab MR !365). |
| 구현 | 묶음 2(백엔드 확장 기반) 끝. 묶음 3·4·5 남음. |
| 플래그 | 꺼져 있다. 켜는 것은 묶음 5 품질 관문 뒤다. |

## 1. 작업 묶음 상태

| 묶음 | 브랜치 | T번호 | 상태 |
|---|---|---|---|
| 1 문서 | `docs(be)/017-assistant-term-spec` | T001~T006 | **머지됨**(MR !365) |
| 2 BE 확장 기반 | `feat(be)/017-assistant-page-kinds` | T010~T019 | **구현·AI 리뷰·수정 끝, 푸시됨.** 커밋 `50102825`(기능)·`d9bff231`(리뷰 수정). MR 생성·CI 결과·머지 여부는 확인 필요 |
| 3 BE 용어 근거·도구 | `feat(be)/017-assistant-term-evidence` (만들 것) | T030~T037 | 시작 전. 묶음 2 머지 뒤 |
| 4 FE 화면 연결 | `feat(fe)/017-assistant-diagnosis-pages` (만들 것) | T040~T046 | 시작 전. 문서만 있으면 묶음 2와 병렬 가능 |
| 5 품질 관문 | `feat(be)/017-assistant-term-quality-gate` (만들 것) | T050~T053 | 시작 전. 3·4 뒤 |

## 2. 묶음 2에서 실제로 들어간 것 (코드 위치)

- 화면 종류: `backend/.../assistant/application/result/PageKind.java` — `SECTOR_SELECTION`·`STRATEGY_SETTINGS`, `requiresPublicToken()`
- 질문 흐름: `.../application/service/AssistantQuestionService.java` — 토큰 필수/금지, 한도 몫 키(`token:`/`page:{kind}:{ip}`), 접두 문장(임시 등록표 → 예시 섹터 대응표), `relatedArticles`(최대 3), 로그(`draftStatus`·`status`·`rejectedBy`)
- 답 검증: `.../application/service/AnswerValidator.java` — `Policy.RESULT`(015 그대로) / `Policy.TERM`(인용 근거 원문 대조, 자리표시자 부분집합). `render(draft, facts, policy, reservedLength)` 하나뿐이다
- 도구 인자: `ToolArgument`(DATE·ENUM), `ToolSpec.dates(...)`, `ToolLoop.checkArguments`
- 모델 어댑터: `.../infrastructure/client/OpenAiChatCompletionsClient.java` — `Conversation.kind`로 `answer-ko.txt`/`term-ko.txt`를 고른다. enum 인자는 strict `enum`
- 응답: `AssistantAnswerResponse` — `evidence[].source`, `relatedArticles`. 015 예시 `docs/specs/assistant-api/examples/answer-*.json`에도 빈 값으로 넣었다
- 용어 프롬프트 첫 판: `backend/src/main/resources/assistant/prompts/term-ko.txt` (묶음 3 T035에서 실제 근거 모양에 맞춰 다듬는다)
- 설정: `application.yml`의 `thorp.assistant.limits.per-page-per-ip-per-minute`(5)·`term-initial-facts`(40)

**plan과 달라진 것**: 화면별 식별자 규칙을 `EvidenceBuilder.prepare`가 아니라 `PageKind.requiresPublicToken()`으로 갈랐다(plan "질문 흐름"에 반영됨). 그래서 진단 종류를 지원하는 빌더가 없으면 빈 근거로 `INSUFFICIENT_EVIDENCE`가 된다 — 임시 빌더는 없다.

## 3. 다음에 할 일 (순서대로)

1. **묶음 2 MR 마무리**: GitLab에서 MR이 열렸는지, CI(Docker 통합 테스트 포함)가 통과했는지 확인하고 머지한다. 로컬(Windows, Docker 꺼짐)에서는 통합 테스트 675개가 건너뛰어졌다 — `AssistantResultIT`가 CI에서 처음 돈다.
2. **묶음 4(FE)**는 지금 바로 시작할 수 있다. [tasks.md](tasks.md) T040~T046. 계약은 [contracts/term-assistant-api.md](contracts/term-assistant-api.md), 예시는 `docs/specs/assistant-api/examples/term-*.json`.
3. **묶음 3(BE)**은 묶음 2 머지 뒤. 핵심은 013 `PublishedTermRepository.findAllPublished()` 추가, `TermEvidenceBuilder`(처음 팩 맨 앞 "읽을 수 있는 항목" 근거 포함), 도구 셋, ArchUnit 규칙 12에 `learning` 추가.
4. **묶음 5**: 표본·평가 문서·관문. '전략 선택'은 등록표만으로 먼저 켤 수 있고, 섹터 이름 외부 전송 확인 전에는 `readSectorList` 없이 켠다.

## 4. 코드 밖에서 남은 일

- **013 운영 게시**: 저장소 시드의 용어 14개·글 9개는 전부 초안이고 문장이 비어 있다. 운영자가 게시해야 '투자 대상 고르기'를 켤 수 있다.
- **이용 조건 확인**: 섹터·ETF·기초 지수 **이름**을 외부 모델(GMS → OpenAI)로 보내는 것이 KRX 원천 이용 조건(constitution 원칙 X)에 걸리지 않는지 확인하고 `docs/adr/0008-external-llm-via-gms.md`에 덧붙인다(T053).
- **dev의 016 번호 중복**: `specs/016-account-settings`와 `specs/016-historical-news`가 둘 다 있다. 017과 무관하지만 담당자에게 알릴 것.
- **015 결과 화면 도우미**도 사람 채점(015 T053)이 남아 꺼져 있다(017 범위 밖).

## 5. 저장소를 GitHub로 옮길 때

- 지금 원격은 GitLab `https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21E205.git`이다. 옮길 때 `dev`와 진행 중 브랜치(`feat(be)/017-assistant-page-kinds`)를 함께 올려야 묶음 2 작업이 사라지지 않는다. 묶음 2가 GitLab에서 머지되기 전에 옮기면, GitHub에서 같은 브랜치로 PR을 다시 연다.
- CI는 Jenkins(`Jenkinsfile`)가 GitLab MR 이벤트로 돈다. GitHub로 옮기면 그 트리거가 없어진다 — CI를 무엇으로 돌릴지 따로 정해야 한다. `Jenkinsfile`의 "Merge with dev" 단계가 dev와 병합 충돌을 검사하므로, 옮긴 뒤에도 PR 전에 dev를 병합해 충돌을 먼저 해소하는 습관을 유지한다.
- MR 템플릿은 `.gitlab/merge_request_templates/Default.md`에 있다. GitHub에서는 `.github/pull_request_template.md`로 옮겨야 같은 본문 형식(작업 내용 요약 / 주요 변경 사항 / 테스트 / 리뷰 포인트)이 뜬다.
- 브랜치 이름의 괄호(`feat(be)/…`)는 GitHub에서도 쓸 수 있지만 셸에서는 따옴표로 감싸야 한다.

## 6. Mac에서 이어 갈 때

- 백엔드 검증: `cd backend && ./gradlew spotlessApply build`. **Docker를 켜 둘 것** — 꺼져 있으면 DB 통합 테스트가 조용히 skip된다(`THORP_REQUIRE_DOCKER=true`로 돌리면 실패로 바뀐다).
- 프론트 검증: `cd frontend && npm run lint && npm run build && npm test`. 새 `.test.ts`는 `frontend/vite.config.ts`의 `test.include` 목록에 맞춰야 실행된다(`src/api/*.test.ts`는 이미 포함).
- spec-kit: `.specify/feature.json`은 git이 추적하지 않는다. Mac에서 처음 `/speckit-*`를 쓰기 전에 내용을 `{"feature_directory":"specs/017-diagnosis-term-assistant"}`로 만든다. 스크립트는 `python3 .specify/scripts/python/...`.
- Windows에서만 있던 문제: `setup_tasks.py` 등이 cp949 출력에서 깨졌다(`PYTHONIOENCODING=utf-8`로 우회). Mac에서는 해당 없다.
- 파트별 규칙은 `backend/CLAUDE.md`·`frontend/CLAUDE.md`에 있다. 커밋 형식은 `feat(be): …`, 한 브랜치에 작업 묶음 하나(AGENTS.md).

## 7. 알아 두면 덜 헤매는 것

- **예시 JSON이 계약의 기준이다.** `AssistantControllerTest`·`AssistantControllerTermTest`·`AssistantResultIT`가 응답을 `docs/specs/assistant-api/examples/*.json`과 필드 단위로 대조한다. 응답 필드를 더하면 예시도 같이 고친다.
- **숫자 검사(계약 §2.4)의 경계**: 아라비아 숫자와 고유어 수사(겹친 꼴 포함)+수량 단위가 그 문장이 가리킨 근거 원문에 같은 표기로 있어야 한다. "두 가지"·"한 번"·서수·한자어 수사는 대상이 아니다. 단위가 한 칸 뒤에 오면 그 뒤가 조사·끝·한글 아닌 글자일 때만 단위로 읽는다. 바꿀 때는 `AnswerValidatorTermTest`의 표에 사례를 먼저 더한다.
- **Java 정규식 리터럴의 역슬래시**: 스크립트로 Java 소스를 고칠 때 `\s`가 `\\s`로 들어갔는지 꼭 확인한다(Java에서 `"\s"`는 공백 한 글자다 — 묶음 2 리뷰 수정 중 실제로 났던 실수).
- **AI 코드리뷰는 MR 전 필수**(AGENTS.md). 묶음 2는 `/code-review`로 9건을 찾아 모두 고쳤다.

## 8. 다음 세션 첫 지시 예시

> `specs/017-diagnosis-term-assistant/HANDOFF.md`를 읽고, 묶음 2 MR 상태를 확인한 뒤 묶음 4(FE, T040~T046)를 `feat(fe)/017-assistant-diagnosis-pages` 브랜치에서 시작해 줘.

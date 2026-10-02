# Quickstart: 진단 화면 용어 도우미 검증

구현 묶음(plan "Work Partition")이 끝날 때 무엇을 돌려 무엇을 보면 되는지 적는다. 계약은 [contracts/term-assistant-api.md](contracts/term-assistant-api.md), 타입은 [data-model.md](data-model.md)를 본다.

## 0. 준비

- 파트별 검증 명령은 각 CLAUDE.md를 따른다. backend는 `./gradlew spotlessApply build`, frontend는 `npm run lint && npm run build && npm test`(skipped 수 확인)다.
- 실제 모델 확인에만 GMS 키가 필요하다. 키는 로컬 `.env`의 `THORP_ASSISTANT_*`에 둔다. 자동 테스트는 키 없이 돈다.
- 로컬에서 화면을 볼 때 쓰는 설정:
  - `THORP_ASSISTANT_ENABLED_SCOPES=STRATEGY_SETTINGS,SECTOR_SELECTION`
  - 013 관리 화면(`/admin/learning`)에서 글 한두 개를 게시한다. 관리 계정은 `thorp.learning.admin-user-ids`에 있어야 한다.

## 1. 묶음 2 — 확장 기반 (모델 없이)

- `./gradlew test`를 돌린다. **015 assistant 테스트가 하나도 바뀌지 않고 통과**해야 한다. `RESULT` 응답에는 `relatedArticles: []`와 `evidence[].source: null`만 더해진다.
- 가짜 모델로 확인할 것:
  - `context:{kind:"STRATEGY_SETTINGS", publicToken:"…"}` → 400
  - 꺼진 종류 → 404
  - 화면 종류별 IP 한도를 넘김 → 429 `PER_PAGE`
- 검증기 표 테스트:
  - 근거 "최대 40%까지"를 인용한 part의 "40%" → 통과
  - 같은 part의 "50%" → 거절
  - 근거가 "20거래일"인데 답이 "20%" → 거절
  - 자리표시자 없는 `ANSWERED` part(`factIds` 있음) → 통과
  - `factIds` 빈 part → 거절
- 열거 인자 검증: 열거 밖 `articleId`, 낯선 인자 이름 → "사용할 수 없음".

## 2. 묶음 3 — 근거 팩과 도구

IT(Testcontainers)에서 글 둘을 게시하고 하나는 초안으로 둔다.

- 처음 팩에 게시 글의 용어만 있다. 초안 글의 용어·문장은 어느 근거에도 없다.
- `readArticle` 열거 집합은 게시 글 둘뿐이다. 초안 글 id로 부르면 "사용할 수 없음"이다.
- 요청 사이에 게시 취소하면 다음 요청의 처음 팩에서 빠진다(캐시 없음).
- `readArticle` 근거에 `hypothetical`·`example`·`sources` 내용이 없다.
- `readStrategy` 근거에 등록표 `range` 숫자가 따로 없고, `description` 원문만 있다.
- 모델에 간 요청 본문을 직렬화했을 때 `publicToken`·선택값 키(`weightTenths`·`selected` 등)가 0회 나온다(SC-004).
- `ArchitectureTest` 규칙 12가 `learning → assistant` 참조를 막는다.

## 3. 묶음 4 — 화면

`npm test`를 돌린다. 화면 테스트로 확인할 것:

- `SECTOR_SELECTION`·`STRATEGY_SETTINGS`가 켜졌을 때 두 화면에 `질문하기` 버튼이 있다. `RESULT`만 켜졌을 때는 없다.
- 말풍선 머리글 `용어 도우미`, 안내 "이 화면의 용어에 대해 질문해보세요.", 화면별 예시 질문이 보인다. 결과 화면 문구는 그대로다.
- `relatedArticles`가 있는 답 아래 `배우기: {title}` 연결이 `/learn/{articleId}`로 간다. 돌아오면 002·003 입력이 유지된다(`TermHelp`와 같은 `returnTo`).
- 투자 대상 고르기 → 전략 선택으로 넘어가면 이력이 비고, 진행 중 요청이 취소된다.

브라우저 확인(preview):

- 넓은 화면에서 버튼 끌기와 위치 기억은 015와 같다.
- 좁은 화면(≤600px) '전략 선택'에서 우측 하단 고정 버튼이 주 행동 버튼·하단 콘텐츠를 가리지 않는다(research R11). 가리면 기본 세로 위치만 바꾼다.

## 4. 묶음 5 — 품질 관문

- `term-quality-cases.json`: 화면 종류마다 30개(근거 있는 질문 20, 사이트에 없는 용어·초안 용어 5, 고르기·성과·입력값 판단 5). '전략 선택'은 013 게시 전이면 근거 있는 질문을 등록표 항목으로 채운다.
- 화면 예시 질문 전부를 013 게시 콘텐츠 0개 상태로 돌려 100% `ANSWERED`이고 근거가 등록표·섹터 목록뿐인지 확인한다(SC-007).
- 자동 검사(가짜 모델 대신 기록된 초안 재생): 상태 일치, 숫자 규칙, 근거 출처.
- 실제 모델(`ASSISTANT_LIVE_TEST=true`)로 표본을 돌린다. 결과는 `docs/specs/assistant-api/evaluation-017.md`에 적는다. 함께 적을 것은 등록표 버전, 게시 글·용어 수, 모델명, 지연·토큰(SC-005)이다.
- 사람 두 명이 각자 채점(SC-002)한 뒤 그 화면 종류를 `THORP_ASSISTANT_ENABLED_SCOPES`에 넣는다(spec FR-014).

## 5. 회귀

기능을 끈 상태(`THORP_ASSISTANT_ENABLED_SCOPES` 빈 값)에서 002·003·005·013 화면 테스트가 전부 그대로 통과해야 한다(SC-006).

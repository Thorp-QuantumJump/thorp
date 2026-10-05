# Implementation Plan: 배우기

**Spec 디렉터리**: `013-learning-pages` | **Date**: 2026-09-16 · 2판 2026-10-05 | **Spec**: [spec.md](spec.md)

> 2판 plan 문서 작업의 브랜치는 `docs(fe)/spec-013-plan`(origin/dev `e120010a`에서 땀)이다. 2026-09-29부터 Jira 티켓을 쓰지 않으므로 구현 묶음의 브랜치는 `{type}({position})/{description}` 형식으로 착수할 때 만든다.
> 1판(2026-09-16)의 묶음 B01~B06은 구현·병합되었다. 1판 실행 결과와 남은 수동 확인(T097)은 [validation.md](validation.md)에 있다.

**Input**: `specs/013-learning-pages/spec.md` (Clarifications 2026-09-16·2026-10-02·2026-10-05, FR-001~FR-031, SC-001~SC-017)

## Summary

완전 입문자가 전략 진단에 필요한 개념을 글로 익히고, 예시가 있는 글에서는 그 예시로 곧바로 섹터·비중 선택(002)에 들어가게 하는 기능이다. 진단 화면과 013 도움말을 붙인 화면의 용어 도움말은 같은 설명을 단일 원본에서 받는다.

**1판(구현됨)**: `learning` 도메인이 글·용어 설명을 PostgreSQL에 보관하고, 초안 작업본과 불변 게시 판을 나누며, 게시할 때 서버가 아홉 가지 검사를 모두 돌린다. 본문은 구조화 블록이고, 관리 권한은 014 로그인 세션 위의 설정 허용 목록이다. 배우기 목록·글 화면, 공용 `TermHelp`, 관리 화면이 있다.

**2판(이 plan)**: spec 2026-10-02~05 개정을 구현한다.

- **단계와 단계 공개**: 고정 단계 여섯을 테이블로 두고, 글마다 초안 배치와 공개 배치를 나눈다. 운영자는 '단계 공개' 한 번으로 그 단계의 글을 모두 검사해 함께 게시하며, 하나라도 걸리면 아무것도 바꾸지 않는다. 단계는 번호 순서로만 공개하고 되돌리지 않는다. 공개 안내와 변경 기록은 같은 트랜잭션에서 쓰는 추가 전용 기록에서 나온다([research](research.md) R14·R15·R23·R27).
- **새 기준 글(V2)과 이전 판(V1)**: 초안에 형식을 명시한다. V2 글은 블록 열 종류와 글자 조각(용어 언급·출처 표시)으로 쓰고, v2 규칙 파일의 열일곱 검사를 받는다. 기존 아홉 글은 다시 쓰기 전까지 V1 이전 판으로 남아 v1 검사로 문장만 고칠 수 있다. 확인 문제는 서버가 준 고정 식별자를 가지며, 게시할 때 서버가 같은 문제인지 판정한다(R16~R21).
- **나의 학습**: 로그인 사용자의 기록을 사용자 × 글, 첫 답, 경험치 장부, 받은 배지의 네 테이블에 둔다. DB 유일 제약이 '첫 답 한 번'과 '같은 일로 경험치 한 번'을 지킨다. 배지 31개·레벨·'준비 중' 판정은 순수 도메인 함수이고, 학습 행동 요청과 '나의 학습'을 여는 요청이 정산한다. '학습 초기화'는 회원정보 화면에서 네 테이블의 그 사용자 행을 지운다(R28~R31, R36).
- **화면**: 글 화면은 새 요소·처음 나오는 자리의 다른 이름과 도움말·비로그인 확인 문제·완료 판정과 읽은 위치·완료 카드를 갖는다. 목록은 여정 지도(커리큘럼 보기)와 목록 보기를 탭 단위로 기억해 전환한다. '나의 학습' 화면(`/learn/me`)은 레벨 표장·레벨 길·배지 메달을 목업([design-mockup.html](design-mockup.html))대로 인라인 SVG로 그린다. 관리 화면은 V2 편집기·원고 가져오기·미리 보기·단계 패널을 갖는다(R18, R26, R32~R38).
- **호환과 공개 순서**: 2판의 공개 API 변경은 필드 추가뿐이고(화면이 옮겨 간 옛 목록 경로 하나만 마지막에 지운다), 첫 단계를 공개하기 전에는 기존 글이 지금 모습 그대로 보이며 나의 학습이 꺼져 있다. 그래서 묶음을 차례로 병합해도 운영 화면이 깨지지 않는다. 첫 단계 공개는 모든 코드 묶음이 배포되고 1단계 원고가 검토된 뒤 한다("첫 단계 공개 조건").

결정 근거는 [research.md](research.md) R1~R40에 있다.

## Technical Context

**Language/Version**: Java 21 (backend), TypeScript 5.8 / React 19 (frontend)

**Primary Dependencies**: Spring Boot 3.5(Web·Validation·Data JPA·Security·Session JDBC), Flyway, Jackson(YAML 포함). React Router 7, Axios, `radix-ui`, `motion`, `@visx/scale`, Vitest, Testing Library. **새 라이브러리를 추가하지 않는다.** 도식·메달·여정 지도는 인라인 SVG, 움직임은 이미 쓰는 `motion`과 CSS다([research](research.md) R38).

**Storage**: PostgreSQL. 2판에서 새 테이블 여섯(`learning_stage`·`learning_release_log`·`learning_progress`·`learning_answer`·`learning_exp_entry`·`learning_badge_award`)을 만들고 테이블 넷(`learning_article`·`learning_term`·`learning_article_revision`·`learning_published_term`)에 열을 더한다([data-model](data-model.md) §3·§11). 게시 전 검사 규칙은 자원 파일 `learning/publish-checks-v1.yml`(이전 판)과 `publish-checks-v2.yml`(새 기준)이다. 브라우저에는 화면 상태 두 키(`thorp.learning.view`·`thorp.learning.dismissedNotices`, 탭 단위)와 관리 화면의 저장하지 않은 편집 내용만 `sessionStorage`에 둔다. 학습 기록은 브라우저에 쓰지 않는다(FR-020).

**Testing**: backend `./gradlew spotlessApply build`(ArchUnit·`PostgresTestSupport` 통합 테스트·계약 픽스처 대조), frontend `npm run lint && npm run build && npm test`. 계약 픽스처는 `docs/specs/learning-api/examples/`에 두고 통합 테스트가 실제 응답과 대조한다. `vite.config.ts`의 `test.include`에 `src/components/learning/**/*.test.ts`를 더한다. 시나리오 검증은 [quickstart.md](quickstart.md)다.

**Target Platform**: 웹 브라우저(데스크톱 우선, 375px에서 가로 스크롤 없음) + 기존 Spring Boot 서버

**Project Type**: 웹 애플리케이션 (frontend + backend)

**Performance Goals**: 정하지 않는다. 글 27편, 확인 문제 54~81개, 배지 31개이고, `GET /learning/me`의 정산은 한 사용자의 행 수백 개 이하를 읽는다. 읽은 위치는 글을 떠날 때 한 번 보낸다. 조회가 체감될 만큼 느려지면 그때 실측해 정한다(원칙 IX).

**Constraints**:
- 본문은 일반 텍스트 블록과 글자 조각만 저장하고 화면은 텍스트 노드로만 그린다(R3, R17).
- 첫 단계를 공개하기 전에는 기존 글이 지금 모습 그대로 보이고 나의 학습이 꺼져 있다(FR-031, R15).
- 공개 API 변경은 필드 추가뿐이다. LEGACY 판의 블록은 9월 판 모양으로 나간다([contracts/README](contracts/README.md)). 예외는 화면이 커리큘럼 조회로 옮긴 뒤 B16이 지우는 `GET /learning/articles` 하나다.
- 로그인 사용자의 확인 문제는 기록이 저장된 뒤에만 정답·풀이를 보인다. 실패를 완료·'안 읽음'·레벨 1로 꾸미지 않는다(FR-020, FR-022).
- 화면은 실패 시 조용히 재시도하지 않는다(FR-010, FR-020).
- 움직임은 `prefers-reduced-motion: no-preference`에서만 더하고 1초 안에 끝난다. 배우기 화면에 자물쇠·잠금·예고 어휘와 오르내림의 빨강·파랑을 쓰지 않는다(FR-030, FR-031, [design-notes](design-notes.md) §4·§5).
- 관리 권한은 014 역할이 생기기 전까지 설정 허용 목록이다(R5).
- 예시 비중 단위는 002 계약과 같은 0.1%p 정수다([data-model](data-model.md) §9).
- 로컬 프로필은 Flyway가 꺼져 있어 초기 자리가 생기지 않는다. 확인 방법은 [quickstart](quickstart.md) §0이다.

**Scale/Scope**: 단계 여섯, 글 27편(기존 아홉 + 새 18편 자리), 배지 31개, 관리 사용자 몇 명. 공개 경로 셋(`/learn`, `/learn/me`, `/learn/:articleId`)과 회원정보 화면(`/account/profile`)의 '학습 기록' 구역, 관리 경로 둘(`/admin/learning`에 단계 패널, `/admin/learning/:articleId`). API는 공개 넷, 나의 학습 일곱, 관리 열하나다.

`NEEDS CLARIFICATION`으로 남은 항목은 없다. spec이 plan으로 넘긴 값(읽기 시간 계산과 허용 오차, 최소 머문 시간, 보기 수 상한, 도식·표 상한, 처음 나오는 자리, 확인 필요 기간, 넘기기 처리, 이전 판 수정 중 새 원고 위치, 저장 구조)은 R16~R40에서 정했다.

## Constitution Check

*GATE: Phase 0 연구 전에 통과해야 하고, Phase 1 설계 후 다시 확인한다.* 기준은 constitution v6.0.0(승인)이며, 미승인 초안 6.1.0·6.2.0·3.5.0이 걸리는 곳은 따로 적는다.

| 원칙·절 | 이 기능에서의 의미 | 판정 |
|---|---|---|
| I. 중심 약속은 전략 진단이다 | 배우기는 보조다. 학습을 요구하지 않고(FR-011), 기록·경험치·배지는 배우기 안의 표시다. 진단·백테스트·탐색 행동에는 보상하지 않는다(FR-028). 기록·조회 실패가 진단 진입과 입력을 막지 않는다 | 통과 |
| II. 첫 화면은 단순하게 | 글 화면의 행동 버튼은 '이 예시로 전략 진단하기' 하나(예시가 있는 글만)다. 돌아가기·완료 카드·확인 문제는 보조 요소다. 화려함 상한(빛 두 곳, 1초 움직임)을 디자인 메모가 정했다 | 통과 |
| III. 진단이지 추천이 아니다 | 금지 표현 검사(v1·v2), 예시 비권고 표시, 레벨·배지 이름에 실력·수익·순위 낱말 금지, 사용자 간 비교 없음 | 통과 — 패턴 검사의 한계는 R7·R19 |
| IV. 비교는 같은 조건에서만 | 글에 실제 결과를 싣지 않는다(FR-005) | 해당 없음 |
| V. 당시에 알 수 있던 정보만 | 시계열을 다루지 않는다 | 해당 없음 |
| VI. 없는 결과를 만들어내지 않는다 | '준비 중'(팀)과 '아직 하지 않음'(사용자)을 글자·선 모양으로 가른다. 닿을 수 없는 레벨을 정직하게 보인다. 저장 뒤에만 정답을 보인다. 실패를 '안 읽음'·레벨 1로 꾸미지 않는다. 예시가 없는 V2 판의 대응표 버전을 `NOT_CHECKED`로 적는다. 넘기기가 옛 글 초안에서 지우는 용어 행은 초안 번호를 올려 관리 화면에 드러낸다(R24) | 통과 |
| VII. 탐색과 검증을 구분한다 | 해당 없음 | 해당 없음 |
| VIII. 재현할 수 있게 기록한다 | 판은 불변이고 `edition`·검사 규칙 버전·대응표 버전·게시자를 남긴다. 변경 기록과 경험치 장부는 추가만 한다. 단계 공개자와 공개일을 남긴다 | 통과 |
| IX. 실행 한도는 실측으로 정한다 | 백테스트를 실행하지 않는다 | 해당 없음 |
| X. 자료는 이용 조건 안에서만 | 글은 KRX 시세를 쓰지 않는다. 외부 인용은 '(*출처)'로 출처를 싣고 내용을 보증하지 않는다(FR-007) | 통과 |
| "하지 않는 것" | 사용자 간 랭킹·리더보드 없음, 배우기 밖 알림·학습 알림·연속 출석 없음, 숙련 퀀트 연구 화면 없음 | 통과 |
| 기록·개인정보·보관 | 나의 학습은 사용자 식별자로만 묶고 이메일·닉네임을 담지 않는다. 집계·관리 화면 노출 없음(FR-020). '학습 초기화'로 사용자가 지운다. 보관 기간은 PD-13을 따르고 무기한 보관을 전제하지 않는다. 비로그인 기록은 없다. 탭 저장소에는 화면 상태 둘만 두며 이는 6.1.0 초안(탭을 닫으면 지워짐)과도 맞다 | 통과 |
| 화면 구조(6.2.0 초안, 미승인) | 예시를 글마다 고른다(FR-003)는 미승인 초안을 앞서 적용한다. 반려되면 `exampleRequired: true`인 v3 규칙을 더하고 FR-003을 되돌린다(R19) | 조건부 통과 |
| 스키마 변경 규칙 | 묶음마다 마이그레이션·매핑·검증을 같은 MR에 담는다. 적용된 `V20260917101000`을 고치지 않는다. 기존 행에 NOT NULL을 거는 `stage_id`는 같은 마이그레이션이 먼저 채우고, 운영 DB의 글 목록을 배포 전에 확인한다(R15). 유일 제약 교체(R24)는 데이터를 지우지 않는다. 이 사실과 영향 범위를 B07·B08 MR 본문에 적는다 | 통과 |
| 작업 묶음·Git | 묶음 열, 묶음마다 목적 하나·독립 검증·미완성 task 없음. 브랜치 `{type}({position})/{description}` | 통과 |
| 완료의 정의 | 구현 완료와 사용성 확인(SC-005)을 구분해 보고한다. 패턴 검사 통과를 SC-002 달성으로 보고하지 않는다 | 통과 |
| 합의되지 않은 영역(인증·인가, API 형식, 입력 검증) | 선택의 근거를 R33과 [contracts](contracts/README.md)에 남겼다 | 통과 |

### 게이트 결과 (Phase 0 전)

차단 항목은 없다. 6.2.0 초안에 기대는 예시 선택은 규칙 파일 값 하나로 되돌릴 수 있게 설계한다. 아래 교차 의존은 013 구현을 막지 않는다.

## 교차 의존

013 혼자 닫을 수 없는 항목이다. **이 plan은 다른 spec 문서를 고치지 않는다.** 각 담당이 판단하고 별도 MR로 한다(constitution "작업 묶음").

| 대상 | 필요한 일 | 담당 | 막는 범위 |
|---|---|---|---|
| [014 사용자 인증](../014-user-auth/spec.md) | (가) Out of Scope의 '역할별 권한 관리와 관리자 계정'을 범위로 옮긴다(1판부터). (나) Assumptions의 "'내 기록' 저장·재조회만 식별된 사용자 단위"를 '내 기록과 배우기의 나의 학습'으로 고치고, 배우기 로그인 연결과 배우기 안 재로그인 복귀를 적는다(spec 013 Assumptions). (다) 회원 탈퇴를 상태 전환으로 만들면 학습 초기화를 함께 부른다(R28) | 014 담당 | 막지 않는다. 허용 목록과 `useLoginReturn`으로 동작한다 |
| [specs/README.md](../README.md) 공통 가정 | 위 (나)와 같은 문장 | 문서 담당 | 막지 않는다 |
| constitution 6.1.0·6.2.0 초안 | 전체 회의 승인 또는 반려 | 팀 | 6.2.0 반려 시 v3 규칙과 FR-003 되돌림 |
| [003 전략 선택](../003-strategy-settings/spec.md) | 설정 항목 네 개의 축을 예약 `termId`(`lookback-period`·`skip-recent-period`·`selection-method`·`weight-cap`)로 바꾸고, `TermHelp`의 `fallbackMeaning`·`articleLinkLabel`을 쓴다. '섹터당 최대 비중'은 지금 `weight`를 가리킨다([term-help-ui](contracts/term-help-ui.md) §5) | 003 담당 | 막지 않는다. B10 뒤에 할 수 있다 |
| [002](../002-sector-weight-selection/spec.md)·[005](../005-result-comparison/spec.md) | 도움말을 붙일 용어와 자리를 정한다(1판부터). 005는 013 US3 시나리오 4가 전제한다 | 각 담당 | 각 화면의 도움말만 |
| [007](../007-auto-parameter-search/spec.md)·[009](../009-market-flow-overview/spec.md)·[010](../010-economic-regime-search/spec.md)·[011](../011-regime-strategy-link/spec.md) | 013 도움말을 붙일지 정한다. 붙이면 같은 용어를 화면에서 따로 설명하지 않는다. 돌아가기 문구는 B10의 표에 미리 둔다 | 각 담당 | 막지 않는다 |
| [001 대표 ETF 자료](../001-etf-market-data/spec.md) | 분배금 반영 같은 Thorp 동작을 설명하는 원고는 쓰기 전에 실제 적재 상태를 확인한다(FR-027) | 원고 작성자 | 그 글의 원고만 |
| [006](../006-my-records/spec.md) PD-13 | 보관 기간 확정 | 팀 | 막지 않는다. 확정 전에는 시간 기반 자동 삭제를 두지 않는다(FR-025) |
| PD-16 | 27편 출발안 확정 | 팀 | 막지 않는다. 출발안으로 개발한다 |
| PD-14 | SC-005 사용성 관찰(2판 확인 질문 둘 포함) | 팀 | 구현 완료와 따로 보고한다 |
| 015 상황 도우미 | 떠 있는 '이어서 읽기'와 도우미 단추가 겹치는지 | — | 없다. 도우미 단추는 지금 결과 화면에만 있다 |

## 게이트 재확인 (Phase 1 설계 후)

설계를 마친 뒤 다시 확인했고 새로 생긴 constitution 위반은 없다. 아래는 설계가 원칙을 지키는 방식이다.

| 원칙 | 설계에서 지키는 방식 |
|---|---|
| I | 나의 학습 계약은 배우기 행동만 받는다([learning-me-api](contracts/learning-me-api.md) §9). 기록 실패는 글과 상단 메뉴의 진단 진입을 막지 않는다(§0). 경험치·배지 알림은 배우기 안에서만 보인다 |
| II | 글 화면의 행동 버튼은 하나다. 완료 카드·확인 문제·돌아가기는 보조 요소다([term-help-ui](contracts/term-help-ui.md) §3·§4) |
| III | v2 검사의 금지 표현 예외는 요소 종류로만 정하고 화면이 '오해'·'틀린 보기' 표시를 붙인다([data-model](data-model.md) §8-2). 배지 이름은 `BadgeCatalog`에 고정하고 테스트로 금지 낱말을 확인한다 |
| VI | `PREPARING`과 `NOT_YET`을 계약에서 다른 값으로 둔다([data-model](data-model.md) §12-4). 레벨은 `visibleMax`·`unreachableFrom`으로 닿을 수 있는 데까지만 보인다. 첫 답은 저장 뒤 응답한다. 같은 문제 판정은 서버가 하고 결과를 운영자에게 알린다(`renamedQuestions`) |
| VIII | 판·이벤트·변경 기록·장부·답·배지 저장소에 수정 메서드를 두지 않는다. 사용자 기록의 삭제는 `deleteAllForUser` 하나다([data-model](data-model.md) §14) |
| 기록·개인정보 | 나의 학습 테이블은 그 사용자의 요청만 읽는다. 관리 API와 집계 조회가 없다. `users` 외래키는 `ON DELETE CASCADE`다 |
| 스키마 변경 규칙 | 묶음별 마이그레이션 셋(B07·B08·B09)은 서로 다른 테이블·열을 맡는다. 각 묶음의 통합 테스트가 빈 DB 적용과 매핑 `validate`를 확인한다 |

**의존 방향**: 1판 규칙(`sector`가 `learning`을 참조하지 않는다)을 유지한다. `learning`은 현재 사용자를 자기 `presentation/support`에서 읽고 `backtest`의 `CurrentRequester`를 참조하지 않는다(R33). 이 관례를 ArchUnit 규칙("`learning`은 `backtest`를 참조하지 않는다")으로 B09에서 더한다. `users` 테이블은 외래키로만 가리키고 `account` 패키지를 참조하지 않는다.

## Project Structure

### Documentation (this feature)

```text
specs/013-learning-pages/
├── plan.md                 # 이 파일
├── research.md             # Phase 0 산출물 (R1~R13 1판, R14~R40 2판)
├── data-model.md           # Phase 1 산출물
├── quickstart.md           # Phase 1 산출물 (§2~§5 1판 회귀, §6~§13 2판)
├── validation.md           # 1판(B06) 실행 결과. B15가 2판 절을 더한다
├── design-notes.md         # 단계 공개 화면 디자인 메모 (2026-10-05)
├── design-mockup.html      # 고른 목업
├── gamification-research.md
├── contracts/              # Phase 1 산출물
│   ├── README.md                  # 소유·비소유 계약, 오류 코드, 배포 순서 호환
│   ├── learning-public-api.md     # 커리큘럼·글·용어 설명 공개 조회
│   ├── learning-me-api.md         # 나의 학습 (2판)
│   ├── learning-admin-api.md      # 관리 권한·초안·게시·미리 보기·단계 공개
│   └── term-help-ui.md            # 공용 도움말, 돌아가기 표, 예시 진입, 설정 항목 도움말
├── checklists/
│   └── requirements.md
└── tasks.md                # /speckit-tasks 산출물 — 이 명령이 만들지 않는다. 1판 T001~T097이 있고 2판 task는 /speckit-tasks가 더한다
```

원고는 코드가 아니라 콘텐츠이므로 spec 폴더가 아니라 `docs/learning-content/`에 둔다(R25·R26).

```text
docs/learning-content/
├── README.md               # 원고 형식과 편집 문법 (B14)
├── current/{articleId}.json # 게시 중인 내용과 같은 원고 (원고 MR)
└── next/{articleId}.json    # 단계 공개를 기다리는 새 기준 원고 (원고 MR)
```

### Source Code (repository root)

1판 구조에 더하거나 바꾸는 것만 적는다. 표시가 없는 줄은 새 파일이다.

```text
backend/src/main/java/com/ssafy/thorp/learning/
├── presentation/
│   ├── controller/      # LearningController(+커리큘럼), LearningAdminController(+미리 보기),
│   │                    # LearningStageAdminController, LearnerController(/learning/me)
│   ├── request/         # V2 블록·글자 조각·확인 문제·용어 요청, StageReleaseRequest,
│   │                    # PositionRequest, AnswerRequest
│   ├── response/        # CurriculumResponse, LearnerOverviewResponse 등
│   └── support/         # LearnerAccess, UniqueConstraintViolations(부분 색인 이름)
├── application/
│   ├── service/         # LearningQueryService(+커리큘럼·V2 글), LearningAdminService(+형식·게시 방식·
│   │                    # 넘기기·미리 보기), LearningStageAdminService, LearnerService
│   ├── repository/      # LearningStageRepository, ReleaseLogRepository, LearnerProgressRepository,
│   │                    # LearnerAnswerRepository, ExpLedgerRepository, BadgeAwardRepository
│   └── port/            # PublishCheckRulesPort(+v2)
├── domain/
│   ├── model/           # data-model §14의 2판 타입
│   └── vo/              # StageKey, QuestionId, ChoiceId, BadgeKey
└── infrastructure/
    ├── persistence/     # 새 JPA 엔티티·매퍼, 장부·답·배지의 ON CONFLICT 저장(JdbcTemplate)
    ├── rules/           # YamlPublishCheckRulesLoader(+v2)
    └── config/          # LearningProperties(institution-note-review-days)

backend/src/main/java/com/ssafy/thorp/common/
├── config/SecurityConfig.java      # /learning/me/** 를 authenticated()로 묶는 한 줄
└── exception/ErrorCode.java        # spec 013 블록에 2판 코드 열셋

backend/src/main/resources/
├── db/migration/V{B07 작성 시각}__learning_stages_and_v2_content.sql
├── db/migration/V{B08 작성 시각}__learning_stage_release.sql
├── db/migration/V{B09 작성 시각}__learning_records.sql
└── learning/publish-checks-v2.yml

backend/src/test/java/com/ssafy/thorp/ArchitectureTest.java   # 규칙 하나 더함(learning → backtest 금지)
docs/specs/learning-api/examples/   # 2판 픽스처(커리큘럼·V2 글·이전 판 글·나의 학습·단계 공개 등)

frontend/src/
├── types/learning.ts(+), types/learningMe.ts, types/learningAdmin.ts(+)
├── api/learning.ts(+커리큘럼), api/learningMe.ts(keepalive 위치 기록 포함), api/learningAdmin.ts(+단계·미리 보기)
├── pages/LearningListPage.tsx(다시 씀)       # /learn
├── pages/LearningMePage.tsx                  # /learn/me
├── pages/LearningArticlePage.tsx(다시 씀)    # /learn/:articleId
├── pages/AccountProfilePage.tsx(+구역)       # /account/profile 의 '학습 기록' 구역
├── pages/AdminLearningListPage.tsx(+단계 패널), pages/AdminLearningEditPage.tsx(+V2)
├── App.tsx(+/learn/me)
└── components/learning/
    ├── article/         # ArticleBody, RichText, firstOccurrences.ts, KeySummary, Misconception,
    │                    # ComparisonTable, HypotheticalDiagram, InstitutionNote, TermDefinition,
    │                    # EditionNotice, QuizPanel, CompletionCard, ResumeLine,
    │                    # useArticleCompletion.ts, useReadingPosition.ts
    ├── curriculum/      # JourneyMap, StageBand, StageLandscape, ArticleNode, ListView, ViewToggle,
    │                    # ReleaseNotice, ReleaseBoundary, PreviousEditionCards, ChangeLog,
    │                    # viewStorage.ts, noticeStorage.ts
    ├── me/              # SummaryPanel, FloatingContinue, LevelEmblem, LevelPath, StageMedal,
    │                    # ArticleStamp, BadgeMedal, BadgeFamilies, GuestSample,
    │                    # LearningResetSection, useLearnerOverview.ts
    ├── returnTargets.ts, ReturnLink.tsx      # ReturnToDiagnosisLink를 대신한다(R35)
    ├── TermHelp.tsx(+fallbackMeaning·articleLinkLabel, returnTo 선택)
    └── admin/           # BlockEditor(+V2), RichTextField, richTextSyntax.ts, QuizEditor,
                         # TermListEditor(+다른 이름·세 문장·넘겨받기), PlacementEditor,
                         # ArticlePreview, ManuscriptTransfer, StageReleasePanel,
                         # PublishCheckResultList(+v2·단계)

frontend/vite.config.ts             # test.include에 src/components/learning/**/*.test.ts
frontend/CLAUDE.md                  # 배우기 저장 키 두 개와 '학습 기록은 저장소에 쓰지 않는다'
```

**구조 선택 근거**: 1판과 같다. 백엔드는 도메인 패키지 안에서 `presentation → application → domain/infrastructure` 계층을 ArchUnit으로 강제한다([ADR-0001](../../docs/adr/0001-be-layered-package-architecture.md)). 단계·나의 학습도 013이 소유하므로 `learning` 안에 둔다. 나의 학습을 별도 도메인 패키지로 떼지 않는 이유는 공개된 글·문제·용어의 범위(§12-1)를 콘텐츠 저장소와 같은 트랜잭션에서 읽어야 하기 때문이다. 프런트엔드는 화면 단위(`article`·`curriculum`·`me`·`admin`)로 하위 폴더를 나눈다. 1판의 평평한 `components/learning`은 부품이 열 개 남짓이었으나 2판에서 마흔 개를 넘는다.

**관리 권한 판정을 `common`에 두지 않는다**: 1판과 같다. 014가 역할을 제공하면 그때 공통 위치로 옮길지 정한다.

## 작업 묶음 (Branch·MR 단위)

constitution "작업 묶음"이 **작업 묶음 = 1 Branch = 1 MR**이고 **묶음 구성은 plan을 시작할 때 정한다**고 요구한다. `tasks.md`의 개별 task는 실행 지시의 최소 단위이며 브랜치 단위가 아니다. 2판의 브랜치·MR 단위는 아래 묶음 열이다. 1판 B01~B06은 완료되었다.

| 묶음 | MR 제목 | 브랜치 | 담는 것 | spec | 선행 |
|---|---|---|---|---|---|
| B07 | `feat(be): 배우기 새 기준 글과 확인 문제를 보관하고 게시한다` | `feat(be)/learning-v2-content` | 마이그레이션(단계 테이블과 여섯 단계, 글의 형식·배치·확인 문제 열, 용어 v2 열, 판 `edition`, 색인 `aliases`, 27편 배치), V2 블록·글자 조각·확인 문제 도메인, 식별자 주기와 같은 문제 판정, v2 검사·규칙 파일·읽기 시간, 형식별 저장 검사, 글마다 게시 방식(이전 판 고치기·공개 단계 게시), V2 글 응답과 1판 응답의 추가 필드, 미리 보기, 관리 목록 2판 필드('확인 필요' 포함), 오류 코드, 픽스처, 통합 테스트 | US1·US5 서버, FR-002·FR-005~FR-008·FR-016·FR-017·FR-022·FR-026 | 1판 |
| B08 | `feat(be): 배우기 단계를 차례로 공개하고 변경 기록을 남긴다` | `feat(be)/learning-stage-release` | 마이그레이션(변경 기록 테이블, `first_released_at`, 넘겨받기 열과 부분 유일 색인), 단계 목록·미리 검사·공개, 넘기기, `ARTICLE_ADDED` 기록, 커리큘럼 API(공개 안내·변경 기록·이전 판·첫 단계 공개 전 목록), 오류 코드, 픽스처, 통합 테스트 | US5 시나리오 7~9, FR-001·FR-023·FR-031 | B07 |
| B09 | `feat(be): 로그인 사용자의 나의 학습을 기록하고 경험치와 배지를 계산한다` | `feat(be)/learning-records` | 마이그레이션(나의 학습 네 테이블), `/learning/me` 일곱 엔드포인트, `SecurityConfig` 한 줄, `LearnerAccess`, 장부·정답 몫 상한·레벨·닿을 수 있는 레벨·배지 31개·'준비 중' 판정·정산, 학습 초기화, ArchUnit 규칙 하나, 오류 코드, 픽스처, 통합 테스트(동시 답 포함) | US6 서버, FR-020~FR-022·FR-024·FR-025·FR-028~FR-030 | B08 |
| B10 | `feat(fe): 배우기 글 화면에 새 요소와 비로그인 확인 문제를 보인다` | `feat(fe)/learning-article-v2` | 글 응답 타입·API, 블록·글자 조각 그리기, 처음 나오는 자리, 가상 도식, 제도 안내, 용어 세 문장과 ⚠️, 글 안 도움말, 이전 판 머리·끝, 비로그인 확인 문제, 읽기 시간, 돌아가기 표와 `ReturnLink`, `TermHelp`의 2판 prop, 1판 화면 테스트 갱신, `vite.config.ts` `include` | US1·US3, FR-005~FR-010·FR-019·FR-022(비로그인)·FR-026 | B07 계약 |
| B11 | `feat(fe): 배우기 목록을 단계별 여정 지도와 목록 보기로 보인다` | `feat(fe)/learning-curriculum` | 커리큘럼 API, 첫 단계 공개 전 목록(9월 판 모습, 보기 전환·나의 학습 요소 없음), 여정 지도(풍경·길·노드), 목록 보기(표·좁은 화면 목록), 보기 기억, 공개 경계·준비 중·다시 준비 중·이전 판 카드, 공개 안내와 [닫기], 변경 기록, 비로그인 한 줄과 견본, `frontend/CLAUDE.md` 저장 키 | US1, FR-001·FR-023·FR-031 | B08 계약, B10 |
| B12 | `feat(fe): 글 끝에서 완료와 확인 문제를 기록한다` | `feat(fe)/learning-article-records` | 나의 학습 API(글 기록·위치·완료·첫 답·다시 풀기), 완료 판정, 읽은 위치와 keepalive, 완료 카드와 마지막 글의 끝 문구(`continueReading`), 글을 연 채 로그인 상태가 바뀔 때 글 끝·타이머 초기화, 저장 뒤 공개하는 확인 문제, 다시 풀기, '62%까지 읽었습니다' 줄, 기록 실패와 로그인 상태 처리, 배우기 로그인 복귀 | US6 시나리오 1~3·5·9·10, FR-021·FR-022 | B09 계약, B10 |
| B13 | `feat(fe): 나의 학습 화면과 학습 초기화를 만든다` | `feat(fe)/learning-journal` | `GET /learning/me` 훅, 첫 단계 공개 전 숨김(`/learn/me`는 `/learn`으로), 요약 패널, 목록의 글별 상태·단계 완료 수, 떠 있는 '이어서 읽기', `/learn/me`(레벨 카드·표장·레벨 길·숫자 줄·단계 진행·익힌 용어·배지 메달·준비 중 묶음·'새'), 비로그인 견본 화면, 회원정보의 '학습 초기화' | US6 시나리오 4·6·8·11, FR-020·FR-024·FR-025·FR-029·FR-030 | B09 계약, B11, B12 |
| B14 | `feat(fe): 관리 화면에서 새 기준 글을 편집하고 단계를 공개한다` | `feat(fe)/learning-admin-v2` | 관리 타입·API 2판, 형식·배치 편집, V2 블록 편집기와 편집 문법, 확인 문제 편집기, 용어 v2와 넘겨받기, 미리 보기, 원고 가져오기·내보내기, `docs/learning-content/README.md`, 단계 패널(미리 검사·공개 확인), 목록 2판 열 | US5, FR-016·FR-017·FR-031 | B07·B08 계약, B10 |
| B16 | `refactor(be): 화면이 쓰지 않는 배우기 글 목록 경로를 지운다` | `refactor(be)/learning-articles-endpoint` | `GET /learning/articles`와 그 픽스처·테스트 정리(커리큘럼 조회가 같은 목록을 `preReleaseArticles`로 준다) | — | B11 배포 |
| B15 | `docs(fe): spec 013 2판 문서 동기화와 최종 검증` | `docs(fe)/spec-013-v2-wrapup` | [quickstart](quickstart.md) §6~§13 실행 기록(`validation.md` 2판 절), spec·plan과 구현 차이 반영, README 색인 확인 | — | 전부(B16 포함) |

```text
B07 ──▶ B08 ──▶ B09
 │       │       │
 │       │       ├──────────────▶ B12 ◀── B10
 │       │       └──▶ B13 ◀── B11, B12
 ├──▶ B10 ──▶ B11 ◀── B08
 └──▶ B14 ◀── B08, B10
 B11 ──▶ B16
            B07~B14, B16 ──▶ B15
```

**묶음 열 = 브랜치 열 = MR 열.** 프런트엔드 묶음은 계약 픽스처를 기준으로 백엔드 병합을 기다리지 않고 시작할 수 있다(1판과 같다).

### 묶음을 이렇게 자른 이유

- **백엔드를 콘텐츠(B07)·단계 공개(B08)·나의 학습(B09)으로 나눴다.** 세 묶음은 테이블과 리뷰 관점이 다르다. B07만으로 V2 원고를 저장·검사·미리 볼 수 있어 원고 작업이 먼저 시작된다. B08의 단계 공개는 되돌릴 수 없는 동작이라 따로 리뷰한다. B09는 사용자 기록과 개인정보 규칙을 다룬다.
- **스키마는 묶음마다 자기 테이블·열만 만든다.** B07(단계·콘텐츠 열), B08(변경 기록·넘겨받기), B09(나의 학습 네 테이블)이 겹치지 않으므로 "스키마·매핑·검증을 같은 MR에" 규칙이 묶음 안에서 닫힌다.
- **글 화면을 B10(비로그인으로 보이는 것)과 B12(기록)로 나눴다.** B10은 B07 계약만으로 검증되고, B12는 B09 계약과 로그인 상태 처리가 필요하다. 한 묶음이면 리뷰 범위가 지나치게 넓다.
- **목록(B11)과 나의 학습 화면(B13)을 나눴다.** B11은 비로그인 화면이 완결되는 단위이고, B13은 나의 학습 응답에 기대는 부품(요약 패널·글별 상태·배지)을 모은다.
- **옛 목록 경로 삭제(B16)를 문서 묶음(B15)에서 뗐다.** 백엔드 코드 삭제와 문서 동기화는 목적과 리뷰어가 다르다. B16은 화면이 커리큘럼 조회로 옮긴 B11이 배포된 뒤에만 병합한다.
- **B14는 가장 크다.** 편집기·문제 편집기·원고 가져오기·단계 패널이 한 흐름(원고를 넣고 검사하고 공개)이다. 더 쪼개면 "넣을 수는 있는데 공개할 수 없는" 중간 상태가 생겨 묶음 조건 3을 어긴다. 브랜치는 하나로 두고 안에서 커밋으로 나눈다.

### 묶음에 넣지 않은 것

- **원고 27편.** 코드가 아니라 콘텐츠다. 단계마다 원고 MR(`docs(fe)/learning-content-stage-{n}`)로 다른 팀원 한 명이 검토한다(FR-016). 운영자가 관리 화면에 가져와 단계를 공개한다. 원고 검토가 맡는 것은 진단 화면과 같은 이름(FR-006), 질문 하나·예시 하나(FR-002), Thorp 동작 설명의 정확성(FR-027, SC-012), 보기 글자로 뜻 바꾸지 않기(spec Edge Cases), 다른 글이 소유한 용어가 나오는 자리를 용어 언급으로 표시하기(SC-013, 자동으로 찾지 않는다, R17), 출처가 필요한 세 경우의 '(*출처)'(SC-014)다.
- **다른 spec의 수정.** 014·README 공통 가정·003·002·005·007·009·010·011은 [교차 의존](#교차-의존)의 별도 MR이다.
- **사용성 관찰(SC-005).** 코드 묶음이 아니다. B15는 수행 여부만 적는다.
- **1판 남은 수동 확인(T097).** 1판 묶음의 마무리이며 2판 묶음과 섞지 않는다.

### 첫 단계 공개 조건

첫 단계 공개는 되돌릴 수 없고 그 순간 나의 학습이 켜진다(FR-031). 아래를 모두 확인한 뒤 운영 관리 화면에서 1단계를 공개한다.

1. B07~B14가 병합되어 운영에 배포되었다. 나의 학습이 없는 상태로 단계를 공개하지 않는다.
2. B07 배포 전에 운영 DB의 `article_key` 목록이 마이그레이션의 아홉 글과 같은지 확인했다(R15).
3. 1단계 원고 네 편이 원고 MR로 검토되었고 `docs/learning-content/next/`에 있다. Thorp 동작을 설명하는 문장(분배금 반영 여부 등)은 쓰기 전에 실제 동작을 확인했다(FR-027).
4. 27편의 용어 대응표(용어마다 처음 설명하는 글)를 만들고, 게시될 용어 수가 25개 이상인지와 경험치 합계 3,000을 확인했다. 미달이면 누구도 받기 전에 배지 단계를 고친다(R39).
5. 운영 관리 화면의 단계 미리 검사가 `ready: true`다.
6. constitution 6.2.0 초안의 상태를 확인했다. 반려되었으면 v3 규칙(예시 필수)이 배포되어 있다.

2단계부터는 3·5만 다시 확인한다.

## Complexity Tracking

| 항목 | 왜 단순한 방법으로 끝내지 않았나 | 기각한 더 단순한 방법 |
|---|---|---|
| 초안 작업본과 불변 게시 판을 나눈다(1판) | 게시된 글을 고치는 동안 검사를 거치지 않은 문장이 공개되면 FR-017·SC-006이 깨진다 | 한 행에 `status`만 두기([research](research.md) R2) |
| 게시 용어 색인 테이블을 따로 둔다(1판) | 도움말 조회가 판 JSON을 뒤지지 않게 하고, 게시와 같은 트랜잭션으로 판과 어긋나지 않게 한다 | 판 JSON에서 조회 시 찾기 |
| 본문을 구조화 블록으로 담는다(1판) | 용어 문장 일치(SC-003)와 가상 계산 예 표시(FR-005)를 구조로 보장한다 | Markdown(R3) |
| 관리 권한을 포트와 설정 허용 목록으로 둔다(1판) | 014에 역할이 없고 014 소유 스키마를 013이 바꾸지 않는다 | `users.role` 컬럼 추가(R5) |
| 예시 섹터를 조회할 때마다 대응표와 대조한다(1판) | 대응표는 게시 뒤에도 바뀐다(FR-004) | 게시 시에만 판정(R8) |
| 초안 형식 V1·V2와 게시 방식 표 **(2판)** | 이전 판 고치기와 '공개 전 단계 글은 단계 공개로만'을 함께 지킨다 | 내용 모양으로 추측(R16) |
| 초안 배치와 공개 배치 두 벌 **(2판)** | "다시 게시할 때 공개된다"(FR-023)와 판 불변을 함께 지킨다 | 판에 단계 기록, 초안 배치를 바로 공개(R15) |
| 글자 조각 **(2판)** | 출처 표시와 용어 언급의 자리를 구조로 알아야 한다(FR-006·FR-007·FR-009) | 마크업 문자열 저장, 이름 자동 찾기(R17) |
| v1·v2 규칙 파일 두 벌 **(2판)** | 이전 판은 v1 검사를 계속 받아야 하고 v1은 운영 판에 기록되었을 수 있다 | v1 파일 고쳐 쓰기(R19) |
| 같은 문제 판정과 식별자 바꾸기 **(2판)** | 사용자의 첫 답 기록을 지우지 않으면서 뜻이 바뀐 문제를 새 문제로 다룬다 | 저장마다 비교, 내용 지문(R21) |
| 단계 미리 검사를 공개와 따로 둔다 **(2판)** | 단계 공개는 되돌릴 수 없다 | 공개만 두기(R23) |
| 넘겨받기 열과 부분 유일 색인 **(2판)** | 용어를 단계 공개와 같은 트랜잭션에서 옮겨 도움말이 끊기지 않게 한다 | 별도 옮기기 동작(R24) |
| 변경 기록 테이블 **(2판)** | 글을 옮기거나 게시 취소해도 지난 공개의 편수·제목이 바뀌지 않게 한다 | 게시 이벤트에서 매번 계산(R27) |
| 나의 학습 네 테이블과 DB 유일 제약 **(2판)** | 여러 탭의 동시 요청에서 첫 답 한 번·경험치 한 번·정답 몫 상한을 지킨다 | 사용자마다 JSON 한 칸(R28) |
| `GET /learning/me`가 정산한다 **(2판)** | FR-024가 '나의 학습'을 열 때 판정하라고 한다 | 운영자 게시 때 일괄 재계산, 정산용 POST(R30) |
| 원고 편집 문법과 가져오기 **(2판)** | 27편과 문제 54~81개를 손으로 넣으면 저장소 원고와 어긋난다(FR-016) | 블록 하나씩 입력, Markdown 원고와 변환기(R26) |

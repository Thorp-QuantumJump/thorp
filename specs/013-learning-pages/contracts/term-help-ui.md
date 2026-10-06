# 계약: 용어 도움말과 화면 사이 이동

**Spec**: [spec.md](../spec.md) | **Research**: [research.md](../research.md) | **Date**: 2026-09-16 · 2판 2026-10-05

서버 API가 아니라 **화면 사이의 계약**이다. 진단 화면(002·003·005)과 013 도움말을 붙인 다른 화면이 013의 용어 설명을 붙이는 방법, 도움말에서 글로 갔다가 돌아오는 방법, 글에서 002로 예시를 넘기는 방법을 정한다. **(2판)** 배우기 글 안의 도움말 표시(FR-009)와 설정 항목 네 개의 도움말(FR-008)도 여기서 정한다.

## 1. 조회 함수 — `frontend/src/api/learningGlossary.ts`

```ts
export type GlossaryLookup =
  | { status: 'ready'; entry: LearningTermEntry }
  | { status: 'notReady' }   // 404 LEARNING_TERM_NOT_READY
  | { status: 'failed' }     // 그 밖의 모든 실패

export function fetchGlossaryEntry(termId: string, signal?: AbortSignal): Promise<GlossaryLookup>
```

- 인자는 표시 이름이 아니라 **`termId`**다([research](../research.md) R4, spec FR-008).
- 함수는 예외를 던지지 않고 세 상태 중 하나로 돌려준다. 호출하는 화면이 HTTP 코드를 해석하지 않게 하기 위해서다.
- `apiClient`를 경유한다([frontend/CLAUDE.md](../../../frontend/CLAUDE.md)).
- 재시도하지 않는다. 다시 시도는 사용자가 누를 때 다시 부른다(FR-010).
- **(2판)** 응답 모양은 바뀌지 않는다. `entry.sentence`는 CURRENT 판 용어면 뜻 문장이다([learning-public-api](./learning-public-api.md) §4).

## 2. 컴포넌트 — `frontend/src/components/learning/TermHelp.tsx`

여러 화면이 공통으로 쓴다. 같은 컴포넌트를 화면마다 따로 만들면 세 상태와 접근성 규칙이 갈라진다.

### Props

| 이름 | 형 | 필수 | 뜻 |
|---|---|---|---|
| `termId` | `string` | ✓ | 가리킬 용어 |
| `label` | `string` | ✓ | 트리거 버튼의 접근 가능한 이름(`${label} 도움말`)에 쓴다. 설명을 불러오기 전에도 버튼이 이름을 가져야 한다 |
| `returnTo` | `LearningReturnState` | **(2판)** 선택 | 글로 이동했다가 돌아올 자리. 보통 현재 화면의 경로다(§3). 배우기 글 안에서는 그 글이 받은 `learningReturn`을 그대로 넘기고, 없으면 넘기지 않는다 |
| `note` | `ReactNode` | 선택 | 9월 판. 003이 축 도움말 문장을 붙일 때 쓴다. 모든 상태에서 보인다. **(2판)** `fallbackMeaning`과 함께 쓰면 뜻 문장을 뺀 나머지 안내만 담는다(§5) |
| `on` | `'click'` \| `'hover'` | 선택 | 9월 판. 여는 방식 |
| `fallbackMeaning` **(2판)** | `string` | 선택 | `notReady`일 때 '설명 준비 중' 대신 보일 뜻 문장. `notReady`에서만 보인다. 설정 항목 네 개에서 003 등록표의 뜻 문장 하나만 넘긴다(§5) |
| `articleLinkLabel` **(2판)** | `string` | 선택 | 글 연결 문구. 기본은 1판 구현의 `‘{articleTitle}’ 글 읽기`이며 spec이 '해당 주제 글로 이동'이라고 부르는 연결이 이것이다. 설정 항목은 '이 설정을 설명하는 글 보기' |

### 상태와 표시

| 상태 | 표시 | 근거 |
|---|---|---|
| 닫힘 | `button` 트리거만 | FR-019 |
| 여는 중 | 불러오는 중이라는 짧은 텍스트 | |
| `ready` | 용어 이름, 설명 문장(뜻 한 문장), 해당 주제 글로 가는 연결. 다른 이름·읽는 법·한계와 '더 보기'는 없다 | FR-008, FR-009 |
| `notReady` | '설명 준비 중' 한 문장. 연결 없음. **(2판)** `fallbackMeaning`이 있으면 그 문장을 보이고 연결 없음 | FR-010, FR-008 |
| `failed` | `role="alert"`로 불러오지 못했다는 한 문장과 다시 시도 `button` | FR-010, SC-007 |

- 설명 문장은 **받은 그대로** 텍스트로 보인다. 화면이 문장을 짓거나 줄이지 않는다(FR-010).
- 여는 행동이 요청을 시작한다. 화면을 열 때 미리 부르지 않는다(R10).
- 어느 상태에서도 호출한 화면의 입력·진행을 막지 않는다.

### 접근성 (FR-019)

- `radix-ui` Popover로 만든다. 트리거는 '?'를 보이는 `button`이고 이름은 `${label} 도움말`이다. 붙이는 화면이 용어 이름을 옆에 이미 보이므로 버튼 글자로 한 번 더 보이지 않는다.
- 키보드로 열고, Esc로 닫고, 닫으면 초점이 트리거로 돌아온다.
- 글 연결과 다시 시도는 Tab으로 닿는 `a`·`button`이다.

## 3. 글로 이동과 돌아가기

### 보내는 쪽 (TermHelp)

```ts
navigate(`/learn/${articleId}`, {
  state: { learningReturn: { path: '/strategy', label: '전략 선택' } },
})
```

- **같은 탭**에서 이동한다. 새 탭을 열지 않는다(spec FR-009).
- 입력값을 state에 싣지 않는다. 002·003의 입력은 각자의 `sessionStorage` 탭 세션 규칙이 유지한다.
- **(2판)** `label`은 호환을 위해 그대로 싣지만 받는 쪽이 쓰지 않는다. 문구는 아래 표가 정한다.

### 받는 쪽 (글 화면 `/learn/:articleId`) **(2판)**

`frontend/src/components/learning/returnTargets.ts`의 표로 정한다([research](../research.md) R35).

| 경로(pathname) | 보이는 연결 | 화면 |
|---|---|---|
| `/diagnosis/sectors` · `/strategy` · `/results/`로 시작 | 하던 진단으로 돌아가기 | 002·003·005 |
| `/param-search` | 자동 탐색으로 돌아가기 | 007 |
| `/market-flow` | 시장 흐름으로 돌아가기 | 009 |
| `/regime-search` | 비슷했던 때 보기로 돌아가기 | 010 |
| `/regime-link`로 시작 | 경제환경이 비슷했던 과거로 돌아가기 | 011 |

연결은 아래 **세 조건을 모두 만족할 때만** 보인다.

1. `location.state.learningReturn.path`가 문자열이다.
2. `normalizeReturnTarget(path)`의 결과가 입력 `path`와 같다(014 `frontend/src/auth/returnTarget.ts`). 쿼리 문자열이 붙은 경로도 받는다.
3. 그 경로의 pathname이 위 표에 든다. `/results/`(뒤에 한 글자 이상)와 `/regime-link`(그 자체와 `/regime-link/` 아래)만 앞부분으로 맞추고, 나머지는 정확히 같아야 한다(예: `/strategy-x`는 들지 않는다).

하나라도 어긋나면 연결을 보이지 않는다. 배우기 메뉴·외부 링크·새로고침으로 state가 없는 경우가 여기에 든다(spec Edge Cases).

- 연결은 보조 연결(`a`)이며 '이 예시로 전략 진단하기'와 같은 크기의 버튼으로 두지 않는다(spec FR-009, 원칙 II).
- 누르면 `navigate(path)`로 돌아간다. `history.back()`에 맡기지 않는다([research](../research.md) R11).
- 글 화면에서 다른 글로 이동할 때(다음 글, 글 안 도움말, 목록)는 받은 `learningReturn`을 그대로 넘겨 처음 떠난 화면을 잃지 않는다(spec Edge Cases).
- 진단 화면으로 돌아왔을 때의 입력 유지는 002·003의 탭 세션 규칙이 보장한다(FR-013). 다른 화면의 상태 유지는 그 화면의 spec이 정한다(FR-009).
- 표에 화면을 더하거나 문구를 고치는 일은 그 화면이 도움말을 붙이는 MR에서 할 수 있다.

## 4. 예시로 002 진입

글 화면의 '이 예시로 전략 진단하기'는 [002 selection-handoff](../../002-sector-weight-selection/contracts/selection-handoff.md) §2를 그대로 따른다. 013은 값을 싣기만 하고 덮어쓰기 확인은 002가 한다(spec FR-003).

```ts
navigate('/diagnosis/sectors', {
  state: {
    sectorSelectionExample: {
      source: 'learning',
      topicId: article.articleId,
      selected: article.example.sectors.map(({ code, weightTenths }) => ({ code, weightTenths })),
    },
  },
})
```

- `topicId`에는 `articleId`를 넣는다.
- `weightTenths`는 공개 조회 응답의 값을 **변환하지 않고** 싣는다.
- `example.available=false`이면 이 버튼을 그리지 않는다. 대신 한 문장 안내와 `navigate('/diagnosis/sectors')`(state 없음) 연결을 보인다(US2 시나리오 3).
- **(2판)** `example`이 `null`인 글에는 버튼도 안내도 없다(FR-003). 이 버튼은 본문 끝 표지 뒤, 완료 카드 앞에 둔다(FR-021).

## 5. 설정 항목 네 개의 도움말 **(2판)**

3단계 설정 항목 글 네 편이 설명하는 항목('관측 기간', '최근 구간 제외', '선정 방식', '비중 상한')은 003 등록표의 축이 [data-model](../data-model.md) §7-3의 예약 `termId`를 가리킨다(FR-008).

| 그 용어의 상태 | 도움말 창 |
|---|---|
| 게시됨(`ready`) | 013 용어 이름과 뜻 문장, `note`(상한 처리 안내 등), '이 설정을 설명하는 글 보기' 순서. 등록표의 뜻 문장(`fallbackMeaning`)은 보이지 않는다. 허용 범위·기본값·상한 처리 안내는 지금처럼 003 등록표가 보인다 |
| 게시 전·게시 취소(`notReady`) | 등록표의 뜻 문장(`fallbackMeaning`), `note` 순서. '설명 준비 중'과 글 연결 없음 |
| 불러오지 못함(`failed`) | 불러오지 못했다는 문장과 다시 시도, 그리고 `note`. 등록표의 뜻 문장은 보이지 않는다. 허용 범위 안내는 그대로 보인다 |

`TermHelp`는 `fallbackMeaning`을 받으면 창 안을 뜻 자리, `note`, 글 연결 순서로 둔다. 뜻 자리는 `ready`면 용어 이름과 013 뜻 문장, `notReady`면 `fallbackMeaning`, `failed`면 불러오지 못했다는 문장과 다시 시도, 여는 중이면 불러오는 중이라는 문장이다. 글 연결은 `ready`에만 있다. `note`는 모든 상태에서 보이고 `fallbackMeaning`은 `notReady`에서만 보인다. `fallbackMeaning`이 없으면 9월 판 그대로 `note`를 맨 앞에 두고, `note`가 있으면 `notReady`에서 '설명 준비 중'을 덧붙이지 않는다(003의 다른 축).

설정 항목 네 개에서 003은 등록표의 뜻 문장 하나만 `fallbackMeaning`으로, 그 밖의 안내(상한 처리 안내, 003 화면이 덧붙이는 평가 범위 문장 등)만 `note`로 넘긴다. 같은 문장을 두 prop에 함께 넘기면 뜻 문장이 둘 보인다(FR-008). 지금 등록표는 뜻 문장과 나머지 안내를 `description` 한 필드에 담는다(예: `parameter-registry.yml`의 모멘텀 `maxWeight` 축). 둘을 어떻게 나눌지는 003이 정한다.

013은 `TermHelp`의 두 선택 prop과 위 표시 규칙을 제공한다(B10). 003 등록표의 `termId` 변경, 뜻 문장과 나머지 안내의 분리, `DetailSettingsPanel`의 prop 사용은 003의 별도 MR이다.

## 6. 화면별 할 일

013은 도움말을 **어디에** 붙일지 정하지 않는다(spec FR-008, Assumptions). 각 화면 spec이 정하고 이 컴포넌트를 쓴다.

| 화면 | 지금 상태(2026-10-05 코드) | 필요한 일 |
|---|---|---|
| [002](../../002-sector-weight-selection/spec.md) | `TermHelp`를 쓰지 않는다(`InlineHelp`만) | 002 담당이 붙일 용어와 자리를 정한다 |
| [003](../../003-strategy-settings/spec.md) | `DetailSettingsPanel`이 등록표 `axes[].termId`로 쓴다 | §5의 설정 항목 네 개 연결(별도 MR) |
| [005](../../005-result-comparison/spec.md) | `TermHelp`를 쓰지 않는다. 013 US3 시나리오 4가 '가장 크게 줄었던 폭' 도움말을 전제한다 | 005 담당이 붙일 용어와 자리를 정한다 |
| [007](../../007-auto-parameter-search/spec.md) · [009](../../009-market-flow-overview/spec.md) · [010](../../010-economic-regime-search/spec.md) · [011](../../011-regime-strategy-link/spec.md) **(2판)** | `TermHelp`를 쓰지 않는다 | 붙일지는 화면마다 별도 MR로 정한다. 붙이면 같은 용어를 화면에서 따로 설명하지 않는다(FR-015). 돌아가기 문구는 §3 표에 이미 있다 |

쓸 수 있는 `termId`는 [data-model](../data-model.md) §13의 표와 원고가 정하는 새 용어다.

## 7. 배우기 글 안의 도움말 표시 **(2판)**

- 글 본문의 언급 조각 가운데 `owned: false`인 용어가 **처음 나오는 자리 한 곳**에 `TermHelp`를 둔다([data-model](../data-model.md) §5-4). 용어 글자 자체는 누를 수 없다(FR-009).
- `returnTo`에는 이 글이 받은 `learningReturn`을 넘긴다. 없으면 넘기지 않는다. 도움말의 글 연결로 옮겨 가도 처음 떠난 화면으로 돌아가는 연결이 남는다(spec Edge Cases).
- 그 용어의 글이 게시 취소되어 있으면 `notReady`로 '설명 준비 중'을 보이고 연결이 없다(FR-010).

# 계약: 용어 도움말과 화면 사이 이동

**Spec**: [spec.md](../spec.md) | **Research**: [research.md](../research.md) | **Date**: 2026-09-16

서버 API가 아니라 **화면 사이의 계약**이다. 002·003·005 화면이 013의 용어 설명을 붙이는 방법, 도움말에서 글로 갔다가 돌아오는 방법, 글에서 002로 예시를 넘기는 방법을 정한다.

## 1. 조회 함수 — `frontend/src/api/learningGlossary.ts`

[003 tasks](../../003-strategy-settings/tasks.md) T057a가 이 파일에 `fetchGlossaryEntry`를 격리해 두고 "013이 구현되면 이 파일만 바꾼다"고 적었다. 013은 그 파일의 실제 구현을 소유한다.

```ts
export type GlossaryLookup =
  | { status: 'ready'; entry: LearningTermEntry }
  | { status: 'notReady' }   // 404 LEARNING_TERM_NOT_READY
  | { status: 'failed' }     // 그 밖의 모든 실패

export function fetchGlossaryEntry(termId: string, signal?: AbortSignal): Promise<GlossaryLookup>
```

- 인자는 표시 이름이 아니라 **`termId`**다([research](../research.md) R4, spec FR-008). 003 T057a의 `term` 인자는 이 `termId`를 뜻한다.
- 함수는 예외를 던지지 않고 세 상태 중 하나로 돌려준다. 호출하는 화면이 HTTP 코드를 해석하지 않게 하기 위해서다.
- `apiClient`를 경유한다([frontend/CLAUDE.md](../../../frontend/CLAUDE.md)).
- 재시도하지 않는다. 다시 시도는 사용자가 누를 때 다시 부른다(FR-010).

## 2. 컴포넌트 — `frontend/src/components/learning/TermHelp.tsx`

002·003·005가 공통으로 쓴다. 003 T057a가 `components/strategy/TermHelp.tsx`로 계획한 자리는 이 공용 컴포넌트를 쓰는 것으로 바꾼다. 같은 컴포넌트를 화면마다 따로 만들면 세 상태와 접근성 규칙이 갈라진다.

### Props

| 이름 | 형 | 필수 | 뜻 |
|---|---|---|---|
| `termId` | `string` | ✓ | 가리킬 용어 |
| `label` | `string` | ✓ | 트리거 버튼의 접근 가능한 이름(`${label} 도움말`)에 쓴다. 화면이 이미 보이는 이름을 넘긴다. 설명을 불러오기 전에도 버튼이 이름을 가져야 한다 |
| `returnTo` | `{ path: string; label: string }` | ✓ | 글로 이동했다가 돌아올 자리. 보통 현재 화면의 경로와 이름이다(§3) |

### 상태와 표시

| 상태 | 표시 | 근거 |
|---|---|---|
| 닫힘 | `button` 트리거만 | FR-019 |
| 여는 중 | 불러오는 중이라는 짧은 텍스트 | |
| `ready` | 용어 이름, 설명 문장, 해당 주제 글로 가는 연결 | FR-008, FR-009 |
| `notReady` | '설명 준비 중' 한 문장. 연결 없음 | FR-010 |
| `failed` | `role="alert"`로 불러오지 못했다는 한 문장과 다시 시도 `button` | FR-010, SC-007 |

- 설명 문장은 **받은 그대로** 텍스트로 보인다. 화면이 문장을 짓거나 줄이지 않는다(FR-010).
- 여는 행동이 요청을 시작한다. 화면을 열 때 미리 부르지 않는다(R10).
- 어느 상태에서도 호출한 화면의 입력·진행을 막지 않는다. 도움말을 열고 닫아도 002·003 상태를 건드리지 않는다(FR-009, FR-010).

### 접근성 (FR-019)

- `radix-ui` Popover로 만든다. 트리거는 '?'를 보이는 `button`이고 이름은 `${label} 도움말`이다. 붙이는 화면이 용어 이름을 옆에 이미 보이므로 버튼 글자로 한 번 더 보이지 않는다.
- 키보드로 열고, Esc로 닫고, 닫으면 초점이 트리거로 돌아온다.
- 글 연결과 다시 시도는 Tab으로 닿는 `a`·`button`이다.

## 3. 글로 이동과 '하던 진단으로 돌아가기'

### 보내는 쪽 (TermHelp)

```ts
navigate(`/learn/${articleId}`, {
  state: { learningReturn: { path: '/strategy', label: '전략 선택' } },
})
```

- **같은 탭**에서 이동한다. 새 탭을 열지 않는다(spec FR-009).
- 입력값을 state에 싣지 않는다. 002·003의 입력은 각자의 `sessionStorage` 탭 세션 규칙이 유지한다([002 selection-handoff](../../002-sector-weight-selection/contracts/selection-handoff.md) §1, 003 FR-017).

### 받는 쪽 (글 화면 `/learn/:articleId`)

돌아가기 연결은 아래 **세 조건을 모두 만족할 때만** 보인다.

1. `location.state.learningReturn`이 있다.
2. `path`가 허용 목록에 든다: `/diagnosis/sectors`, `/strategy`, 또는 `/results/`로 시작하는 경로.
3. `normalizeReturnTarget(path)`의 결과가 입력 `path`와 같다(014 `frontend/src/auth/returnTarget.ts`).

하나라도 어긋나면 연결을 보이지 않는다. 배우기 메뉴·외부 링크·새로고침으로 state가 없는 경우가 여기에 든다(spec Edge Cases).

- 연결은 보조 연결(`a`)이며 '이 예시로 비중 비교하기'와 같은 크기의 버튼으로 두지 않는다(spec FR-009, 원칙 II).
- 누르면 `navigate(path)`로 돌아간다. `history.back()`에 맡기지 않는다([research](../research.md) R11).
- 글 화면에서 다른 글로 이동할 때는 받은 `learningReturn`을 그대로 넘겨 돌아갈 자리를 잃지 않는다.

## 4. 예시로 002 진입

글 화면의 '이 예시로 비중 비교하기'는 [002 selection-handoff](../../002-sector-weight-selection/contracts/selection-handoff.md) §2를 그대로 따른다. 013은 값을 싣기만 하고 덮어쓰기 확인은 002가 한다(spec FR-003).

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
- `weightTenths`는 공개 조회 응답의 값을 **변환하지 않고** 싣는다. 단위가 같다([learning-public-api](./learning-public-api.md) §2).
- `example.available=false`이면 이 버튼을 그리지 않는다. 대신 한 문장 안내와 `navigate('/diagnosis/sectors')`(state 없음) 연결을 보인다(US2 시나리오 3).

## 5. 002·003·005가 할 일

013은 도움말을 **어디에** 붙일지 정하지 않는다(spec FR-008, Assumptions). 각 화면 spec이 정하고 이 컴포넌트를 쓴다.

| 화면 | 지금 상태 | 필요한 일 |
|---|---|---|
| [002](../../002-sector-weight-selection/spec.md) | spec에 013 도움말 참조가 없다 | 002 담당이 붙일 용어와 자리를 정한다 |
| [003](../../003-strategy-settings/spec.md) | T097에서 등록표 `axes[].termId`를 받아 `DetailSettingsPanel`이 이 공용 컴포넌트를 쓴다(2026-09-18 코드 확인) | T057a·T063의 옛 경로·실패 처리 문구를 현재 구현에 맞추는 것은 003 담당이다. 축 설명은 003 등록표가 계속 소유한다 |
| [005](../../005-result-comparison/spec.md) | spec에 013 도움말 참조가 없다. 013 US3 시나리오 4가 '가장 크게 줄었던 폭' 도움말을 전제한다 | 005 담당이 붙일 용어와 자리를 정한다 |

쓸 수 있는 `termId` 목록은 [data-model](../data-model.md) §8이다.

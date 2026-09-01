# Frontend 작업 가이드라인

이 문서는 `frontend` 영역에서 기능을 개발하거나 버그를 수정할 때 지켜야 할 공통 기준을 정의한다.
현재 프로젝트의 기술 스택인 React 19, TypeScript, Vite, React Router, Axios,
Tailwind CSS 4, CSS Module, Motion을 기준으로 한다. 기본 UI는 shadcn 방식의
로컬 컴포넌트를 사용하며, 고급 차트는 Bklit 레지스트리와 Visx 기반 구현을 사용한다.

## 1. 적용 기준

규칙의 강도를 다음과 같이 구분한다.

- **필수**: 특별한 사유가 없다면 반드시 지킨다.
- **권장**: 기본 선택으로 따르되, 더 적합한 방법이 있다면 근거를 남기고 변경할 수 있다.
- **조건부**: 해당 기술이나 상황이 실제로 적용된 경우에만 따른다.

이 문서와 코드, 테스트, 요구사항이 서로 다르면 실제 동작과 최신 요구사항을 함께 확인한다.
규칙을 예외적으로 적용해야 할 때는 그 이유와 영향을 작업 설명에 남긴다.

## 2. 기본 원칙

### 필수

- 요청받은 범위만 수정한다.
- 기능 추가, 버그 수정, 리팩터링, 디자인 변경을 한 작업에 섞지 않는다.
- 기존 UI나 동작을 변경해야 한다면 변경 목적과 영향을 먼저 명확히 한다.
- 다른 작업자의 변경을 임의로 수정하거나 되돌리지 않는다.
- 라이브러리를 추가하기 전에 기존 기술로 해결할 수 있는지 확인한다.
- 전체 프로젝트 포맷 변경은 별도 작업으로 분리한다.
- UTF-8과 LF를 사용한다. 저장소의 `.editorconfig`와 `.gitattributes`를 따른다.
- 코드, 테스트, 문서가 다르면 임의로 한쪽을 정답으로 간주하지 않고 요구사항과 실제 동작을 확인한다.

### 권장

- 변경은 검토와 검증이 가능한 작은 단위로 나눈다.
- 추상화나 공통화보다 현재 요구사항을 명확하게 구현하는 것을 우선한다.
- 새 패턴을 도입하기 전에 저장소에서 이미 사용 중인 패턴을 확인한다.
- 외부 레지스트리 컴포넌트와 프로젝트에서 직접 작성한 코드를 구분한다.

## 3. 디렉터리와 책임

현재 프로젝트는 다음 디렉터리를 기본으로 사용한다.

```text
src/
├─ api/          공통 HTTP client와 도메인별 API 함수
├─ components/   재사용 가능한 UI 컴포넌트
├─ hooks/        재사용 가능한 React 로직
├─ pages/        URL 단위 페이지
├─ types/        공통 타입과 API DTO
├─ App.tsx       최상위 앱 구성
├─ main.tsx      애플리케이션 진입점
└─ index.css     최소 전역 스타일
```

프로젝트가 커져 분리가 필요할 때는 다음 디렉터리를 추가할 수 있다.

```text
src/
├─ app/          Provider, 라우팅, 전역 초기화
├─ components/
│  ├─ common/    여러 기능이 실제로 공유하는 UI
│  ├─ ui/        shadcn/Bklit 방식의 Button, Input, Modal 같은 기본 UI
│  ├─ charts/    Bklit 레지스트리에서 설치한 범용 차트 소스
│  └─ feature/   특정 기능의 UI
├─ data/         정적 데이터와 개발용 mock 데이터
├─ styles/       전역 스타일과 디자인 토큰
└─ utils/        React에 의존하지 않는 순수 함수
```

규모가 더 커지면 `shared`와 `features`를 사용하는 기능 중심 구조를 검토할 수 있다.
단, 기존 구조와 기능 중심 구조를 기준 없이 혼합하지 않으며 구조 변경은 팀의 합의가 필요한 별도 작업으로 진행한다.

### `app`

- 전역 Provider, 최상위 라우팅, 인증·권한 처리, Error Boundary, 전역 초기화를 담당한다.
- 비즈니스 화면의 세부 UI는 두지 않는다.

### `pages`

- URL 하나에 대응하는 화면을 구성한다.
- API 결과와 페이지 수준 상태를 연결하고 하위 컴포넌트를 조합한다.
- 지나치게 큰 JSX나 독립적인 기능은 하위 컴포넌트로 분리한다.
- 버튼, 모달, 리스트, 폼의 세부 구현을 한 페이지 파일에 모두 몰아넣지 않는다.

### `components`

- 화면을 표현하는 UI를 담당한다.
- 특정 기능에서만 사용하는 컴포넌트는 해당 기능 가까이에 둔다.
- 여러 기능에서 역할과 동작이 실제로 같을 때만 공통 컴포넌트로 이동한다.

#### 외부 레지스트리 컴포넌트

이 프로젝트의 shadcn과 Bklit 컴포넌트는 일반 npm UI 패키지처럼
`node_modules`에서만 실행되는 코드가 아니다. 레지스트리의 소스가
`src/components` 아래로 복사되며, 복사된 이후에는 저장소에서 직접 관리하는 코드가 된다.

현재 레지스트리 관리 구역은 다음과 같다.

```text
src/components/ui/       shadcn/Bklit 기본 UI
src/components/charts/   Bklit 차트와 내부 전용 Hook·유틸리티
src/components/shimmering-text.tsx   Bklit 텍스트 모션 컴포넌트
```

- 허용된 레지스트리와 주소는 `components.json`에서 관리한다.
- 설치 전에 추가·변경되는 파일과 npm 의존성을 확인한다.
- 레지스트리 업데이트는 기존 파일을 바로 덮어쓰지 않고 diff를 검토한 후 반영한다.
- 제품 전용 동작과 스타일은 가능한 한 `components/backtest` 같은 기능 컴포넌트에서
  wrapper 또는 props 조합으로 구현한다.
- 레지스트리 원본을 직접 수정해야 한다면 수정 이유와 업데이트 시 충돌 가능성을 작업 설명에 남긴다.
- 레지스트리 코드에는 원본의 파일명과 내부 배치를 유지하기 위한 예외를 허용한다.
  따라서 kebab-case 파일명과 컴포넌트 가까이에 배치된 전용 Hook·순수 유틸리티를 허용한다.
- 레지스트리 원본의 Tailwind utility class 사용을 허용한다.
- 위 예외는 파일명, 내부 배치, 스타일 표현 방식에만 적용한다. 타입 오류, 보안 문제,
  Secret 노출, 접근성, Effect cleanup, 빌드 오류까지 면제하지 않는다.
- 외부 코드에서 발생한 lint 경고는 원인과 영향을 검토하고, 이유 없이 전체 규칙을
  비활성화하지 않는다. 프로젝트 코드에서 새 경고가 발생하지 않게 하고 레지스트리
  업데이트로 기존 경고 수가 증가하면 증가 원인을 보고한다.

### `hooks`

- 반복되는 상태와 생명주기 로직을 담당한다.
- 이벤트 listener, timer, observer, subscription과 그 정리를 관리한다.
- 단순 계산이나 React에 의존하지 않는 함수는 Hook으로 만들지 않고 `utils`에 둔다.

### `api`

- 공통 HTTP client, 인증 헤더, 공통 오류 처리, 도메인별 API 함수를 담당한다.
- 컴포넌트와 페이지에서 Axios나 `fetch`를 직접 호출하지 않는다.
- 현재 프로젝트의 `apiClient`와 `/api` 프록시를 사용하며 URL을 컴포넌트에 하드코딩하지 않는다.

### `types`

- 공통 도메인 타입과 API 요청·응답 DTO를 관리한다.
- 한 파일에서만 사용하는 타입은 사용 위치 가까이에 둔다.
- 모든 타입을 하나의 거대한 파일에 모으지 않는다.
- API DTO와 화면 모델이 다르면 두 타입을 분리하고 변환 위치를 명확히 한다.

### `utils`

- 날짜, 금액, 문자열 포맷, 검증, 정렬, 필터처럼 React에 의존하지 않는 순수 함수를 둔다.
- API 모델 변환을 유틸리티로 처리한다면 프로젝트 전체에서 변환 위치를 일관되게 유지한다.

### `data`

- 정적 옵션, 상수 데이터, 개발용 mock 데이터를 둔다.
- 서버에서 받아오는 데이터와 혼동되지 않도록 파일과 변수의 이름을 명확히 한다.

## 4. 컴포넌트 작성 기준

### 필수

- 컴포넌트는 함수형으로 작성하고 한 가지 주요 책임을 갖게 한다.
- 페이지는 조합을 담당하고 하위 컴포넌트는 표현을 담당한다.
- 목록의 `key`에는 index 대신 안정적인 ID를 사용한다. 안정적인 ID가 없는 정적 목록은 예외로 할 수 있다.
- 렌더링 중 상태를 직접 변경하지 않는다.
- 버튼은 의도하지 않은 폼 제출을 막기 위해 `type`을 명시한다.

### 권장

- 비즈니스 판단이 커지면 Hook이나 순수 함수로 분리한다.
- props가 지나치게 많아지면 컴포넌트의 책임과 데이터 경계를 다시 검토한다.
- 거대한 범용 컴포넌트보다 목적이 분명한 작은 컴포넌트를 우선한다.
- 한 곳에서만 사용하는 코드를 미래의 재사용 가능성만으로 공통화하지 않는다.
- 두 곳 이상에서 역할과 동작이 같을 때 공통화를 검토한다.

파일명은 역할이 드러나는 PascalCase로 작성한다.

```text
UserCard.tsx
UserList.tsx
UserCreateForm.tsx
UserDetailModal.tsx
ProjectSettingsPage.tsx
RequireAuth.tsx
```

`Component1`, `CommonBox`, `DataManager`처럼 책임이 불명확한 이름은 피한다.
Hook의 이름은 `use`로 시작한다.
외부 레지스트리에서 설치한 파일은 업데이트 호환성을 위해 원본 파일명을 유지할 수 있다.

## 5. TypeScript 규칙

### 필수

- `any` 사용을 피한다. 불가피한 경우 사용 범위를 최소화하고 이유를 남긴다.
- 서버 응답을 검증 없이 type assertion으로 확정하지 않는다.
- `optional`과 `nullable`을 구분한다.
- API DTO 필드는 백엔드 계약과 일치시킨다. 현재 백엔드 API 필드 형식은 camelCase다.
- `as`로 타입 오류를 숨기지 않는다.

### 권장

- props와 함수 반환값이 불명확하면 타입을 명시한다.
- 제한된 문자열 상태는 union type으로 표현한다.
- API DTO와 화면에서 사용하는 모델의 형태나 의미가 다르면 분리한다.
- 타입이 자연스럽게 추론되도록 데이터 구조와 분기 로직을 설계한다.
- 런타임 검증이 필요한 외부 데이터는 검증 함수를 사용한다. 검증 라이브러리 도입은 별도로 검토한다.

```ts
type RequestStatus = 'idle' | 'loading' | 'success' | 'error'

type UserResponse = {
  userId: number
  displayName: string
}

type User = {
  id: number
  name: string
}
```

## 6. 상태 관리 규칙

상태는 그 상태가 필요한 가장 가까운 위치에 둔다.

- 한 컴포넌트만 사용: 로컬 state
- 부모와 자식이 공유: 공통 부모
- 반복되는 상태 로직: custom Hook
- 앱 전체에서 공유: Context 또는 팀이 선택한 전역 상태 도구
- 서버 데이터: API Hook 또는 팀이 선택한 서버 상태 관리 계층

모든 값을 전역 상태로 만들지 않는다. 파생할 수 있는 값은 별도 state로 중복 저장하지 않는 것을 권장한다.

```ts
const completedItems = items.filter((item) => item.completed)
```

원본과 파생 상태를 함께 저장하면 서로 불일치할 수 있다.

```ts
const [items, setItems] = useState<Item[]>([])
const [completedItems, setCompletedItems] = useState<Item[]>([])
```

## 7. Effect 규칙

### 필수

- Effect는 외부 시스템과 동기화할 때 사용한다.
- 단순 계산을 위해 Effect와 state를 조합하지 않는다.
- timer, interval, listener, observer, subscription은 cleanup한다.
- 비동기 요청의 오래된 응답이 최신 상태를 덮어쓰지 않도록 취소나 요청 순서를 관리한다.
- Effect dependency 경고를 근거 없이 무시하지 않는다.

### 권장

- 한 Effect가 여러 독립적인 일을 하면 책임별로 분리한다.
- 이벤트에 의해 발생하는 로직은 가능한 한 해당 이벤트 핸들러에서 처리한다.

```tsx
useEffect(() => {
  const timer = window.setInterval(refresh, 10_000)

  return () => {
    window.clearInterval(timer)
  }
}, [refresh])
```

## 8. API 통신 규칙

통신 흐름은 다음 계층을 따른다.

```text
Page 또는 Component
        ↓
도메인 API 또는 API Hook
        ↓
공통 apiClient
        ↓
Server
```

### 필수

- 컴포넌트에서 HTTP client를 직접 호출하지 않는다.
- 개발 환경에서는 기본적으로 `/api` 프록시를 사용한다.
- API 주소를 코드 곳곳에 하드코딩하지 않는다.
- 요청·응답 DTO를 명시적으로 정의한다.
- 로딩, 성공, 빈 결과, 실패 상태를 구분한다.
- 브라우저에 포함되면 안 되는 Secret을 프런트엔드 코드나 환경변수에 저장하지 않는다.
- `VITE_*` 환경변수는 브라우저에 공개될 수 있다는 전제로 사용한다.
- 백테스트 요청은 `QUEUED → RUNNING → COMPLETED`의 비동기 흐름을 고려한다.

### 권장

- 인증 헤더와 공통 오류 처리는 `apiClient` 계층에서 처리한다.
- 서버 응답을 화면 모델로 변환하는 위치를 통일한다.
- 필요한 화면은 재시도 상태와 동작을 제공한다.
- 중복 요청, 요청 취소, 오래된 응답 처리를 고려한다.

## 9. 라우팅 규칙

- Router Provider는 앱 진입점에서 한 번만 제공한다.
- Route 정의 위치를 통일한다.
- 인증과 권한 검사는 Route wrapper 또는 Layout에서 처리한다.
- 페이지 이동은 React Router API를 사용한다.
- URL 직접 접근과 새로고침을 지원한다.
- 잘못된 경로에 대한 fallback을 정의한다.
- 큰 페이지는 실제 번들 크기와 사용 빈도를 확인한 뒤 lazy loading을 검토한다.
- 공유하거나 새로고침 후 복원해야 하는 화면 상태는 URL로 표현하는 것을 우선 검토한다.

URL로 관리하기 좋은 상태의 예는 다음과 같다.

- 검색어
- 페이지 번호
- 정렬 기준
- 선택한 탭
- 필터
- 리소스 ID

## 10. 스타일 규칙

현재 프로젝트는 Tailwind CSS 4와 CSS Module을 함께 사용한다. 두 방식을 임의로
섞지 않고 다음 책임 경계를 기본으로 한다.

- shadcn/Bklit 레지스트리 컴포넌트와 범용 UI: Tailwind utility class
- 프로젝트에서 직접 작성하는 페이지와 기능 컴포넌트: CSS Module
- 여러 영역이 공유하는 색상, radius, 글꼴, 차트 색상: CSS custom property 디자인 토큰
- 런타임에서 계산되는 좌표와 데이터 색상: 인라인 스타일

기존 레지스트리 컴포넌트를 사용하는 것만으로 페이지 전체를 Tailwind로 다시 작성하지
않는다. 반대로 CSS Module로 작성된 기능 컴포넌트 안에 utility class를 임의로 혼합하지
않는다. 책임 경계를 변경할 필요가 있으면 변경 범위와 이유를 먼저 기록한다.

### 전역 스타일

`index.css`에는 다음과 같은 전역 기반 규칙만 둔다.

- Tailwind, shadcn, 공통 font stylesheet import
- `@font-face`
- `box-sizing`
- `body` 기본 margin
- 기본 글꼴과 배경
- root 크기
- form control의 font 상속
- 최소 reset
- 전역 디자인 토큰과 Tailwind theme mapping

특정 페이지나 모달에만 필요한 selector는 전역 스타일에 넣지 않는다.

### 디자인 토큰

색상, 간격, 글꼴, radius, shadow가 반복되기 시작하면 CSS custom property로 토큰화한다.
토큰 체계가 도입된 후에는 컴포넌트마다 같은 의미의 값을 다시 하드코딩하지 않는다.

```css
:root {
  --color-primary: #2563eb;
  --color-danger: #dc2626;
  --color-surface: #fff;
  --color-text: #172033;
  --radius-control: 8px;
  --shadow-panel: 0 12px 28px rgb(0 0 0 / 12%);
}
```

### 기능별 스타일

다음 스타일은 해당 페이지나 기능 가까이에 둔다.

- 특정 화면 전용 스타일
- 복잡한 selector와 pseudo-element
- keyframe
- 특수한 반응형 레이아웃

CSS Module을 사용할 때는 다음과 같이 명시적으로 가져온다.

```tsx
import styles from './ProjectDashboard.module.css'

return <section className={styles.dashboard}>...</section>
```

### 인라인 스타일

인라인 스타일은 드래그 좌표, 계산된 위치와 크기, 데이터 기반 색상, CSS custom property처럼 런타임 계산값에 사용한다.
정적인 간격, 색상, 글꼴, 레이아웃은 CSS 또는 CSS Module을 사용한다.

### 금지 사항

- 화면 전용 스타일을 전역 CSS에 추가하지 않는다.
- 실제로 공유하지 않는 스타일을 공통 스타일로 분류하지 않는다.
- 하나의 거대한 `legacy.css`를 만들지 않는다.
- 여러 스타일 방식을 기준 없이 혼용하지 않는다.
- 컴포넌트마다 임의 breakpoint를 계속 추가하지 않는다.
- `!important`로 구조 문제를 반복해서 덮지 않는다.
- 의미 없이 `z-index` 값을 계속 높이지 않는다.

계층이 필요하면 프로젝트 공통 기준을 정의한다.

```text
base       0
header     10
dropdown   20
overlay    40
modal      50
toast      60
```

## 11. Tailwind 규칙

Tailwind는 shadcn/Bklit 컴포넌트와 범용 UI의 기반 스타일 도구로 사용한다.

- 일반적인 레이아웃, 간격, 크기, 색상, 타이포그래피, 반응형 스타일은 utility class를 우선한다.
- 동적 class는 완전한 클래스 문자열이 소스 코드에 존재하도록 작성한다.
- 클래스 일부를 런타임에 조립하지 않는다.

```ts
const statusClass = {
  success: 'bg-green-500 text-white',
  error: 'bg-red-500 text-white',
}
```

다음과 같은 방식은 피한다.

```tsx
className={`bg-${color}-500`}
```

긴 클래스 문자열은 여러 컴포넌트에서 같은 의미로 반복되거나 이름을 붙였을 때 역할이 더 명확해지는 경우에만 공통화한다.

## 12. 반응형 규칙

- 프로젝트의 공통 breakpoint를 먼저 정한다.
- 모바일에서 단순히 축소하지 않고 실제 배치 변화를 확인한다.
- 가로 스크롤 발생 여부를 확인한다.
- fixed 요소가 viewport를 벗어나지 않는지 확인한다.
- modal과 drawer의 모바일 높이 및 내부 스크롤을 확인한다.
- 최소 지원 너비를 팀에서 정한다.
- 데스크톱과 모바일 모두에서 클릭 영역을 확인한다.

위 항목은 변경한 화면과 관련된 범위에서 검증한다.

## 13. 테마와 모션

테마 또는 애니메이션을 사용하는 경우 다음 기준을 적용한다.

- 테마 값을 디자인 토큰으로 관리한다.
- 테마 저장 위치와 초기화 흐름을 통일한다.
- 색상뿐 아니라 배경 이미지, 그림자, border도 함께 확인한다.
- animation duration과 easing에 공통 기준을 사용한다.
- `prefers-reduced-motion`을 지원한다.
- 애니메이션 종료 후의 레이아웃과 상태를 확인한다.
- 애니메이션 때문에 버튼 활성화나 라우팅을 불필요하게 지연하지 않는다.
- React 애니메이션은 현재 설치된 `motion/react`를 기본으로 사용한다.
- 앱 최상위 `MotionConfig`에서 사용자의 reduced motion 설정을 존중한다.
- Bklit 내부 모션을 수정할 때는 차트의 loading, reveal, ready 상태 전이를 함께 확인한다.

## 14. 접근성 규칙

### 필수

- 의미 있는 이미지에 적절한 `alt`를 제공하고 장식 이미지는 빈 `alt`를 사용한다.
- 입력에는 `label`을 연결한다.
- 아이콘 버튼에는 접근 가능한 이름을 제공한다.
- 클릭 동작에는 가능한 한 `button`이나 `a`를 사용한다.
- 키보드 focus 표시를 제거하지 않는다.
- 비활성 상태는 실제 `disabled` 속성으로 표현한다.
- 색상만으로 성공과 실패를 구분하지 않는다.
- 버튼의 `type`을 명시한다.

### 권장

- 모달은 Escape 닫기, focus 진입, focus 복원, 배경 focus 제한을 고려한다.
- 동적으로 변경되는 중요한 상태 메시지는 필요한 경우 `aria-live`를 사용한다.
- 키보드만으로 주요 사용자 흐름을 수행할 수 있는지 확인한다.

## 15. 테스트 기준

테스트 환경이 도입되어 있거나 작업 범위에 테스트가 포함된 경우 다음 대상을 우선한다.

- 순수 유틸리티
- 데이터 변환
- 입력 검증
- 상태 전이
- 권한 분기
- 날짜와 금액 계산
- 오류 처리
- 중복 요청과 오래된 응답
- 중요한 사용자 상호작용

구현 세부사항보다 사용자에게 보이는 동작을 테스트한다.
테스트 파일은 대상 코드 가까이에 둔다.

```text
utils/
├─ formatPrice.ts
└─ formatPrice.test.ts

components/
├─ LoginForm.tsx
└─ LoginForm.test.tsx
```

현재 프로젝트에는 테스트 스크립트가 없으므로 `npm test`를 완료 조건으로 강제하지 않는다.
테스트 도구가 도입되면 `package.json`에 팀 공통 명령을 정의하고 이 문서를 갱신한다.

## 16. 작업 절차

1. 요구사항과 변경 범위를 확정한다.
2. 현재 Git 상태와 기존 변경을 확인한다.
3. 관련 페이지, 컴포넌트, API, 타입, 테스트를 찾는다.
4. 레지스트리 컴포넌트를 추가하거나 갱신한다면 `components.json`, 생성 파일,
   `package.json`, lockfile 변경을 먼저 확인한다.
5. 변경 전 화면과 동작을 확인한다.
6. 작업을 검토 가능한 작은 단위로 나눈다.
7. 구현 중 타입 검사, lint, 관련 테스트를 반복한다.
8. 변경한 화면과 관련된 데스크톱 및 모바일 동작을 확인한다.
9. 관련된 로딩, 성공, 실패, 빈 상태를 확인한다.
10. 관련된 접근성과 키보드 동작을 확인한다.
11. 구조나 동작이 달라졌다면 관련 문서를 갱신한다.
12. 변경 파일과 검증 결과를 보고한다.

검증은 모든 항목을 기계적으로 수행하는 것이 아니라 변경 범위와 위험도에 맞게 수행한다.
검증을 생략한 항목이 중요하다면 생략 이유를 작업 결과에 남긴다.

## 17. 완료 조건

현재 프로젝트에서 작업 후 반드시 실행하는 명령은 다음과 같다.

```bash
npm run lint
npm run build
git diff --check
```

`npm run lint`와 `npm run build`는 `frontend` 디렉터리에서 실행한다.
테스트나 formatter가 도입되면 다음과 같은 공통 스크립트를 `package.json`에 정의한 뒤 완료 조건에 추가한다.

```bash
npm test
npm run format:check
```

UI 변경이 있는 경우 변경 범위와 관련된 항목을 실제 화면에서 확인한다.

- URL 직접 접근과 새로고침
- 데스크톱과 모바일 레이아웃
- 프로젝트가 지원하는 테마
- 로딩, 빈 결과, 오류, 재시도
- 폼 validation
- modal, dropdown, toast
- 키보드와 focus
- 애니메이션 종료 상태
- 브라우저 콘솔 오류

## 18. 핵심 원칙

페이지는 화면을 조합하고, 컴포넌트는 UI를 표현하며, Hook은 상태와 생명주기를 관리한다.
API 계층은 서버 통신을 숨기고 유틸리티는 순수 로직을 담당한다.
프로젝트 기능 화면은 CSS Module을 기본으로 하고, shadcn/Bklit 기반 범용 UI는 Tailwind를
사용한다. 외부 레지스트리의 구조는 업데이트 호환성을 위해 제한적으로 예외를 허용하지만,
제품 코드의 품질·보안·접근성 기준은 동일하게 적용한다.

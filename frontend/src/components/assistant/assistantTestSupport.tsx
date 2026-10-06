import { act, render } from '@testing-library/react'
import { type RefObject, createRef, useEffect, useMemo } from 'react'
import {
  MemoryRouter,
  type NavigateFunction,
  Route,
  Routes,
  useNavigate,
  useParams,
} from 'react-router-dom'
import type { AssistantAnswer } from '@/types/assistant'
import { AssistantProvider, AssistantWidget } from './AssistantWidget'
import { useAssistantPageContext } from './assistantPageContext'

export const TOKEN = '0123456789abcdef0123456789abcdef'
export const OTHER_TOKEN = 'fedcba9876543210fedcba9876543210'

export const answered: AssistantAnswer = {
  status: 'ANSWERED',
  answer: '비용을 뺀 전체 수익률은 16.67%이고, 거래비용은 모두 17,078원입니다.',
  evidence: [
    { id: 'F1', label: '비용 차감 후 전체 수익률', value: '16.67%', asOf: '2024-12-31', tab: 'summary' },
    { id: 'F4', label: '거래비용 합계', value: '17,078원', asOf: null, tab: 'settings' },
  ],
  page: { kind: 'RESULT' },
}

/** 결과 화면 대역. 실제 `ResultPage`처럼 자기 맥락을 등록한다. */
function FakeResultPage({ token }: { token: string }) {
  const page = useMemo(() => ({ kind: 'RESULT' as const, publicToken: token }), [token])
  useAssistantPageContext(page)
  return <main>결과 {token.slice(0, 4)}</main>
}

export function renderAssistant({ path = `/results/${TOKEN}` }: { path?: string } = {}) {
  const navigateRef = createRef<NavigateFunction>()
  const view = render(
    <MemoryRouter initialEntries={[path]}>
      <NavigationHandle navigateRef={navigateRef} />
      <AssistantProvider>
        <Routes>
          <Route path="/results/:token" element={<RouteResult />} />
          <Route path="/login" element={<RouteResult />} />
          <Route path="/admin/learning" element={<RouteResult />} />
          <Route path="/learn" element={<main>배우기</main>} />
        </Routes>
        <AssistantWidget />
      </AssistantProvider>
    </MemoryRouter>,
  )
  return {
    ...view,
    /** 트리를 내리지 않고 경로만 바꾼다. 실제 앱의 라우트 전환처럼 제공자와 버튼은 그대로 남는다. */
    navigate: async (to: string) => {
      await act(async () => {
        await navigateRef.current?.(to)
      })
    },
  }
}

/** 라우터의 이동 함수를 테스트가 쥐게 한다. */
function NavigationHandle({ navigateRef }: { navigateRef: RefObject<NavigateFunction | null> }) {
  const navigate = useNavigate()
  useEffect(() => {
    navigateRef.current = navigate
  }, [navigate, navigateRef])
  return null
}

function RouteResult() {
  // 경로의 토큰으로 맥락을 등록한다. 토큰이 없으면(로그인·관리 화면 대역) 결과 하나를 등록한 채로 그 경로에
  // 있는 상황을 흉내 낸다.
  const { token = TOKEN } = useParams()
  return <FakeResultPage token={token} />
}

/** jsdom에는 `matchMedia`가 없다. 좁은 화면을 흉내 낼 때만 붙인다. */
export function mockMatchMedia(matches: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string) => ({
      matches,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }),
  })
}

export function clearMatchMedia() {
  // @ts-expect-error 테스트에서만 붙였다 뗀다
  delete window.matchMedia
}

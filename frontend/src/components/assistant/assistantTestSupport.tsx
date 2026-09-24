import { render } from '@testing-library/react'
import { useCallback, useMemo } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
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

/** 결과 화면 대역. 실제 `ResultPage`처럼 자기 맥락을 등록하고 탭 열기 요청을 받는다. */
export function FakeResultPage({
  token,
  onOpenTab,
}: {
  token: string
  onOpenTab?: (tab: string) => void
}) {
  const page = useMemo(() => ({ kind: 'RESULT' as const, publicToken: token }), [token])
  const openTab = useCallback((tab: string) => onOpenTab?.(tab), [onOpenTab])
  useAssistantPageContext(page, { openTab })
  return <main>결과 {token.slice(0, 4)}</main>
}

export function renderAssistant({
  path = `/results/${TOKEN}`,
  onOpenTab,
}: { path?: string; onOpenTab?: (tab: string) => void } = {}) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AssistantProvider>
        <Routes>
          <Route path="/results/:token" element={<RouteResult onOpenTab={onOpenTab} />} />
          <Route path="/login" element={<RouteResult onOpenTab={onOpenTab} />} />
          <Route path="/admin/learning" element={<RouteResult onOpenTab={onOpenTab} />} />
          <Route path="/learn" element={<main>배우기</main>} />
        </Routes>
        <AssistantWidget />
      </AssistantProvider>
    </MemoryRouter>,
  )
}

function RouteResult({ onOpenTab }: { onOpenTab?: (tab: string) => void }) {
  // 경로에 토큰이 없으면(로그인·관리 화면 대역) 결과 하나를 등록한 채로 그 경로에 있는 상황을 흉내 낸다.
  const token = window.location.pathname.includes('/results/') ? TOKEN : TOKEN
  return <FakeResultPage token={token} onOpenTab={onOpenTab} />
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

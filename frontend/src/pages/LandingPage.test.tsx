import { screen, within } from '@testing-library/react'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { renderWithRouter } from '@/test/renderWithRouter'
import { LandingPage } from './LandingPage'

// 첫 화면은 최신 지표를 불러온다. 이 파일이 확인하는 것은 메뉴와 행동 버튼이므로 응답은 비워 둔다.
vi.mock('@/api/market', async () => {
  const actual = await vi.importActual<typeof import('@/api/market')>('@/api/market')
  return {
    ...actual,
    fetchLatestMarketIndicators: vi.fn(() => Promise.resolve({ data: {}, errors: {} })),
  }
})

/** jsdom에 없는 브라우저 API를 채운다. 없으면 애니메이션 계층이 렌더링 도중 멈춘다. */
beforeAll(() => {
  if (!window.matchMedia) {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: () => {},
        removeListener: () => {},
        addEventListener: () => {},
        removeEventListener: () => {},
        dispatchEvent: () => false,
      }),
    })
  }

  class NoopObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
    takeRecords() {
      return []
    }
  }

  for (const name of ['IntersectionObserver', 'ResizeObserver'] as const) {
    if (!(name in window)) {
      Object.defineProperty(window, name, { configurable: true, value: NoopObserver })
      Object.defineProperty(globalThis, name, { configurable: true, value: NoopObserver })
    }
  }
})

/** FR-001이 첫 화면에 요구하는 메뉴 넷과 각 목적지다. */
const expectedMenus = [
  { label: '전략 진단', href: '/diagnosis/sectors' },
  { label: '시장 흐름', href: '/home' },
  { label: '배우기', href: '/learn' },
  { label: '내 기록', href: '/records' },
]

describe('LandingPage', () => {
  it('주요 메뉴 셋과 보조 메뉴 하나가 모두 보인다', () => {
    renderWithRouter(<LandingPage />)

    for (const menu of expectedMenus) {
      const link = screen.getByRole('link', { name: menu.label })
      expect(link.getAttribute('href')).toBe(menu.href)
    }
  })

  it("가장 중요한 행동 버튼은 '비중 비교하기' 하나이며 섹터 선택으로 간다", () => {
    renderWithRouter(<LandingPage />)

    const cta = screen.getByRole('link', { name: /비중 비교하기/ })
    expect(cta.getAttribute('href')).toBe('/diagnosis/sectors')

    // 행동 줄에서 다른 화면으로 보내는 것은 이 버튼 하나뿐이다. 옆에 있는 '서비스 알아보기'는
    // 같은 페이지 안을 내려갈 뿐이라 이 제약이 세는 대상이 아니다.
    // 검사 범위를 행동 줄로 좁혀 둔다. 첫 화면의 다른 자리에 링크가 생겼다는 이유로 이 검사가
    // 깨지면 실패 메시지가 실제 원인을 가리키지 못한다.
    const actionRow = cta.parentElement
    expect(actionRow).not.toBeNull()
    expect(within(actionRow as HTMLElement).getAllByRole('link')).toHaveLength(1)
  })

  it("보조 버튼 '서비스 알아보기'는 화면을 떠나지 않는다", () => {
    renderWithRouter(<LandingPage />)

    const secondary = screen.getByRole('button', { name: /서비스 알아보기/ })
    // 링크가 아니라 버튼이라는 사실 자체가 다른 화면으로 보내지 않는다는 뜻이다.
    expect(secondary.tagName).toBe('BUTTON')
  })

  it('머리 영역의 주 버튼이 대시보드로 가지 않는다', () => {
    renderWithRouter(<LandingPage />)

    // 이 자리를 '비중 비교하기'가 대신한다. 옛 버튼이 남으면 첫 화면의 주된 행동이 둘이 된다.
    expect(screen.queryByRole('link', { name: /시장 살펴보기/ })).toBeNull()
  })

  it('소개 섹션의 링크는 머리 영역을 지나야 나오므로 그대로 둔다', () => {
    renderWithRouter(<LandingPage />)

    expect(screen.getByRole('link', { name: /시장 분석 보러 가기/ })).toBeDefined()
    expect(screen.getByRole('link', { name: /백테스트 시작하기/ })).toBeDefined()
  })

  it('소개 섹션은 그대로 남는다', () => {
    renderWithRouter(<LandingPage />)

    expect(screen.getByRole('heading', { name: /시장을 분석합니다/ })).toBeDefined()
    expect(screen.getByRole('heading', { name: /과거에서 실험해보세요/ })).toBeDefined()
  })
})

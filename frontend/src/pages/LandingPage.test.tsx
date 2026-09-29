import { readdirSync, readFileSync } from 'node:fs'
import { screen, within } from '@testing-library/react'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
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

// 둘째 칸의 시장 흐름 창도 자료를 읽는다. 이 파일에서는 실패로 두고 창의 자세한 동작은
// MarketFlowPreview.test.tsx가 확인한다.
vi.mock('@/api/marketFlow', () => ({
  fetchMarketFlowFields: vi.fn(() => Promise.reject(new Error('offline'))),
  fetchMarketFlowField: vi.fn(() => Promise.reject(new Error('offline'))),
}))

// 전략 카드는 등록표에서 읽는다(003 FR-005). 이름을 코드에 두지 않으므로 응답으로 준다.
vi.mock('@/api/strategyRegistry', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/strategyRegistry')>('@/api/strategyRegistry')
  return {
    ...actual,
    fetchStrategyRegistry: vi.fn(() =>
      Promise.resolve({
        registryVersion: 'test',
        provisional: false,
        analysisConditions: {},
        strategies: [
          { strategyId: 'EQUAL_WEIGHT', name: '동일 비중', description: '설명', selectable: true },
        ],
      }),
    ),
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
  { label: '시장 흐름', href: '/market-flow' },
  { label: '배우기', href: '/learn' },
  { label: '내 기록', href: '/records' },
]

/** 첫 화면도 공용 상단 바(AppHeader)를 쓰므로 그것이 읽는 인증 상태를 최소로 준다. */
function renderLanding() {
  const authValue: AuthContextValue = {
    status: 'anonymous',
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
  }
  return renderWithRouter(
    <AuthContext.Provider value={authValue}>
      <LandingPage />
    </AuthContext.Provider>,
  )
}

describe('LandingPage', () => {
  it('주요 메뉴 셋과 보조 메뉴 하나가 모두 보인다', () => {
    renderLanding()

    for (const menu of expectedMenus) {
      const link = screen.getByRole('link', { name: menu.label })
      expect(link.getAttribute('href')).toBe(menu.href)
    }
  })

  it("가장 중요한 행동 버튼은 '전략 진단하기' 하나이며 섹터 선택으로 간다", () => {
    renderLanding()

    const cta = screen.getByRole('link', { name: /전략 진단하기/ })
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
    renderLanding()

    const secondary = screen.getByRole('button', { name: /서비스 알아보기/ })
    // 링크가 아니라 버튼이라는 사실 자체가 다른 화면으로 보내지 않는다는 뜻이다.
    expect(secondary.tagName).toBe('BUTTON')
  })

  it('머리 영역의 주 버튼이 대시보드로 가지 않는다', () => {
    renderLanding()

    // 이 자리를 '전략 진단하기'가 대신한다. 옛 버튼이 남으면 첫 화면의 주된 행동이 둘이 된다.
    expect(screen.queryByRole('link', { name: /시장 살펴보기/ })).toBeNull()
  })

  it('소개 섹션의 링크가 헤더 메뉴와 같은 곳을 가리킨다', () => {
    // 002 T024. 존재만 보면 목적지가 옛 화면으로 남아 있어도 통과한다 — 실제로 그랬다.
    renderLanding()

    expect(screen.getByRole('link', { name: /시장 분석 보러 가기/ }).getAttribute('href')).toBe('/market-flow')
    expect(screen.getByRole('link', { name: /백테스트 시작하기/ }).getAttribute('href')).toBe('/diagnosis/sectors')
  })

  it('첫 화면 코드 어디에도 옛 경로로 보내는 링크가 없다', () => {
    // 전략을 골라야 나오는 링크가 하나 있어 화면을 그려 보는 검사로는 닿지 않는다. 실제로
    // 그 링크가 옛 경로로 남아 있었다. 소스를 직접 본다.
    const dir = 'src/components/landing'
    const stale = readdirSync(dir)
      .filter((name) => name.endsWith('.tsx'))
      .flatMap((name) => {
        const source = readFileSync(`${dir}/${name}`, 'utf8')
        return [...source.matchAll(/to="(\/dashboard|\/backtest)"/g)].map((hit) => `${name}: ${hit[1]}`)
      })
    expect(stale).toEqual([])
  })

  it('첫 화면에 옛 대시보드·백테스트 경로로 가는 링크가 없다', () => {
    // 그 두 경로와 화면은 S15P21E205-416에서 저장소에서 없앴다. 링크가 되살아나면 사용자는
    // 없는 주소로 간다.
    renderLanding()

    const stale = screen
      .getAllByRole('link')
      .map((link) => link.getAttribute('href'))
      .filter((href) => href === '/dashboard' || href === '/backtest')
    expect(stale).toEqual([])
  })

  it('전략 카드의 이름을 등록표 응답에서 읽는다', async () => {
    // 이름을 코드에 상수로 두면 등록표에 없는 전략을 첫 화면이 광고하게 된다(003 FR-005).
    renderLanding()

    expect(await screen.findByRole('heading', { name: '동일 비중' })).toBeDefined()
  })

  it('첫 화면이 실행 근거 없는 성과 수치를 보이지 않는다', () => {
    // 예전에는 데모 자료의 `누적 수익 +124.8%`·CAGR·MDD·SHARPE를 첫 화면에 적었다. 어떤
    // 실행에서도 나온 적 없는 값이라 첫 화면이 성과를 단정하는 꼴이었다. 성과 수치는 자기
    // 실행을 가진 결과 화면에만 둔다.
    const { container } = renderLanding()

    const text = container.textContent ?? ''
    for (const forbidden of ['124.8', 'CAGR', 'MDD', 'SHARPE', '누적 수익']) {
      expect(text).not.toContain(forbidden)
    }
  })

  it('소개 섹션은 그대로 남는다', () => {
    renderLanding()

    expect(screen.getByRole('heading', { name: /시장을 분석합니다/ })).toBeDefined()
    expect(screen.getByRole('heading', { name: /전략을 실험해보세요/ })).toBeDefined()
  })
})

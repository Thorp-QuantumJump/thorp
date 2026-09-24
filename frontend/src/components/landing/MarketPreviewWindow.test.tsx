import { fireEvent, render, screen } from '@testing-library/react'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { marketMock } from '@/data/marketMock'
import { MarketPreviewWindow } from './MarketPreviewWindow'

// 실제 흐름을 받지 못한 상태(예시 흐름)로 그린다. 이 파일이 보는 것은 칸의 글자다.
vi.mock('@/api/market', () => ({
  fetchMarketSeries: vi.fn(() => Promise.reject(new Error('offline'))),
}))

/** jsdom에는 없다. 창이 화면 안에 있는지 보는 데 쓴다. */
beforeAll(() => {
  if ('IntersectionObserver' in window) return
  class NoopObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
    takeRecords() {
      return []
    }
  }
  Object.defineProperty(window, 'IntersectionObserver', { configurable: true, value: NoopObserver })
  Object.defineProperty(globalThis, 'IntersectionObserver', { configurable: true, value: NoopObserver })
})

describe('MarketPreviewWindow', () => {
  it('지표 칸을 여러 번 바꿔 골라도 칸마다 값과 변화가 하나씩만 있다', () => {
    // 예전에는 한 칸 안의 두 요소가 같은 key를 써서, 고름이 바뀔 때마다 옛 값이 치워지지 않고 쌓였다.
    render(<MarketPreviewWindow indicators={marketMock.marketIndicators} />)

    const tickers = () => screen.getAllByRole('button', { pressed: undefined }).filter((button) => button.hasAttribute('aria-pressed'))
    for (const index of [1, 2, 3, 0, 2, 1, 3]) fireEvent.click(tickers()[index])

    for (const ticker of tickers()) {
      expect(ticker.querySelectorAll('strong')).toHaveLength(1)
      expect(ticker.querySelectorAll('em')).toHaveLength(1)
    }
    expect(tickers()[3].getAttribute('aria-pressed')).toBe('true')
  })
})

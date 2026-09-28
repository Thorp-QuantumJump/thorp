import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { fetchMarketSeries } from '@/api/market'
import type { MarketSeriesCode, MarketSeriesResponse } from '@/types/marketApi'
import { MarketNow } from './MarketNow'

vi.mock('@/api/market', () => ({ fetchMarketSeries: vi.fn() }))

function series(code: MarketSeriesCode, values: number[]): MarketSeriesResponse {
  return {
    code,
    name: code,
    unit: 'pt',
    frequency: 'daily',
    points: values.map((value, index) => ({ date: `2026-09-2${index}`, value })),
    retrievedAt: '2026-09-23T00:00:00Z',
    source: { provider: 'KRX', datasetName: '지수' },
    status: 'ok',
    statusMessage: null,
  }
}

describe('MarketNow (002 FR-018, 009 FR-017)', () => {
  it('지수마다 이름 옆에 값과 직전 거래일 대비 변화를 오르내림 색으로 보인다', async () => {
    vi.mocked(fetchMarketSeries).mockImplementation(async (code) =>
      code === 'KOSPI' ? series('KOSPI', [2600, 2650]) : series('VKOSPI200', [20, 18]),
    )
    const { container } = render(<MarketNow />)

    expect(await screen.findByText('KOSPI')).toBeTruthy()
    const readings = container.querySelectorAll('[data-direction]')
    expect(readings[0].getAttribute('data-direction')).toBe('up')
    expect(readings[0].textContent).toContain('2,650')
    expect(readings[0].textContent).toContain('+1.9%')
    expect(readings[1].getAttribute('data-direction')).toBe('down')
    // 출처와 기준일은 카드 아래 한 줄이다.
    expect(screen.getByText(/기준 · 출처 KRX/)).toBeTruthy()
  })

  it('한 지수만 받으면 그 카드만 보이고 출처도 받은 쪽으로 밝힌다', async () => {
    vi.mocked(fetchMarketSeries).mockImplementation(async (code) => {
      if (code === 'VKOSPI200') throw new Error('offline')
      return series('KOSPI', [2600, 2650])
    })
    const { container } = render(<MarketNow />)

    expect(await screen.findByText('KOSPI')).toBeTruthy()
    expect(screen.queryByText('VKOSPI 200')).toBeNull()
    expect(container.querySelectorAll('[data-direction]')).toHaveLength(1)
    expect(screen.getByText('2026.09.21 기준 · 출처 KRX')).toBeTruthy()
  })

  it('받지 못한 지수는 빼고, 모두 못 받으면 한 문장만 남긴다', async () => {
    vi.mocked(fetchMarketSeries).mockRejectedValue(new Error('offline'))
    render(<MarketNow />)

    expect(await screen.findByText('국내 시장 참고 정보를 지금은 불러올 수 없습니다.')).toBeTruthy()
  })
})

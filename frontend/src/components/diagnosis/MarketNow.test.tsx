import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMarketSeries } from '@/api/market'
import { fetchPriceSeries } from '@/api/sectors'
import type { MarketSeriesCode, MarketSeriesResponse } from '@/types/marketApi'
import type { SectorSummary } from '@/types/sector'
import { MarketNow } from './MarketNow'

vi.mock('@/api/market', () => ({ fetchMarketSeries: vi.fn() }))
vi.mock('@/api/sectors', () => ({ fetchPriceSeries: vi.fn() }))

const SEMICONDUCTOR: SectorSummary = {
  code: 'SEMICONDUCTOR',
  name: '반도체',
  classification: 'INDUSTRY',
  representativeEtf: {
    instrumentId: '091160',
    name: 'KODEX 반도체',
    listedOn: '2006-06-27',
    benchmarkIndex: 'KRX 반도체',
  },
}

const TITLE = <h2>지금 국내 시장</h2>

function series(code: MarketSeriesCode, values: number[]): MarketSeriesResponse {
  return {
    code,
    name: code,
    unit: 'pt',
    frequency: 'daily',
    points: values.map((value, index) => ({
      date: `2026-09-2${index}`,
      value,
    })),
    retrievedAt: '2026-09-23T00:00:00Z',
    source: { provider: 'KRX', datasetName: '지수' },
    status: 'ok',
    statusMessage: null,
  }
}

describe('MarketNow (002 FR-018, 009 FR-017)', () => {
  beforeEach(() => {
    vi.mocked(fetchPriceSeries).mockResolvedValue({
      status: 'failed',
      kind: 'network',
      code: null,
      message: 'offline',
      retryable: true,
    })
  })

  it('지수마다 이름 옆에 값과 직전 거래일 대비 변화를 오르내림 색으로 보인다', async () => {
    vi.mocked(fetchMarketSeries).mockImplementation(async (code) =>
      code === 'KOSPI' ? series('KOSPI', [2600, 2650]) : series(code, [20, 18]),
    )
    const { container } = render(<MarketNow title={TITLE} sectors={[]} />)

    expect((await screen.findAllByText('KOSPI')).length).toBeGreaterThan(0)
    const readings = container.querySelectorAll('ul:not([aria-hidden]) [data-direction]')
    expect(readings[0].getAttribute('data-direction')).toBe('up')
    expect(readings[0].textContent).toContain('2,650')
    expect(readings[0].textContent).toContain('+1.9%')
    expect(readings[1].getAttribute('data-direction')).toBe('down')
    // 출처와 기준일은 제목 아래 한 줄이다.
    expect(screen.getByText('출처 KRX')).toBeTruthy()
  })

  it('한 지수만 받으면 그 카드만 보이고 출처도 받은 쪽으로 밝힌다', async () => {
    vi.mocked(fetchMarketSeries).mockImplementation(async (code) => {
      if (code !== 'KOSPI') throw new Error('offline')
      return series('KOSPI', [2600, 2650])
    })
    const { container } = render(<MarketNow title={TITLE} sectors={[]} />)

    expect((await screen.findAllByText('KOSPI')).length).toBeGreaterThan(0)
    expect(screen.queryByText('VKOSPI 200')).toBeNull()
    expect(container.querySelectorAll('ul:not([aria-hidden]) li')).toHaveLength(1)
    expect(screen.getByText('2026.09.21 기준')).toBeTruthy()
  })

  it('섹터 대표 ETF의 최근 종가와 직전 거래일 대비 변화를 지수 뒤에 잇는다', async () => {
    vi.mocked(fetchMarketSeries).mockRejectedValue(new Error('offline'))
    vi.mocked(fetchPriceSeries).mockResolvedValue({
      status: 'loaded',
      series: {
        instrumentId: '091160',
        datasetId: 'd1',
        dataDeadline: '2026-09-30T15:30:00+09:00',
        isSample: false,
        points: [
          {
            date: '2026-09-29',
            adjustedClose: '50000.00000000',
            tradingSuspended: false,
            priceCarriedForward: false,
          },
          {
            date: '2026-09-30',
            adjustedClose: '51000.00000000',
            tradingSuspended: false,
            priceCarriedForward: false,
          },
        ],
      },
    })
    const { container } = render(<MarketNow title={TITLE} sectors={[SEMICONDUCTOR]} />)

    expect((await screen.findAllByText('KODEX 반도체')).length).toBeGreaterThan(0)
    const reading = container.querySelector('ul:not([aria-hidden]) [data-direction]')
    expect(reading?.getAttribute('data-direction')).toBe('up')
    expect(reading?.textContent).toContain('51,000')
    expect(reading?.textContent).toContain('+2.0%')
    expect(screen.getByText('2026.09.30 기준')).toBeTruthy()
    expect(vi.mocked(fetchPriceSeries).mock.calls.at(-1)?.[0]).toBe('SEMICONDUCTOR')
  })

  it('지수와 ETF의 기준일이 다르면 둘을 나눠 밝히고, 반도체 ETF를 맨 앞에 둔다', async () => {
    vi.mocked(fetchMarketSeries).mockImplementation(async (code) => series(code, [2600, 2650]))
    vi.mocked(fetchPriceSeries).mockResolvedValue({
      status: 'loaded',
      series: {
        instrumentId: '091160',
        datasetId: 'd1',
        dataDeadline: '2026-09-15T15:30:00+09:00',
        isSample: false,
        points: [{ date: '2026-09-15', adjustedClose: '50000', tradingSuspended: false, priceCarriedForward: false }],
      },
    })
    const BANK: SectorSummary = {
      ...SEMICONDUCTOR,
      code: 'BANK',
      representativeEtf: { ...SEMICONDUCTOR.representativeEtf, name: 'KODEX 은행' },
    }
    render(<MarketNow title={TITLE} sectors={[BANK, SEMICONDUCTOR]} />)

    expect(await screen.findByText('지수 2026.09.21 · ETF 2026.09.15 기준')).toBeTruthy()
    expect(vi.mocked(fetchPriceSeries).mock.calls.map((call) => call[0])).toEqual(['SEMICONDUCTOR', 'BANK'])
  })

  it('받지 못한 지수는 빼고, 모두 못 받으면 한 문장만 남긴다', async () => {
    vi.mocked(fetchMarketSeries).mockRejectedValue(new Error('offline'))
    render(<MarketNow title={TITLE} sectors={[]} />)

    expect((await screen.findAllByText('국내 시장 참고 정보를 지금은 불러올 수 없습니다.')).length).toBeGreaterThan(0)
  })
})

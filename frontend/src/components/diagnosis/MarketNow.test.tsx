import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMarketSeries } from '@/api/market'
import { fetchLatestPrices, type LatestPricesResult } from '@/api/sectors'
import type { MarketSeriesCode, MarketSeriesResponse } from '@/types/marketApi'
import type { LatestPrice, SectorSummary } from '@/types/sector'
import { MarketNow } from './MarketNow'

vi.mock('@/api/market', () => ({ fetchMarketSeries: vi.fn() }))
vi.mock('@/api/sectors', () => ({ fetchLatestPrices: vi.fn() }))

function sector(code: string, etfName: string): SectorSummary {
  return {
    code,
    name: etfName.replace('KODEX ', ''),
    classification: 'INDUSTRY',
    representativeEtf: { instrumentId: `${code}.XKRX`, name: etfName, listedOn: '2010-01-04', benchmarkIndex: 'KRX' },
  }
}

const SEMICONDUCTOR = sector('SEMICONDUCTOR', 'KODEX 반도체')
const BANK = sector('BANK', 'KODEX 은행')

const TITLE = <h2>지금 국내 시장</h2>

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

function price(sectorCode: string, name: string, overrides: Partial<LatestPrice> = {}): LatestPrice {
  return {
    sectorCode,
    instrumentId: `${sectorCode}.XKRX`,
    name,
    date: '2026-09-30',
    adjustedClose: '51000.00000000',
    tradingSuspended: false,
    priceCarriedForward: false,
    previousDate: '2026-09-29',
    previousAdjustedClose: '50000.00000000',
    ...overrides,
  }
}

function latest(prices: LatestPrice[]): LatestPricesResult {
  return {
    status: 'loaded',
    latest: {
      mapVersion: 'SEM-1',
      datasetId: 'd1',
      dataDeadline: '2026-09-30T06:30:00Z',
      isSample: false,
      prices,
      missingSectorCodes: [],
    },
  }
}

/** 보이는 첫 줄만 고른다. 넘칠 때 이어 붙이는 두 번째 줄은 보조 기술에 숨겨져 있다. */
function visibleCards(container: HTMLElement) {
  return [...container.querySelectorAll('ul:not([aria-hidden]) li')]
}

describe('MarketNow (002 FR-018, 009 FR-017)', () => {
  beforeEach(() => {
    vi.mocked(fetchLatestPrices).mockResolvedValue({ status: 'failed', kind: 'network', message: 'offline' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
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
    // 기준일과 출처는 제목 아래에 있다.
    expect(screen.getByText('2026.09.21 기준')).toBeTruthy()
    expect(screen.getByText('출처 KRX')).toBeTruthy()
  })

  it('한 지수만 받으면 그 카드만 보인다', async () => {
    vi.mocked(fetchMarketSeries).mockImplementation(async (code) => {
      if (code !== 'KOSPI') throw new Error('offline')
      return series('KOSPI', [2600, 2650])
    })
    const { container } = render(<MarketNow title={TITLE} sectors={[]} />)

    expect((await screen.findAllByText('KOSPI')).length).toBeGreaterThan(0)
    expect(screen.queryByText('VKOSPI 200')).toBeNull()
    expect(visibleCards(container)).toHaveLength(1)
  })

  it('섹터 대표 ETF는 최근 가격 한 번으로 받고, 반도체를 맨 앞에 두며, 출처를 KRX 통계정보로 밝힌다', async () => {
    vi.mocked(fetchMarketSeries).mockRejectedValue(new Error('offline'))
    vi.mocked(fetchLatestPrices).mockResolvedValue(
      latest([
        price('BANK', 'KODEX 은행', { adjustedClose: '9000', previousAdjustedClose: '10000' }),
        price('SEMICONDUCTOR', 'KODEX 반도체'),
      ]),
    )
    const { container } = render(<MarketNow title={TITLE} sectors={[BANK, SEMICONDUCTOR]} />)

    expect((await screen.findAllByText('KODEX 반도체')).length).toBeGreaterThan(0)
    const cards = visibleCards(container)
    expect(cards.map((card) => card.firstChild?.textContent)).toEqual(['KODEX 반도체', 'KODEX 은행'])
    expect(cards[0].textContent).toContain('51,000')
    expect(cards[0].textContent).toContain('+2.0%')
    expect(cards[1].textContent).toContain('−10.0%')
    expect(screen.getByText('2026.09.30 기준')).toBeTruthy()
    expect(screen.getByText('출처 한국거래소 통계정보')).toBeTruthy()
    expect(fetchLatestPrices).toHaveBeenCalledTimes(1)
  })

  it('지수와 ETF의 기준일이 다르면 둘을 나눠 밝힌다', async () => {
    vi.mocked(fetchMarketSeries).mockImplementation(async (code) => series(code, [2600, 2650]))
    vi.mocked(fetchLatestPrices).mockResolvedValue(
      latest([price('SEMICONDUCTOR', 'KODEX 반도체', { date: '2026-09-15' })]),
    )
    render(<MarketNow title={TITLE} sectors={[SEMICONDUCTOR]} />)

    expect((await screen.findAllByText('지수 2026.09.21 · ETF 2026.09.15 기준')).length).toBeGreaterThan(0)
  })

  it('마지막 날이 거래정지이거나 직전 값을 옮겨 적은 날이면 등락률 대신 그 사실을 적는다', async () => {
    vi.mocked(fetchMarketSeries).mockRejectedValue(new Error('offline'))
    vi.mocked(fetchLatestPrices).mockResolvedValue(
      latest([
        price('SEMICONDUCTOR', 'KODEX 반도체', { tradingSuspended: true, priceCarriedForward: true }),
        price('BANK', 'KODEX 은행', { priceCarriedForward: true }),
      ]),
    )
    const { container } = render(<MarketNow title={TITLE} sectors={[SEMICONDUCTOR, BANK]} />)

    expect((await screen.findAllByText('거래정지')).length).toBeGreaterThan(0)
    const cards = visibleCards(container)
    expect(cards[1].textContent).toContain('직전 값')
    for (const card of cards) {
      expect(card.textContent).not.toContain('%')
      expect(card.querySelector('strong')?.parentElement?.hasAttribute('data-direction')).toBe(false)
    }
  })

  it('직전 값이 없으면 등락률을 적지 않는다', async () => {
    vi.mocked(fetchMarketSeries).mockRejectedValue(new Error('offline'))
    vi.mocked(fetchLatestPrices).mockResolvedValue(
      latest([price('SEMICONDUCTOR', 'KODEX 반도체', { previousDate: null, previousAdjustedClose: null })]),
    )
    const { container } = render(<MarketNow title={TITLE} sectors={[SEMICONDUCTOR]} />)

    expect((await screen.findAllByText('KODEX 반도체')).length).toBeGreaterThan(0)
    expect(visibleCards(container)[0].textContent).not.toContain('%')
  })

  it('지수는 ETF 응답을 기다리지 않고 먼저 보인다', async () => {
    vi.mocked(fetchMarketSeries).mockImplementation(async (code) => series(code, [2600, 2650]))
    vi.mocked(fetchLatestPrices).mockReturnValue(new Promise(() => {}))
    render(<MarketNow title={TITLE} sectors={[SEMICONDUCTOR]} />)

    expect((await screen.findAllByText('KOSPI')).length).toBeGreaterThan(0)
    expect(screen.queryByText('KODEX 반도체')).toBeNull()
  })

  it('섹터 목록이 바뀌면 새 응답이 올 때까지 이전 ETF 카드를 보이지 않는다', async () => {
    vi.mocked(fetchMarketSeries).mockRejectedValue(new Error('offline'))
    vi.mocked(fetchLatestPrices).mockResolvedValueOnce(latest([price('SEMICONDUCTOR', 'KODEX 반도체')]))
    const { rerender } = render(<MarketNow title={TITLE} sectors={[SEMICONDUCTOR]} />)
    expect((await screen.findAllByText('KODEX 반도체')).length).toBeGreaterThan(0)

    vi.mocked(fetchLatestPrices).mockReturnValueOnce(new Promise(() => {}))
    rerender(<MarketNow title={TITLE} sectors={[BANK]} />)

    expect(screen.queryByText('KODEX 반도체')).toBeNull()
    expect(screen.getByRole('status').textContent).toBe('국내 시장 자료를 불러오는 중입니다.')
  })

  it('화면을 떠나면 지수와 ETF 요청을 모두 취소한다', () => {
    vi.mocked(fetchMarketSeries).mockReturnValue(new Promise(() => {}))
    vi.mocked(fetchLatestPrices).mockReturnValue(new Promise(() => {}))
    const { unmount } = render(<MarketNow title={TITLE} sectors={[SEMICONDUCTOR]} />)
    unmount()

    const signals = [
      ...vi.mocked(fetchMarketSeries).mock.calls.map((call) => call[1]),
      ...vi.mocked(fetchLatestPrices).mock.calls.map((call) => call[0]),
    ]
    expect(signals).toHaveLength(5)
    expect(signals.every((signal) => signal?.aborted)).toBe(true)
  })

  /** jsdom은 배치를 하지 않으므로 한 줄의 폭과 창의 폭을 직접 정하고, 관찰자는 붙자마자 한 번 알린다. */
  function layout(listWidth: number, viewportWidth: number) {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(private readonly callback: () => void) {}
        observe() {
          this.callback()
        }
        disconnect() {}
      },
    )
    vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(listWidth)
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(viewportWidth)
  }

  it('카드가 적어 한 줄이 창보다 좁으면 창을 채울 만큼 반복해 흘린다', async () => {
    vi.mocked(fetchMarketSeries).mockImplementation(async (code) => series(code, [2600, 2650]))
    layout(300, 800)
    const { container } = render(<MarketNow title={TITLE} sectors={[]} />)

    // 한 묶음은 3번 반복(900px ≥ 800px)이고, 묶음 두 벌을 이으므로 줄은 6개다. 보조 기술에는 첫 줄만 읽힌다.
    await waitFor(() => expect(container.querySelectorAll('ul')).toHaveLength(6))
    expect(container.querySelectorAll('ul:not([aria-hidden])')).toHaveLength(1)
    const track = screen.getByRole('region', { name: '국내 대표 지수와 ETF' }).firstElementChild as HTMLElement
    // 카드 한 장이 지나는 시간을 그대로 두도록 반복한 만큼 한 바퀴가 길어진다(4장 × 3번 × 4초).
    expect(track.style.getPropertyValue('--ticker-duration')).toBe('48s')
  })

  it('한 줄이 창보다 넓으면 반복하지 않고 두 벌만 이어 흘린다', async () => {
    vi.mocked(fetchMarketSeries).mockImplementation(async (code) => series(code, [2600, 2650]))
    layout(1200, 800)
    const { container } = render(<MarketNow title={TITLE} sectors={[]} />)

    expect((await screen.findAllByText('KOSPI')).length).toBe(2)
    expect(container.querySelectorAll('ul[aria-hidden="true"]')).toHaveLength(1)
  })

  it('폭을 잴 수 없으면 두 벌만 이어 둔다', async () => {
    vi.mocked(fetchMarketSeries).mockImplementation(async (code) => series(code, [2600, 2650]))
    const { container } = render(<MarketNow title={TITLE} sectors={[]} />)

    expect((await screen.findAllByText('KOSPI')).length).toBe(2)
    expect(container.querySelectorAll('ul')).toHaveLength(2)
  })

  it('밝힐 기준일이 없으면 날짜 줄을 빼고 출처만 남긴다', async () => {
    vi.mocked(fetchMarketSeries).mockImplementation(async (code) => ({
      ...series(code, [2600, 2650]),
      points: [{ date: '', value: 2650 }],
    }))
    render(<MarketNow title={TITLE} sectors={[]} />)

    expect((await screen.findAllByText('KOSPI')).length).toBeGreaterThan(0)
    expect(screen.queryByText(/기준$/)).toBeNull()
    expect(screen.getByText('출처 KRX')).toBeTruthy()
  })

  it('받지 못한 지표는 빼고, 모두 못 받으면 한 문장만 남긴다', async () => {
    vi.mocked(fetchMarketSeries).mockRejectedValue(new Error('offline'))
    render(<MarketNow title={TITLE} sectors={[SEMICONDUCTOR]} />)

    expect((await screen.findAllByText('국내 시장 참고 정보를 지금은 불러올 수 없습니다.')).length).toBeGreaterThan(0)
  })
})

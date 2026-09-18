import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchMarketIndicatorsFor } from '@/api/market'
import type { MarketIndicatorCode, MarketIndicatorResponse } from '@/types/marketApi'
import { DomesticMarketReference } from './DomesticMarketReference'

vi.mock('@/api/market', () => ({ fetchMarketIndicatorsFor: vi.fn() }))

function indicator(code: MarketIndicatorCode, name: string, value: number): MarketIndicatorResponse {
  return {
    code, name, group: 'market', value, unit: 'pt', frequency: 'daily',
    observationDate: '2026-09-16', retrievedAt: '2026-09-17T00:10:00Z',
    source: { provider: '한국거래소 통계정보', datasetName: 'kospi_dd_trd' },
    status: 'ok', statusMessage: null,
  }
}

describe('DomesticMarketReference', () => {
  afterEach(() => vi.clearAllMocks())

  it('가격 추세와 변동성을 값·기준일·출처와 함께 보인다', async () => {
    vi.mocked(fetchMarketIndicatorsFor).mockResolvedValue({
      data: {
        KOSPI: indicator('KOSPI', '코스피', 2745.12),
        VKOSPI200: indicator('VKOSPI200', '코스피200 변동성', 15.4),
      },
      errors: {},
    })
    render(<DomesticMarketReference />)

    expect(await screen.findByText('코스피')).toBeTruthy()
    expect(screen.getByText('코스피200 변동성')).toBeTruthy()
    expect(screen.getAllByText('2026.09.16 기준').length).toBe(2)
    expect(screen.getByText(/출처 · 한국거래소 통계정보/)).toBeTruthy()
  })

  /** 출처를 화면이 지어내면 언젠가 틀린 출처를 보이게 된다(FR-015). */
  it('출처는 응답이 준 이름을 그대로 쓴다', async () => {
    const fromEcos = { ...indicator('KOSPI', '코스피', 2745.12) }
    fromEcos.source = { provider: '한국은행 ECOS', datasetName: '다른 자료' }
    vi.mocked(fetchMarketIndicatorsFor).mockResolvedValue({ data: { KOSPI: fromEcos }, errors: {} })
    render(<DomesticMarketReference />)

    expect(await screen.findByText(/출처 · 한국은행 ECOS/)).toBeTruthy()
    expect(screen.queryByText(/한국거래소/)).toBeNull()
  })

  // VKOSPI200은 원천 승인에 따라 503이 날 수 있다(market-api.md). 하나가 빠져도 나머지는 보인다.
  it('한 지표가 빠져도 얻은 지표는 보인다', async () => {
    vi.mocked(fetchMarketIndicatorsFor).mockResolvedValue({
      data: { KOSPI: indicator('KOSPI', '코스피', 2745.12) },
      errors: { VKOSPI200: '원천 인증 거부' },
    })
    render(<DomesticMarketReference />)

    expect(await screen.findByText('코스피')).toBeTruthy()
    expect(screen.queryByText('코스피200 변동성')).toBeNull()
  })

  it('자료를 하나도 못 얻으면 이 영역만 사유를 보인다', async () => {
    vi.mocked(fetchMarketIndicatorsFor).mockResolvedValue({
      data: {}, errors: { KOSPI: '원천 인증 거부', VKOSPI200: '원천 인증 거부' },
    })
    render(<DomesticMarketReference />)

    expect(await screen.findByText(/지금은 불러올 수 없습니다/)).toBeTruthy()
  })

  it('조회가 통째로 실패해도 오류 경계로 떨어지지 않는다', async () => {
    vi.mocked(fetchMarketIndicatorsFor).mockRejectedValue(new Error('network'))
    render(<DomesticMarketReference />)

    expect(await screen.findByText(/지금은 불러올 수 없습니다/)).toBeTruthy()
  })

  /**
   * FR-017: 이 영역의 값은 경제환경 검색 입력에 쓰지 않는다.
   * props도 콜백도 없어서 값을 바깥으로 내보낼 통로가 없다는 것을 못 박는다.
   */
  it('값을 바깥으로 내보내는 통로가 없다', () => {
    expect(DomesticMarketReference.length).toBe(0)
  })
})

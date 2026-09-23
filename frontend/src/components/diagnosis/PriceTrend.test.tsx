import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { PriceSeriesResult } from '@/api/sectors'
import type { PriceSeries } from '@/types/sector'
import { PriceTrend } from './PriceTrend'

/**
 * 카드가 보여 주는 가격 흐름이다.
 *
 * <p>그림 자체는 단언하기 어려우므로 이 검사가 보는 것은 <b>그림이 말하지 않는 것</b>이다. 값이 아직
 * 없을 때 빈 선을 그리지 않는지, 이어 붙인 가격이 섞여 있다는 사실을 알리는지, 그리고 소리로만 듣는
 * 사용자에게 무엇을 보고 있는지 전해지는지다.
 */

function series(overrides: Partial<PriceSeries> = {}): PriceSeries {
  return {
    instrumentId: '117700.XKRX',
    datasetId: 'KRX_DAILY_20260922',
    dataDeadline: '2026-09-22T06:30:00Z',
    isSample: false,
    points: [
      {
        date: '2026-09-16',
        adjustedClose: '10000.00000000',
        tradingSuspended: false,
        priceCarriedForward: false,
      },
      {
        date: '2026-09-17',
        adjustedClose: '10250.50000000',
        tradingSuspended: false,
        priceCarriedForward: false,
      },
    ],
    ...overrides,
  }
}

function renderTrend(result: PriceSeriesResult | null, onReload = vi.fn()) {
  render(<PriceTrend name="KODEX 건설" result={result} onReload={onReload} />)
  return { onReload }
}

function note(): string {
  return document.querySelector('[data-slot="chart-note"]')?.textContent ?? ''
}

describe('가격 흐름', () => {
  it('불러오는 동안에도 빈 자리가 아니라 상태를 알린다', () => {
    renderTrend(null)

    expect(screen.getByRole('status').textContent).toContain('불러오는 중')
    expect(document.querySelector('[data-slot="chart-canvas"]')).toBeNull()
  })

  it('자료가 아직 없으면 빈 선 대신 한 문장과 다시 불러오기를 보인다', async () => {
    const user = userEvent.setup()
    const { onReload } = renderTrend({ status: 'notReady', message: '준비 중' })

    // 점 없는 선은 "값이 없다"가 아니라 "움직임이 없었다"로 읽힌다(원칙 VI).
    expect(document.querySelector('[data-slot="chart-canvas"]')).toBeNull()
    expect(screen.getByText(/가격 자료가 아직 준비 중입니다/)).toBeTruthy()

    await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
    expect(onReload).toHaveBeenCalledTimes(1)
  })

  it('다시 불러도 달라지지 않는 실패에는 행동을 두지 않는다', () => {
    renderTrend({
      status: 'failed',
      kind: 'api',
      code: 'SECTOR_NOT_FOUND',
      message: '없는 섹터',
      retryable: false,
    })

    expect(screen.getByText(/불러오지 못했습니다/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: '다시 불러오기' })).toBeNull()
  })

  it('그린 선이 무엇인지 소리로만 듣는 사용자에게도 전한다', () => {
    renderTrend({ status: 'loaded', series: series() })

    // 세로 눈금을 적지 않으므로 기간과 거래일 수를 말로 실어 준다.
    const canvas = screen.getByRole('img')
    expect(canvas.getAttribute('aria-label')).toContain('KODEX 건설')
    expect(canvas.getAttribute('aria-label')).toContain('2026-09-16')
    expect(canvas.getAttribute('aria-label')).toContain('2026-09-17')
    expect(canvas.getAttribute('aria-label')).toContain('2거래일')
  })

  it('무엇을 그린 값인지 그림 아래에 적는다', () => {
    renderTrend({ status: 'loaded', series: series() })

    expect(note()).toContain('2026-09-16')
    expect(note()).toContain('수정종가')
    // 이어 붙인 날이 없으면 그 문장도 없다.
    expect(note()).not.toContain('이어 붙인')
  })

  it('이어 붙인 가격이 섞여 있으면 그 사실을 알린다', () => {
    renderTrend({
      status: 'loaded',
      series: series({
        points: [
          {
            date: '2026-09-16',
            adjustedClose: '10000.00000000',
            tradingSuspended: false,
            priceCarriedForward: false,
          },
          {
            date: '2026-09-17',
            adjustedClose: '10000.00000000',
            tradingSuspended: true,
            priceCarriedForward: true,
          },
        ],
      }),
    })

    // 그 구간의 평평한 선은 "값이 움직이지 않았다"가 아니라 "거래가 없었다"이다(원칙 V).
    expect(note()).toContain('이어 붙인 날 1일')
  })

  it('예시 자료로 그린 선이면 그 사실을 함께 적는다', () => {
    renderTrend({ status: 'loaded', series: series({ isSample: true }) })

    expect(note()).toContain('예시 자료')
  })
})

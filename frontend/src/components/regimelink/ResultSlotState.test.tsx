import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { RegimeLinkView } from '@/types/regimeLink'
import { PostWindowCompleteness, ResultSlotState } from './ResultSlotState'

function view(overrides: Partial<RegimeLinkView> = {}): RegimeLinkView {
  return {
    basis: { kind: 'HISTORICAL_REVISION' },
    linkId: 'RL-1',
    publicToken: '9f2c'.repeat(8),
    caseId: 'SNAP~2012-03',
    scenario: 'CONTINUOUS',
    status: 'COMPLETED',
    postWindow: {
      d0: '2012-04-02',
      d21: '2012-05-02',
      d63: '2012-07-03',
      completedTradingDays: 63,
      complete21: true,
      complete63: true,
    },
    availability: { state: 'NORMAL' },
    runProgress: null,
    failure: null,
    result: null,
    ...overrides,
  }
}

describe('사후 구간 결과 자리의 상태', () => {
  it('자료 부족은 사유를 보이고 다시 시도를 두지 않는다', () => {
    render(
      <ResultSlotState
        link={view({ availability: { state: 'DATA_INSUFFICIENT', reason: 'UNLISTED' } })}
      />,
    )
    expect(screen.getByText('자료 부족')).toBeTruthy()
    expect(screen.getByText(/상장되지 않은/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull()
  })

  it('21거래일 미만은 확보한 거래일과 사유만 보이고 수치를 내지 않는다', () => {
    render(
      <ResultSlotState
        link={view({
          availability: { state: 'POST_INCOMPLETE', reason: 'RECENT' },
          postWindow: {
            d0: '2026-06-01',
            d21: null,
            d63: null,
            completedTradingDays: 12,
            complete21: false,
            complete63: false,
          },
        })}
      />,
    )
    expect(screen.getByText('사후 자료 미완성')).toBeTruthy()
    expect(screen.getByText('12거래일만 자료가 있습니다.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull()
  })

  it('분석 기간 밖은 확인 전 문구를 보인다', () => {
    render(<ResultSlotState link={view({ availability: { state: 'OUT_OF_PERIOD' } })} />)
    expect(screen.getByText('분석 기간 밖')).toBeTruthy()
  })

  it('실패는 서버 문장과 다시 시도를 함께 보이고 자료 부족과 섞이지 않는다', async () => {
    const onRetry = vi.fn()
    render(
      <ResultSlotState
        link={view({
          status: 'FAILED',
          failure: { code: 'RUN_FAILED', message: '계산을 마치지 못했습니다.', retryable: true },
        })}
        onRetry={onRetry}
      />,
    )
    expect(screen.getByText('계산을 마치지 못했습니다.')).toBeTruthy()
    expect(screen.queryByText('자료 부족')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(onRetry).toHaveBeenCalledWith('RL-1')
  })

  it('두 번째 결정적 실패에서는 다시 시도가 사라지고 안내가 바뀐다', () => {
    render(
      <ResultSlotState
        link={view({
          status: 'FAILED',
          failure: { code: 'INPUT_INCOMPLETE', message: '계산을 마치지 못했습니다.', retryable: false },
        })}
      />,
    )
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull()
    expect(screen.getByText(/일시적인 문제가 아닙니다/)).toBeTruthy()
  })

  it('대기·계산 중은 진행 상태를 보인다', () => {
    render(
      <ResultSlotState
        link={view({ status: 'RUNNING', runProgress: { status: 'RUNNING', progress: 0.4 } })}
      />,
    )
    expect(screen.getByText('계산하는 중입니다')).toBeTruthy()
  })

  it('여섯 상태가 서로 다른 DOM 표시를 갖는다', () => {
    const kinds = [
      view({ availability: { state: 'DATA_INSUFFICIENT', reason: 'UNLISTED' } }),
      view({ availability: { state: 'POST_INCOMPLETE', reason: 'RECENT' } }),
      view({ availability: { state: 'OUT_OF_PERIOD' } }),
      view({ status: 'FAILED', failure: { code: 'X', message: 'x', retryable: false } }),
      view({ status: 'QUEUED' }),
      view({ status: 'RUNNING' }),
    ].map((link) => {
      const { container, unmount } = render(<ResultSlotState link={link} />)
      const value = container.querySelector('[data-slot-state]')?.getAttribute('data-slot-state')
      unmount()
      return value
    })
    expect(new Set(kinds).size).toBe(6)
  })

  it('정상 결과 자리에는 아무것도 그리지 않는다', () => {
    const { container } = render(<ResultSlotState link={view()} />)
    expect(container.innerHTML).toBe('')
  })
})

describe('구간 온전함 덧말', () => {
  it('63거래일 미완성이면 길이를 밝힌다', () => {
    render(
      <PostWindowCompleteness
        link={view({
          postWindow: {
            d0: '2026-06-01',
            d21: '2026-06-30',
            d63: null,
            completedTradingDays: 34,
            complete21: true,
            complete63: false,
          },
        })}
      />,
    )
    expect(screen.getByText('34거래일 완성(미완성)')).toBeTruthy()
  })

  it('구간 안 상장폐지는 부분 구간과 폐지일을 밝힌다', () => {
    render(
      <PostWindowCompleteness
        link={view({
          availability: { state: 'NORMAL', partial: true, delistedOn: '2018-11-15' },
          postWindow: {
            d0: '2018-09-03',
            d21: '2018-10-04',
            d63: null,
            completedTradingDays: 51,
            complete21: true,
            complete63: false,
          },
        })}
      />,
    )
    expect(screen.getByText(/51거래일\(상장폐지로 부분 구간\)/)).toBeTruthy()
    expect(screen.getByText(/2018-11-15/)).toBeTruthy()
  })
})

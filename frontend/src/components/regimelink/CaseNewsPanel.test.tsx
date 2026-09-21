import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { NewsDigestResult } from '@/types/marketNews'
import { CaseNewsPanel } from './CaseNewsPanel'
import { observationRange } from './observationRange'

const api = vi.hoisted(() => ({ news: vi.fn() }))

vi.mock('@/api/marketNews', async (load) => ({
  ...(await load<typeof import('@/api/marketNews')>()),
  fetchMarketNewsDigests: api.news,
}))

function digests(overrides: Partial<NewsDigestResult> = {}): NewsDigestResult {
  return {
    context: 'HISTORICAL',
    state: 'READY',
    sectorId: null,
    from: null,
    to: null,
    lastUpdatedAt: null,
    stale: false,
    statusMessage: null,
    digests: [],
    ...overrides,
  }
}

beforeEach(() => {
  api.news.mockReset()
})

describe('당시 뉴스 영역', () => {
  it('관측 구간 3개월을 UTC 경계로 만든다', () => {
    const range = observationRange('2012-03')
    expect(range.from).toBe('2012-01-01T00:00:00.000Z')
    expect(range.to).toBe('2012-03-31T23:59:59.000Z')
    const days = (Date.parse(range.to) - Date.parse(range.from)) / 86_400_000
    expect(days).toBeLessThanOrEqual(93)
  })

  it('012 과거 구간 조회에 구간 인자를 넘긴다', async () => {
    api.news.mockResolvedValue(digests())
    render(<CaseNewsPanel endMonth="2012-03" />)
    await waitFor(() => expect(api.news).toHaveBeenCalled())
    expect(api.news).toHaveBeenLastCalledWith(null, expect.any(AbortSignal), {
      from: '2012-01-01T00:00:00.000Z',
      to: '2012-03-31T23:59:59.000Z',
    })
  })

  it('빈 상태는 012의 문구를 정상 화면으로 보인다', async () => {
    api.news.mockResolvedValue(
      digests({ state: 'EMPTY', statusMessage: '이 구간의 뉴스 묶음이 없습니다.' }),
    )
    render(<CaseNewsPanel endMonth="2012-03" />)
    expect(await screen.findByText('이 구간의 뉴스 묶음이 없습니다.')).toBeTruthy()
    expect(screen.queryByText(/불러오지 못했습니다/)).toBeNull()
  })

  it('조회 실패는 이 영역에만 남는다', async () => {
    api.news.mockRejectedValue(new Error('뉴스원 장애'))
    render(<CaseNewsPanel endMonth="2012-03" />)
    expect(await screen.findByText(/전략 결과는 그대로 볼 수 있습니다/)).toBeTruthy()
  })
})

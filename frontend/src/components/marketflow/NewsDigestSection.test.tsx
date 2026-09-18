import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchMarketNewsDigests } from '@/api/marketNews'
import type { NewsDigest, NewsDigestResult, NewsDigestState } from '@/types/marketNews'
import { NewsDigestSection } from './NewsDigestSection'

vi.mock('@/api/marketNews', () => ({ fetchMarketNewsDigests: vi.fn() }))

const digest: NewsDigest = {
  digestId: 'a'.repeat(32), representativeTitle: '반도체 수출 증가', factualSummary: '사실 요약',
  firstPublishedAt: '2026-09-16T23:00:00Z', publicationTimeStatus: 'VERIFIED',
  publisherCount: 3, sectors: ['SEMICONDUCTOR'], topics: ['수출'], importanceRuleVersion: 'v1',
  articles: [{
    articleId: 'b'.repeat(32), title: '기사', publisher: '매체',
    publishedAt: '2026-09-16T23:00:00Z', publicationTimeStatus: 'VERIFIED',
    originalUrl: 'https://example.test/news/1',
  }],
}

function result(state: NewsDigestState, digests: NewsDigest[] = []): NewsDigestResult {
  return {
    context: 'CURRENT', state, sectorId: null, from: null, to: null,
    lastUpdatedAt: '2026-09-17T00:10:00Z', stale: state === 'STALE',
    statusMessage: null, digests,
  }
}

describe('NewsDigestSection', () => {
  afterEach(() => vi.clearAllMocks())

  it('묶음을 대표 제목·요약·매체 수와 함께 보인다', async () => {
    vi.mocked(fetchMarketNewsDigests).mockResolvedValue(result('READY', [digest]))
    render(<NewsDigestSection sectorCode={null} />)

    expect(await screen.findByRole('link', { name: /반도체 수출 증가/ })).toBeTruthy()
    expect(screen.getByText('사실 요약')).toBeTruthy()
    expect(screen.getByText(/매체 3곳 · 2026.09.16/)).toBeTruthy()
  })

  /** 012가 정한 상태를 그대로 그린다. 009가 다시 재지 않는다. */
  it.each([
    ['EMPTY' as const, '지금 보여 드릴 뉴스 묶음이 없습니다.'],
    ['STALE' as const, '표시한 뉴스가 최근 갱신분이 아닙니다.'],
    ['PREPARING' as const, '뉴스 묶음을 준비하고 있습니다.'],
  ])('%s 상태를 오류가 아니라 상태 표시로 그린다', async (state, notice) => {
    vi.mocked(fetchMarketNewsDigests).mockResolvedValue(result(state))
    render(<NewsDigestSection sectorCode={null} />)

    expect(await screen.findByText(notice)).toBeTruthy()
  })

  it('012가 보낸 문장이 있으면 그것을 쓴다', async () => {
    vi.mocked(fetchMarketNewsDigests).mockResolvedValue({
      ...result('PREPARING'), statusMessage: '아직 수집되지 않았습니다.',
    })
    render(<NewsDigestSection sectorCode={null} />)

    expect(await screen.findByText('아직 수집되지 않았습니다.')).toBeTruthy()
  })

  /** 확인하지 못한 발행 시각을 확인한 것처럼 쓰지 않는다(012 계약, 원칙 VI). */
  it('발행 시각을 확인하지 못한 묶음은 시각 대신 그 사실을 보인다', async () => {
    vi.mocked(fetchMarketNewsDigests).mockResolvedValue(
      result('READY', [{ ...digest, publicationTimeStatus: 'UNKNOWN', firstPublishedAt: null }]),
    )
    render(<NewsDigestSection sectorCode={null} />)

    expect(await screen.findByText(/발행 시각 확인 안 됨/)).toBeTruthy()
  })

  it('섹터를 고르면 그 섹터로 다시 조회한다', async () => {
    vi.mocked(fetchMarketNewsDigests).mockResolvedValue(result('EMPTY'))
    const { rerender } = render(<NewsDigestSection sectorCode={null} />)
    await screen.findByText('지금 보여 드릴 뉴스 묶음이 없습니다.')

    rerender(<NewsDigestSection sectorCode="SEMICONDUCTOR" />)

    expect(fetchMarketNewsDigests).toHaveBeenLastCalledWith('SEMICONDUCTOR', expect.any(AbortSignal))
  })

  it('조회가 실패해도 이 영역 안에서 끝낸다', async () => {
    vi.mocked(fetchMarketNewsDigests).mockRejectedValue(new Error('network'))
    render(<NewsDigestSection sectorCode={null} />)

    expect(await screen.findByText(/지금은 불러올 수 없습니다/)).toBeTruthy()
  })
})

import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMarketNewsDigests, fetchNewsSectors } from '@/api/marketNews'
import { SectorSelectionContext, type SectorSelectionContextValue } from '@/diagnosis/sector-selection-context'
import type { NewsDigest, NewsDigestResult, NewsSector } from '@/types/marketNews'
import { NewsSection } from './NewsSection'

vi.mock('@/api/marketNews', () => ({ fetchMarketNewsDigests: vi.fn(), fetchNewsSectors: vi.fn() }))

const sectors: NewsSector[] = [
  { sectorId: 'SEMICONDUCTOR', name: '반도체', investable: true },
  { sectorId: 'NEWS_IT', name: 'IT·AI', investable: false },
  { sectorId: 'BANK', name: '은행', investable: true },
  { sectorId: 'SECURITIES', name: '증권', investable: true },
  { sectorId: 'NEWS_MARKET', name: '증시', investable: false },
]

function digest(id: string, articles = 1): NewsDigest {
  return {
    digestId: id,
    representativeTitle: `${id} 제목`,
    factualSummary: `${id} 요약입니다.`,
    firstPublishedAt: '2026-10-01T03:00:00Z',
    publicationTimeStatus: 'VERIFIED',
    publisherCount: articles,
    sectors: [],
    topics: [],
    importanceRuleVersion: 'news-importance-v1',
    articles: Array.from({ length: articles }, (_, index) => ({
      articleId: `${id}-a${index}`,
      title: `${id} 보도 ${index}`,
      publisher: `매체 ${index}`,
      publishedAt: '2026-10-01T03:00:00Z',
      publicationTimeStatus: 'VERIFIED' as const,
      originalUrl: `https://news.example.com/${id}/${index}`,
    })),
  }
}

function result(digests: NewsDigest[], overrides: Partial<NewsDigestResult> = {}): NewsDigestResult {
  return {
    context: 'CURRENT', state: 'READY', sectorId: null, from: null, to: null,
    lastUpdatedAt: '2026-10-02T05:00:00Z', stale: false, statusMessage: null, digests, ...overrides,
  }
}

/** 섹터 목록마다 정해 둔 묶음. 012가 순위를 정해 준 순서 그대로다. 금융 분류는 은행·증권 두 섹터를 한 번에 묻는다. */
const bySectors: Record<string, NewsDigest[]> = {
  '': [digest('L1', 2), digest('L2'), digest('L3'), digest('L4'), digest('L5')],
  SEMICONDUCTOR: [digest('L1', 2), digest('S1'), digest('S2')],
  'SEMICONDUCTOR,NEWS_IT': [digest('S1'), digest('T1'), digest('T2'), digest('T3')],
  BANK: [digest('F1'), digest('F2')],
  'BANK,SECURITIES': [digest('F1'), digest('F2'), digest('F3'), digest('F4')],
}

function keyOf(sectorId: string | readonly string[] | null | undefined): string {
  if (!sectorId) return ''
  return typeof sectorId === 'string' ? sectorId : sectorId.join(',')
}

/** 묶음이 없는 조회. 012는 이때 EMPTY와 그 문장을 준다. */
function answer(sectorId: string | readonly string[] | null | undefined): NewsDigestResult {
  const digests = bySectors[keyOf(sectorId)]
  return digests ? result(digests) : result([], { state: 'EMPTY', statusMessage: '표시할 수 있는 뉴스가 없습니다.' })
}

const calls = () => vi.mocked(fetchMarketNewsDigests).mock.calls

function withSelection(selectedCodes: string[], children: ReactNode) {
  const value = { selectedCodes } as unknown as SectorSelectionContextValue
  return <SectorSelectionContext.Provider value={value}>{children}</SectorSelectionContext.Provider>
}

describe('NewsSection (012 FR-001, 002 FR-019)', () => {
  beforeEach(() => {
    vi.mocked(fetchNewsSectors).mockReset()
    vi.mocked(fetchNewsSectors).mockResolvedValue(sectors)
    vi.mocked(fetchMarketNewsDigests).mockReset()
    vi.mocked(fetchMarketNewsDigests).mockImplementation(async (sectorId) => answer(sectorId))
  })

  it('주요 뉴스는 1위 묶음을 보도 목록과 함께 크게, 그다음 넷을 목록으로 보인다', async () => {
    render(withSelection([], <NewsSection />))

    const lead = await screen.findByRole('link', { name: /L1 제목/ })
    expect(lead.getAttribute('href')).toBe('https://news.example.com/L1/0')
    const reports = screen.getByRole('list', { name: '이 사건을 다룬 보도' })
    expect(within(reports).getAllByRole('listitem')).toHaveLength(2)
    // 보도마다 매체·발행 시각·원문 링크(012 US1 시나리오 3). 시각은 한국 시각이다.
    expect(within(reports).getAllByText('10.01 12:00')).toHaveLength(2)
    expect(within(screen.getByRole('list', { name: '그다음 주요 뉴스' })).getAllByRole('listitem')).toHaveLength(4)
    expect(screen.getByRole('heading', { level: 2, name: '지금 시장 뉴스' })).toBeTruthy()
    expect(screen.getByText(/마지막 갱신 10\. 2\. 14:00 KST/)).toBeTruthy()
  })

  it('1위 묶음의 보도가 많으면 넷만 보이고 나머지는 펼쳐 본다', async () => {
    vi.mocked(fetchMarketNewsDigests).mockImplementation(async (sectorId) => (
      keyOf(sectorId) === '' ? result([digest('L1', 7), digest('L2')]) : answer(sectorId)
    ))
    render(withSelection([], <NewsSection />))

    const reports = await screen.findByRole('list', { name: '이 사건을 다룬 보도' })
    expect(within(reports).getAllByRole('listitem')).toHaveLength(4)
    const more = screen.getByRole('button', { name: '보도 3건 더 보기' })
    expect(more.getAttribute('aria-expanded')).toBe('false')

    fireEvent.click(more)
    expect(within(reports).getAllByRole('listitem')).toHaveLength(7)
    expect(screen.getByRole('button', { name: '보도 접기' }).getAttribute('aria-expanded')).toBe('true')
  })

  it('확인하지 못한 발행 시각은 확인한 것처럼 쓰지 않는다', async () => {
    const unverified: NewsDigest = {
      ...digest('L1'),
      firstPublishedAt: null,
      publicationTimeStatus: 'UNKNOWN',
      articles: [{ ...digest('L1').articles[0], publishedAt: null, publicationTimeStatus: 'UNKNOWN' }],
    }
    vi.mocked(fetchMarketNewsDigests).mockImplementation(async (sectorId) => (
      keyOf(sectorId) === '' ? result([unverified]) : answer(sectorId)
    ))
    render(withSelection([], <NewsSection />))

    expect(await screen.findByText('매체 1곳 · 발행 시각 확인 안 됨')).toBeTruthy()
    expect(within(screen.getByRole('list', { name: '이 사건을 다룬 보도' })).getByText('시각 확인 안 됨')).toBeTruthy()
  })

  it('분류의 섹터는 한 번의 조회로 묻고, 칸마다 보일 수보다 넉넉히 받는다', async () => {
    render(withSelection(['SEMICONDUCTOR'], <NewsSection />))
    await screen.findByRole('region', { name: '분류별 뉴스' })

    await waitFor(() => expect(fetchMarketNewsDigests).toHaveBeenCalledWith(['SEMICONDUCTOR', 'NEWS_IT'], expect.any(AbortSignal), undefined, 20))
    expect(fetchMarketNewsDigests).toHaveBeenCalledWith(['SEMICONDUCTOR'], expect.any(AbortSignal), undefined, 10)
    // 주요 뉴스는 보일 만큼(다섯)만 받는다. 앞에 건너뛸 부분이 없다.
    expect(fetchMarketNewsDigests).toHaveBeenCalledWith([], expect.any(AbortSignal), undefined, 5)
  })

  it('한 묶음은 먼저 나오는 부분에만 보인다: 주요 → 고른 섹터 → 분류별', async () => {
    render(withSelection(['SEMICONDUCTOR'], <NewsSection />))

    const selected = await screen.findByRole('region', { name: '고른 섹터 뉴스' })
    const semi = await within(selected).findByRole('group', { name: '반도체' })
    expect(within(semi).queryByText('L1 제목')).toBeNull()
    expect(within(semi).getByText('S1 제목')).toBeTruthy()
    expect(within(semi).getByText('S2 제목')).toBeTruthy()

    const groups = await screen.findByRole('region', { name: '분류별 뉴스' })
    const tech = await within(groups).findByRole('group', { name: '기술' })
    expect(within(tech).queryByText('S1 제목')).toBeNull()
    expect(within(tech).getAllByRole('listitem')).toHaveLength(3)
    // 묶음이 없는 칸은 012가 준 문장을 옮긴다.
    expect(within(within(groups).getByRole('group', { name: '시장·경제' })).getByText('표시할 수 있는 뉴스가 없습니다.')).toBeTruthy()
  })

  it('받은 묶음이 모두 앞 부분에 보인 칸은 뉴스가 없다고 말하지 않는다', async () => {
    vi.mocked(fetchMarketNewsDigests).mockImplementation(async (sectorId) => (
      keyOf(sectorId) === 'SEMICONDUCTOR' ? result([digest('L1', 2), digest('L2')]) : answer(sectorId)
    ))
    render(withSelection(['SEMICONDUCTOR'], <NewsSection />))

    const semi = await within(await screen.findByRole('region', { name: '고른 섹터 뉴스' })).findByRole('group', { name: '반도체' })
    expect(within(semi).getByText('위에 이미 보인 뉴스뿐입니다.')).toBeTruthy()
    expect(within(semi).queryByText(/없습니다/)).toBeNull()
  })

  it('고른 섹터가 없으면 그 부분을 두지 않는다', async () => {
    render(withSelection([], <NewsSection />))
    await screen.findByRole('region', { name: '분류별 뉴스' })

    expect(screen.queryByRole('region', { name: '고른 섹터 뉴스' })).toBeNull()
  })

  it('투자 대상을 더 고르면 그 섹터 하나만 새로 받고, 받는 동안 이미 그린 칸은 그대로 둔다', async () => {
    const { rerender } = render(withSelection(['SEMICONDUCTOR'], <NewsSection />))
    await within(await screen.findByRole('region', { name: '분류별 뉴스' })).findByRole('group', { name: '금융' })
    await waitFor(() => expect(screen.queryByRole('status')).toBeNull())
    const before = calls().length

    let release: (value: NewsDigestResult) => void = () => {}
    vi.mocked(fetchMarketNewsDigests).mockImplementation((sectorId) => (
      keyOf(sectorId) === 'BANK'
        ? new Promise<NewsDigestResult>((resolve) => { release = resolve })
        : Promise.resolve(answer(sectorId))
    ))
    rerender(withSelection(['SEMICONDUCTOR', 'BANK'], <NewsSection />))

    // 새 칸만 받는 중이다. 고른 섹터·분류별 뉴스가 통째로 '불러오는 중'으로 바뀌지 않는다.
    const selected = screen.getByRole('region', { name: '고른 섹터 뉴스' })
    const bank = within(selected).getByRole('group', { name: '은행' })
    expect(within(bank).getByText('뉴스를 불러오는 중입니다.')).toBeTruthy()
    expect(within(within(selected).getByRole('group', { name: '반도체' })).getByText('S1 제목')).toBeTruthy()
    const finance = within(screen.getByRole('region', { name: '분류별 뉴스' })).getByRole('group', { name: '금융' })
    expect(within(finance).getByText('F1 제목')).toBeTruthy()
    expect(calls().length).toBe(before + 1)
    expect(calls().at(-1)).toEqual([['BANK'], expect.any(AbortSignal), undefined, 10])

    await act(async () => release(result(bySectors.BANK)))
    expect(await within(bank).findByText('F1 제목')).toBeTruthy()
    // F1·F2는 이제 고른 섹터 칸에 있으므로 금융 칸은 그다음 순위로 채운다.
    expect(within(finance).queryByText('F1 제목')).toBeNull()
    expect(within(finance).getByText('F3 제목')).toBeTruthy()
  })

  it('012가 준 상태 문장을 옮기고, 받지 못한 칸 하나만 다시 받는다', async () => {
    vi.mocked(fetchMarketNewsDigests).mockImplementation(async (sectorId) => {
      if (keyOf(sectorId) === '') return result(bySectors[''], { state: 'STALE', statusMessage: '뉴스 수집이 지연되어 마지막 갱신 자료를 표시합니다.' })
      if (keyOf(sectorId) === 'BANK,SECURITIES') throw new Error('offline')
      return answer(sectorId)
    })
    render(withSelection([], <NewsSection />))

    expect(await screen.findByText('뉴스 수집이 지연되어 마지막 갱신 자료를 표시합니다.')).toBeTruthy()
    const finance = await screen.findByRole('group', { name: '금융' })
    expect(within(finance).getByText('뉴스를 지금은 불러올 수 없습니다.')).toBeTruthy()
    const before = calls().length

    vi.mocked(fetchMarketNewsDigests).mockImplementation(async (sectorId) => answer(sectorId))
    fireEvent.click(within(finance).getByRole('button', { name: '다시 시도' }))
    expect(await within(finance).findByText('F1 제목')).toBeTruthy()
    expect(calls().length).toBe(before + 1)
    expect(calls().at(-1)?.[0]).toEqual(['BANK', 'SECURITIES'])
  })

  it('뉴스를 준비 중이면 012의 문장만 두고 빈 칸을 늘어놓지 않는다', async () => {
    vi.mocked(fetchMarketNewsDigests).mockImplementation(async () => (
      result([], { state: 'PREPARING', statusMessage: '뉴스 제공을 준비하고 있습니다.', lastUpdatedAt: null })
    ))
    render(withSelection(['SEMICONDUCTOR'], <NewsSection />))

    expect(await screen.findByText('뉴스 제공을 준비하고 있습니다.')).toBeTruthy()
    // 섹터 목록이 와서 고른 섹터 조회까지 나간 뒤에도 그렇다.
    await waitFor(() => expect(fetchMarketNewsDigests).toHaveBeenCalledWith(['SEMICONDUCTOR'], expect.any(AbortSignal), undefined, 10))
    expect(screen.queryByRole('region', { name: '주요 뉴스' })).toBeNull()
    expect(screen.queryByRole('region', { name: '분류별 뉴스' })).toBeNull()
    expect(screen.queryByText(/없습니다/)).toBeNull()
  })

  it('012가 문장을 주지 않은 EMPTY는 화면의 문장을 한 번만 보인다', async () => {
    vi.mocked(fetchMarketNewsDigests).mockImplementation(async () => result([], { state: 'EMPTY' }))
    render(withSelection([], <NewsSection />))

    expect(await screen.findByText('지금 보여 드릴 뉴스 묶음이 없습니다.')).toBeTruthy()
    await waitFor(() => expect(fetchNewsSectors).toHaveBeenCalled())
    expect(screen.getAllByText(/없습니다/)).toHaveLength(1)
  })

  it('섹터 목록을 받지 못해도 주요 뉴스는 두고, 섹터별 뉴스 자리에서 다시 받는다', async () => {
    vi.mocked(fetchNewsSectors).mockRejectedValueOnce(new Error('offline'))
    render(withSelection(['SEMICONDUCTOR'], <NewsSection />))

    expect(await screen.findByRole('link', { name: /L1 제목/ })).toBeTruthy()
    const failure = await screen.findByRole('region', { name: '섹터별 뉴스' })
    expect(within(failure).getByText('섹터별 뉴스를 지금은 불러올 수 없습니다.')).toBeTruthy()

    fireEvent.click(within(failure).getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByRole('region', { name: '분류별 뉴스' })).toBeTruthy()
    expect(screen.getByRole('region', { name: '고른 섹터 뉴스' })).toBeTruthy()
    expect(screen.queryByRole('region', { name: '섹터별 뉴스' })).toBeNull()
    expect(fetchNewsSectors).toHaveBeenCalledTimes(2)
  })

  it('섹터 칩으로 좁히면 그 섹터의 묶음만 보이고, 다른 분류를 열거나 전체 보기를 누르면 푼다', async () => {
    render(withSelection([], <NewsSection />))
    fireEvent.click(await screen.findByRole('button', { name: '기술' }))
    expect(screen.getByRole('button', { name: '기술' }).getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(screen.getByRole('button', { name: '반도체' }))
    expect(screen.getByRole('button', { name: '반도체' }).getAttribute('aria-pressed')).toBe('true')

    const narrowed = await screen.findByRole('region', { name: '반도체 뉴스' })
    expect(await within(narrowed).findByText('L1 제목')).toBeTruthy()
    expect(within(narrowed).getAllByRole('listitem')).toHaveLength(3)
    expect(screen.queryByRole('region', { name: '분류별 뉴스' })).toBeNull()

    // 다른 분류를 열면 '금융'이 눌린 채 반도체 뉴스가 남지 않는다.
    fireEvent.click(screen.getByRole('button', { name: '금융' }))
    expect(screen.queryByRole('region', { name: '반도체 뉴스' })).toBeNull()
    expect(await screen.findByRole('region', { name: '분류별 뉴스' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: '은행' }))
    await screen.findByRole('region', { name: '은행 뉴스' })
    fireEvent.click(screen.getByRole('button', { name: '전체 보기' }))
    expect(await screen.findByRole('region', { name: '분류별 뉴스' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '전체' }).getAttribute('aria-pressed')).toBe('true')
  })

  it('제목 옆 i 표시를 열면 뉴스 갱신 주기를 알리고 실시간이라 부르지 않는다 (012 FR-014)', async () => {
    render(withSelection([], <NewsSection />))

    fireEvent.focus(screen.getByRole('button', { name: '뉴스 갱신 도움말' }))
    const tooltip = await screen.findByRole('tooltip')
    expect(tooltip.textContent).toContain('약 15분마다')
    expect(tooltip.textContent).not.toMatch(/실시간/)
  })

  it('투자 대상 목록 밖(선택 상태 없음)에서도 주요 뉴스를 보인다', async () => {
    render(<NewsSection />)

    expect(await screen.findByRole('link', { name: /L1 제목/ })).toBeTruthy()
  })
})

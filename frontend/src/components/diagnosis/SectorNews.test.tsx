import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMarketNewsDigests, fetchNewsSectors } from '@/api/marketNews'
import { SectorNews } from './SectorNews'

vi.mock('@/api/marketNews', () => ({ fetchMarketNewsDigests: vi.fn(), fetchNewsSectors: vi.fn() }))

const sectors = [
  { sectorId: 'SEMICONDUCTOR', name: '반도체', investable: true },
  { sectorId: 'NEWS_IT', name: 'IT·AI', investable: false },
]

describe('SectorNews (002 FR-019)', () => {
  beforeEach(() => {
    vi.mocked(fetchNewsSectors).mockResolvedValue(sectors)
    vi.mocked(fetchMarketNewsDigests).mockResolvedValue({
      context: 'CURRENT',
      state: 'EMPTY',
      sectorId: null,
      from: null,
      to: null,
      lastUpdatedAt: null,
      stale: false,
      statusMessage: null,
      digests: [],
    })
  })

  it('기본은 전체로 최신 뉴스를 받고, 분류를 펼쳐 섹터를 고르면 그 섹터로 좁힌다', async () => {
    render(<SectorNews />)

    await waitFor(() => expect(vi.mocked(fetchMarketNewsDigests).mock.calls[0][0]).toBeNull())
    expect(screen.getByRole('button', { name: '전체' }).getAttribute('aria-pressed')).toBe('true')
    // 처음에는 분류만 보이고 섹터 칩은 펼쳐지지 않는다.
    expect(screen.queryByRole('button', { name: '반도체' })).toBeNull()

    fireEvent.click(await screen.findByRole('button', { name: '기술' }))
    // 분류만 눌러서는 뉴스가 좁혀지지 않는다.
    expect(vi.mocked(fetchMarketNewsDigests).mock.calls.at(-1)?.[0]).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '반도체' }))
    await waitFor(() => expect(vi.mocked(fetchMarketNewsDigests).mock.calls.at(-1)?.[0]).toBe('SEMICONDUCTOR'))
    expect(screen.getByRole('button', { name: '반도체' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('button', { name: '전체' }).getAttribute('aria-pressed')).toBe('false')
  })

  it('투자 대상이 아닌 뉴스 전용 섹터도 칩으로 고를 수 있다', async () => {
    render(<SectorNews />)

    fireEvent.click(await screen.findByRole('button', { name: '기술' }))
    fireEvent.click(screen.getByRole('button', { name: 'IT·AI' }))
    await waitFor(() => expect(vi.mocked(fetchMarketNewsDigests).mock.calls.at(-1)?.[0]).toBe('NEWS_IT'))
  })

  it('전체를 누르면 펼친 분류를 접고 섹터 선택을 푼다', async () => {
    render(<SectorNews />)

    fireEvent.click(await screen.findByRole('button', { name: '기술' }))
    fireEvent.click(screen.getByRole('button', { name: '반도체' }))
    await waitFor(() => expect(vi.mocked(fetchMarketNewsDigests).mock.calls.at(-1)?.[0]).toBe('SEMICONDUCTOR'))

    fireEvent.click(screen.getByRole('button', { name: '전체' }))
    await waitFor(() => expect(vi.mocked(fetchMarketNewsDigests).mock.calls.at(-1)?.[0]).toBeNull())
    expect(screen.queryByRole('button', { name: '반도체' })).toBeNull()
  })

  it('섹터 목록을 받지 못해도 전체 뉴스는 보인다', async () => {
    vi.mocked(fetchNewsSectors).mockRejectedValue(new Error('offline'))
    render(<SectorNews />)

    await waitFor(() => expect(vi.mocked(fetchMarketNewsDigests)).toHaveBeenCalled())
    expect(screen.getAllByRole('button')).toHaveLength(1)
    expect(screen.getByRole('button', { name: '전체' })).toBeTruthy()
  })
})

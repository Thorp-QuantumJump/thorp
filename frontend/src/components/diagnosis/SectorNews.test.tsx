import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMarketNewsDigests } from '@/api/marketNews'
import { SectorNews } from './SectorNews'

vi.mock('@/api/marketNews', () => ({ fetchMarketNewsDigests: vi.fn() }))

const sectors = [
  { code: 'CONSTRUCTION', name: '건설' },
  { code: 'SEMICONDUCTOR', name: '반도체' },
]

describe('SectorNews (002 FR-019)', () => {
  beforeEach(() => {
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

  it('기본은 전체로 최신 뉴스를 받고, 섹터를 고르면 그 섹터로 좁힌다', async () => {
    render(<SectorNews sectors={sectors} />)

    await waitFor(() => expect(vi.mocked(fetchMarketNewsDigests).mock.calls[0][0]).toBeNull())
    expect(screen.getByRole('button', { name: '전체' }).getAttribute('aria-pressed')).toBe('true')

    fireEvent.click(screen.getByRole('button', { name: '반도체' }))
    await waitFor(() => expect(vi.mocked(fetchMarketNewsDigests).mock.calls.at(-1)?.[0]).toBe('SEMICONDUCTOR'))
    expect(screen.getByRole('button', { name: '반도체' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('button', { name: '전체' }).getAttribute('aria-pressed')).toBe('false')
  })
})

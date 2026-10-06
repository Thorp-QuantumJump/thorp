import { fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getRegimeSearch } from '@/api/regime'
import { renderWithRouter } from '@/test/renderWithRouter'
import type { RegimeSearchResult } from '@/types/regime'
import { CompositePast } from './CompositePast'

vi.mock('@/api/regime', () => ({ getRegimeSearch: vi.fn(), getRegimeCaseDetail: vi.fn() }))

function compositeResult(overrides: Partial<RegimeSearchResult> = {}): RegimeSearchResult {
  return {
    searchId: 'C-1',
    mode: 'COMPOSITE',
    field: null,
    baselineMonth: '2026-07',
    status: 'NEAREST_ONLY',
    statusNote: '가까운 순서만 제공합니다.',
    caseLabel: null,
    versions: { data: 'd', registry: 'r', model: 'm', revision: 1, sampleData: false, sampleRegistry: false },
    comparisonMonths: [],
    dataAsOf: { indicators: [], note: null, revisions: [], newerReleases: [] },
    cases: [
      { endMonth: '2019-08', rank: 1, observationMonths: ['2019-06', '2019-07', '2019-08'], distance: '0.4', similar: ['생산 분야가 비슷했습니다.'], different: ['노동 분야는 달랐습니다.'], detailAvailable: true },
    ],
    ...overrides,
  } as RegimeSearchResult
}

describe('CompositePast (009 FR-019, 010 US3)', () => {
  beforeEach(() => {
    vi.mocked(getRegimeSearch).mockReset()
    vi.mocked(getRegimeSearch).mockResolvedValue(compositeResult())
  })

  it('종합 비교를 고른 동안에만 조회하고 사례마다 비슷한 점·다른 점과 백테스트를 보인다', async () => {
    renderWithRouter(<CompositePast onShowFields={vi.fn()} />)

    expect(await screen.findByText('2019-06 ~ 2019-08')).toBeTruthy()
    expect(vi.mocked(getRegimeSearch).mock.calls[0][0]).toEqual({ mode: 'composite', limit: 3 })
    expect(screen.getByRole('heading', { level: 2, name: 'Thorp 종합 비교' })).toBeTruthy()
    expect(screen.getByText('생산 분야가 비슷했습니다.')).toBeTruthy()
    expect(screen.getByRole('link', { name: '이 기간으로 백테스트' }).getAttribute('href')).toBe('/regime-link/start?case=C-1%7E2019-08')
    expect(screen.getByRole('link', { name: '자세히' }).getAttribute('href')).toBe('/regime-search?mode=composite')
  })

  it('기준 충족 사례가 없으면 안내와 분야별로 살펴보기·참고 사례 보기를 보인다', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(compositeResult({
      status: 'THRESHOLD_APPLIED',
      cases: [],
      thresholdNote: '설정된 종합 유사성 기준을 충족하는 사례가 없습니다. 분야별로 살펴볼 수 있습니다.',
    }))
    const onShowFields = vi.fn()
    renderWithRouter(<CompositePast onShowFields={onShowFields} />)

    expect(await screen.findByText(/설정된 종합 유사성 기준을 충족하는 사례가 없습니다/)).toBeTruthy()
    expect(screen.getByRole('link', { name: /참고 사례 보기/ }).getAttribute('href')).toBe('/regime-search?mode=composite')
    fireEvent.click(screen.getByRole('button', { name: '분야별로 살펴보기' }))
    expect(onShowFields).toHaveBeenCalled()
  })

  it('서버가 실패 사유를 주면 그 문장을 그대로 보인다 (종합 기준월이 아직 없을 때)', async () => {
    vi.mocked(getRegimeSearch).mockRejectedValueOnce({
      isAxiosError: true,
      response: { status: 404, data: { isSuccess: false, message: '아직 계산된 기준월이 없습니다.' } },
    })
    renderWithRouter(<CompositePast onShowFields={vi.fn()} />)

    expect(await screen.findByText('아직 계산된 기준월이 없습니다.')).toBeTruthy()
    expect(screen.queryByText('과거 사례를 불러오지 못했습니다.')).toBeNull()
  })

  it('예시 자료로 계산된 결과는 그 사실을 문구로 보인다 (010 FR-038)', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(compositeResult({
      versions: { data: 'd', registry: 'r', model: 'm', revision: 1, sampleData: true, sampleRegistry: false },
    }))
    renderWithRouter(<CompositePast onShowFields={vi.fn()} />)

    expect(await screen.findByText('이 결과는 아직 확정되지 않은 예시 자료로 계산되었습니다.')).toBeTruthy()
  })

  it('받지 못해도 경고로 막지 않고 다시 시도를 둔다', async () => {
    vi.mocked(getRegimeSearch).mockRejectedValueOnce(new Error('offline'))
    renderWithRouter(<CompositePast onShowFields={vi.fn()} />)

    expect(await screen.findByText('과거 사례를 불러오지 못했습니다.')).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(getRegimeSearch).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('2019-06 ~ 2019-08')).toBeTruthy()
  })
})

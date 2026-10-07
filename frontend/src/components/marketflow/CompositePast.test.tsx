import { fireEvent, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getRegimeSearch } from '@/api/regime'
import { renderWithRouter } from '@/test/renderWithRouter'
import type { RegimeBaselineMonths, RegimeSearchResult } from '@/types/regime'
import { CompositePast } from './CompositePast'

vi.mock('@/api/regime', () => ({ getRegimeSearch: vi.fn(), getRegimeCaseDetail: vi.fn() }))

const baselines: RegimeBaselineMonths = {
  byField: [{ id: 'f1', name: '시험 분야', baselineMonth: '2026-07' }],
  composite: '2026-07',
  range: { earliest: '1962-01', latest: '2026-08' },
  note: '전체 분야를 맞추기 위해 비교 기준월이 바뀝니다.',
}

function compositeResult(overrides: Partial<RegimeSearchResult> = {}): RegimeSearchResult {
  return {
    searchId: 'C-1',
    mode: 'COMPOSITE',
    field: null,
    baselineMonth: '2026-07',
    baselineOrigin: 'T_ALL',
    status: 'NEAREST_ONLY',
    statusNote: '가까운 순서만 제공합니다.',
    caseLabel: null,
    basis: { kind: 'HISTORICAL_REVISION', note: '최신 개정자료를 이용한 역사 비교입니다.' },
    counts: { evaluated: 10, eligible: 3, stored: 1, displayed: 1 },
    versions: { data: 'd', registry: 'r', model: 'm', revision: 1, sampleData: false, sampleRegistry: false },
    comparisonMonths: [],
    dataAsOf: { indicators: [], note: null, revisions: [], newerReleases: [] },
    cases: [
      { endMonth: '2019-08', rank: 1, observationMonths: ['2019-06', '2019-07', '2019-08'], distance: '0.4', similar: ['생산 분야가 비슷했습니다.'], different: ['노동 분야는 달랐습니다.'], detailAvailable: true },
    ],
    ...overrides,
  } as RegimeSearchResult
}

function renderComposite() {
  return renderWithRouter(<CompositePast baselines={baselines} fieldNames={{ f1: '시험 분야' }} />)
}

describe('CompositePast (009 FR-019, 010 US3)', () => {
  beforeEach(() => {
    vi.mocked(getRegimeSearch).mockReset()
    vi.mocked(getRegimeSearch).mockResolvedValue(compositeResult())
  })

  it('종합 비교를 고른 동안에만 조회하고 사례마다 비슷한 점·다른 점·백테스트·상세를 그 자리에서 보인다', async () => {
    renderComposite()

    expect(await screen.findByText('2019-06 ~ 2019-08')).toBeTruthy()
    expect(vi.mocked(getRegimeSearch).mock.calls[0][0]).toEqual({ mode: 'composite', limit: 3 })
    expect(screen.getByRole('heading', { level: 2, name: 'Thorp 종합 비교' })).toBeTruthy()
    expect(screen.getByRole('heading', { level: 3, name: '가까운 과거 사례' })).toBeTruthy()
    expect(screen.getByText('생산 분야가 비슷했습니다.')).toBeTruthy()
    expect(screen.getByRole('link', { name: '이 기간으로 백테스트' }).getAttribute('href')).toBe('/regime-link/start?case=C-1%7E2019-08')
    // 010 검색 화면으로 넘기는 길은 없다. 상세는 같은 자리에서 펼친다(2026-10-07).
    expect(screen.getByRole('button', { name: '상세 보기' })).toBeTruthy()
    expect(screen.queryByRole('link', { name: '자세히' })).toBeNull()
    expect(document.querySelector('a[href^="/regime-search"]')).toBeNull()
    // 예전 머리 문장은 두지 않는다.
    expect(screen.queryByText(/일곱 분야를 함께 견준/)).toBeNull()
  })

  it('기준 충족 사례가 없으면 안내와 함께 기준 미충족 참고 사례를 바로 펼치고 분야별 탐색 버튼은 두지 않는다', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(compositeResult({
      status: 'THRESHOLD_APPLIED',
      cases: [],
      counts: { evaluated: 10, eligible: 3, stored: 0, displayed: 0 },
      thresholdNote: '설정된 종합 유사성 기준을 충족하는 사례가 없습니다. 분야별로 살펴볼 수 있습니다.',
      referenceLabel: '기준 미충족 참고 사례',
      referenceCases: compositeResult().cases,
    }))
    renderComposite()

    expect(await screen.findByText(/설정된 종합 유사성 기준을 충족하는 사례가 없습니다/)).toBeTruthy()
    expect(screen.getByRole('heading', { level: 4, name: '기준 미충족 참고 사례' })).toBeTruthy()
    expect(screen.getByText('Thorp가 정한 유사성 기준을 넘지 못한, 가까운 순서의 참고 사례입니다.')).toBeTruthy()
    expect(screen.getByText('2019-06 ~ 2019-08')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '분야별로 살펴보기' })).toBeNull()
    expect(screen.queryByRole('link', { name: /참고 사례 보기/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /참고 사례 보기/ })).toBeNull()
  })

  it('서버가 실패 사유를 주면 그 문장을 그대로 보인다 (종합 기준월이 아직 없을 때)', async () => {
    vi.mocked(getRegimeSearch).mockRejectedValueOnce({
      isAxiosError: true,
      response: { status: 404, data: { isSuccess: false, message: '아직 계산된 기준월이 없습니다.' } },
    })
    renderComposite()

    expect(await screen.findByText('아직 계산된 기준월이 없습니다.')).toBeTruthy()
    expect(screen.queryByText('과거 사례를 불러오지 못했습니다.')).toBeNull()
  })

  it('예시 자료로 계산된 결과는 그 사실을 문구로 보인다 (010 FR-038)', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(compositeResult({
      versions: { data: 'd', registry: 'r', model: 'm', revision: 1, sampleData: true, sampleRegistry: false },
    }))
    renderComposite()

    expect(await screen.findByText('이 결과는 아직 확정되지 않은 예시 자료로 계산되었습니다.')).toBeTruthy()
  })

  it('받지 못해도 경고로 막지 않고 다시 시도를 둔다', async () => {
    vi.mocked(getRegimeSearch).mockRejectedValueOnce(new Error('offline'))
    renderComposite()

    expect(await screen.findByText('과거 사례를 불러오지 못했습니다.')).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    await waitFor(() => expect(getRegimeSearch).toHaveBeenCalledTimes(2))
    expect(await screen.findByText('2019-06 ~ 2019-08')).toBeTruthy()
  })

  it('기준월을 지정하면 그 달로 종합 검색을 다시 연다', async () => {
    renderComposite()
    await screen.findByText('2019-06 ~ 2019-08')

    fireEvent.change(screen.getByLabelText('기준월 지정'), { target: { value: '2015-03' } })
    fireEvent.click(screen.getByRole('button', { name: '이 달로 찾기' }))

    await waitFor(() => expect(getRegimeSearch).toHaveBeenCalledWith({ mode: 'composite', baselineMonth: '2015-03', limit: 3 }, expect.any(AbortSignal)))
    expect(await screen.findByText('기준월을 2015-03로 바꾸어 새 검색을 열었습니다.')).toBeTruthy()
  })
})

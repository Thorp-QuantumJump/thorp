import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SAME_VERSION_NOTICE } from '@/components/records/RecordOriginNotice'
import { analysisDetail, analysisDetailUnjudged } from '@/test/myRecordsFixtures'
import { resultDetailCompleted, resultTimeseries, resultToken } from '@/test/resultFixtures'
import { ResultPage } from './ResultPage'

/** 006 T024. quickstart S1-6(동일 버전 조회)·S5(대응표 판정은 서버 문장 그대로). */

const resultMocks = vi.hoisted(() => ({
  fetchResult: vi.fn(),
  fetchResultTimeseries: vi.fn(),
}))
const recordMocks = vi.hoisted(() => ({ fetchMyRecord: vi.fn() }))

vi.mock('@/api/result', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/result')>()
  return { ...actual, ...resultMocks }
})
vi.mock('@/api/myRecords', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/myRecords')>()
  return { ...actual, ...recordMocks }
})
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => <header>Thorp</header> }))

function renderResult(search: string) {
  return render(
    <MemoryRouter initialEntries={[`/results/${resultToken}${search}`]}>
      <Routes>
        <Route path="/results/:token" element={<ResultPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ResultPage from records', () => {
  beforeEach(() => {
    resultMocks.fetchResult.mockReset().mockResolvedValue(resultDetailCompleted)
    resultMocks.fetchResultTimeseries.mockReset().mockResolvedValue(resultTimeseries)
    recordMocks.fetchMyRecord.mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('기록에서 열면 동일 버전 조회 한 줄과 서버가 판정한 대응표 문장을 보인다', async () => {
    recordMocks.fetchMyRecord.mockResolvedValue(analysisDetail)

    renderResult('?from=records&recordId=MR-20260918-000042')

    expect(await screen.findByText(SAME_VERSION_NOTICE)).toBeTruthy()
    expect(await screen.findByText('2차전지 섹터의 대표 ETF가 저장 당시와 다릅니다.')).toBeTruthy()
    expect(recordMocks.fetchMyRecord).toHaveBeenCalledWith('MR-20260918-000042', expect.any(AbortSignal))
  })

  it('판정 근거가 없으면(키 없음) 문장을 덧붙이지 않는다 — 버전 번호만 올랐을 때 경고가 뜨면 UI-01 위반이다', async () => {
    recordMocks.fetchMyRecord.mockResolvedValue(analysisDetailUnjudged)

    renderResult('?from=records&recordId=MR-20260918-000042')

    expect(await screen.findByText(SAME_VERSION_NOTICE)).toBeTruthy()
    await screen.findByRole('heading', { name: '분석 결과' })
    expect(screen.getAllByRole('status').filter((el) => el.closest('[class*="origin"]'))).toHaveLength(1)
  })

  it('기록에서 오지 않았으면 그 줄이 없고 기록 상세를 부르지 않는다', async () => {
    renderResult('')

    await screen.findByRole('heading', { name: '분석 결과' })
    expect(screen.queryByText(SAME_VERSION_NOTICE)).toBeNull()
    expect(recordMocks.fetchMyRecord).not.toHaveBeenCalled()
  })
})

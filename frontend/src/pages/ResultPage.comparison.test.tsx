import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthContext } from '@/auth/auth-context'
import { anonymousAuthContext } from '@/test/authFixtures'
import { ResultError } from '@/api/result'
import { resultDetailCompleted as base, resultTimeseries } from '@/test/resultFixtures'
import type { PublicResultDetail } from '@/types/result'
import { ResultPage } from './ResultPage'

const api = vi.hoisted(() => ({ detail: vi.fn(), series: vi.fn(), create: vi.fn() }))
vi.mock('@/api/result', async (load) => ({
  ...(await load<typeof import('@/api/result')>()),
  fetchResult: api.detail,
  fetchResultTimeseries: api.series,
}))
vi.mock('@/api/backtest', async (load) => ({
  ...(await load<typeof import('@/api/backtest')>()),
  createBacktest: api.create,
}))
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => <header>Thorp</header> }))
const token = 'b'.repeat(32)
let target: PublicResultDetail
beforeEach(() => {
  Object.values(api).forEach((mock) => mock.mockReset())
  target = {
    ...base,
    publicToken: token,
    strategyName: '비교용 전략',
    summary: { ...base.summary!, netTotalReturn: '0.25', maxDrawdown: '-0.02' },
  }
  api.detail.mockImplementation(async (requested: string) =>
    requested === base.publicToken ? base : target,
  )
  api.series.mockResolvedValue(resultTimeseries)
})
function setup(search = '?compare=' + token) {
  return render(
    <MemoryRouter initialEntries={['/results/' + base.publicToken + search]}>
      <AuthContext.Provider value={anonymousAuthContext}>
      <Routes>
        <Route path="/results/:token" element={<ResultPage />} />
      </Routes>
    </AuthContext.Provider>
    </MemoryRouter>,
  )
}
describe('결과 화면의 비교 연결 (T061)', () => {
  it('같은 조건의 결과만 파선·비교 문장·이름·비중 출처에 연결한다', async () => {
    const { container } = setup()
    expect(await screen.findByText(/같은 조건에서 비교 대상\(비교용 전략\)/)).toBeTruthy()
    expect(screen.getByRole('img').getAttribute('aria-label')).toContain(
      '비교 대상은 비교용 전략이며 파선',
    )
    expect(screen.getByLabelText('분석 조건').textContent).toContain('비교 대상 비교용 전략')
    expect(screen.getByText(/비교 대상의 비중도/)).toBeTruthy()
    // 판정 기준(PD-12)이 정해지기 전까지 미검증 문장은 내지 않는다.
    expect(screen.queryByText(/판정 기준이 아직 정해지지 않았습니다/)).toBeNull()
    expect(container.querySelectorAll('[data-slot="button"][data-variant="default"]')).toHaveLength(
      1,
    )
    expect(screen.queryByText('자동 탐색 후보 비교')).toBeNull()
    expect(container.innerHTML).not.toContain('BT-')
  })
  it('탐색 주소를 유지하며 묶음으로 돌아가는 연결을 보인다', async () => {
    setup('?compare=' + token + '&search=SEARCH-77')
    expect(await screen.findByText('자동 탐색 후보 비교')).toBeTruthy()
    expect(screen.getByRole('link', { name: '탐색 묶음으로 돌아가기' }).getAttribute('href')).toBe(
      '/param-search?searchId=SEARCH-77',
    )
  })
  it('탐색 인자가 없어도 서버가 알려 준 SEARCH_BUNDLE은 구분하고 묶음 주소를 지어내지 않는다', async () => {
    target = { ...target, config: { ...target.config, executionType: 'SEARCH_BUNDLE' } }
    setup()
    expect(await screen.findByText('자동 탐색 후보 비교')).toBeTruthy()
    expect(screen.queryByRole('link', { name: '탐색 묶음으로 돌아가기' })).toBeNull()
  })
  it('기간이 다른 결과는 확인창 뒤에서도 기준 곡선 하나만 보인다', async () => {
    target = { ...target, config: { ...target.config, effectiveStart: '2024-01-02' } }
    setup()
    await screen.findByRole('dialog')
    expect(screen.getByRole('img', { hidden: true }).getAttribute('aria-label')).toContain(
      '비교 대상은 없습니다',
    )
    expect(api.create).not.toHaveBeenCalled()
    const pickerButton = screen.getByRole('button', { name: '비교 대상 고르기', hidden: true })
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    await waitFor(() => expect(document.activeElement).toBe(pickerButton))
    expect(screen.getByRole('img').getAttribute('aria-label')).toContain('비교 대상은 없습니다')
  })
  it('자료 버전이 다르면 같은 현재 기준으로 두 결과를 다시 계산한다', async () => {
    target = { ...target, versions: { ...target.versions, data: 'new-data' } }
    api.create
      .mockResolvedValueOnce({ publicToken: 'c'.repeat(32) })
      .mockResolvedValueOnce({ publicToken: 'd'.repeat(32) })
    setup()
    const dialog = await screen.findByRole('dialog')
    expect(dialog.textContent).toContain('자료 또는 계산 엔진 기준이 달라')
    fireEvent.click(screen.getByRole('button', { name: '두 결과 다시 계산' }))
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(2))
  })
  it('금액이나 자료 기준이 다르면 비교 문장과 곡선을 함께 막는다', async () => {
    target = { ...target, config: { ...target.config, initialCash: '999.00' } }
    setup()
    expect(await screen.findByText(/자료 기준 또는 분석 시나리오가 달라/)).toBeTruthy()
    expect(screen.getByRole('img').getAttribute('aria-label')).toContain('비교 대상은 없습니다')
    expect(screen.queryByText(/같은 조건에서 비교 대상/)).toBeNull()
  })
  it('대상이 둘이면 파선 둘·문장 둘·조건 줄의 이름 둘을 보이고 한 번에 해제할 수 있다', async () => {
    const other = 'c'.repeat(32)
    const second = { ...target, publicToken: other, strategyName: '둘째 전략' }
    api.detail.mockImplementation(async (requested: string) =>
      requested === base.publicToken ? base : requested === other ? second : target,
    )
    const { container } = setup('?compare=' + token + ',' + other)
    expect(await screen.findByText(/같은 조건에서 비교 대상\(비교용 전략\)/)).toBeTruthy()
    expect(screen.getByText(/같은 조건에서 비교 대상\(둘째 전략\)/)).toBeTruthy()
    expect(screen.getByLabelText('분석 조건').textContent).toContain('비교 대상 비교용 전략, 둘째 전략')
    expect(screen.getByRole('img').getAttribute('aria-label')).toContain(
      '비교 대상은 비교용 전략, 둘째 전략이며 서로 다른 파선',
    )
    expect(screen.getByLabelText('비교 대상 둘째 전략')).toBeTruthy()
    expect(screen.getByRole('button', { name: '비교 모두 해제' })).toBeTruthy()
    expect(container.innerHTML).not.toContain('BT-')
  })
  it('같은 이름의 대상이 둘이면 번호를 붙여 갈라 적는다', async () => {
    const other = 'c'.repeat(32)
    api.detail.mockImplementation(async (requested: string) =>
      requested === base.publicToken ? base : { ...target, publicToken: requested },
    )
    setup('?compare=' + token + ',' + other)
    expect(await screen.findByLabelText('비교 대상 비교용 전략 (1)')).toBeTruthy()
    expect(screen.getByLabelText('비교 대상 비교용 전략 (2)')).toBeTruthy()
  })
  it('대상만 410이면 기준 숫자와 곡선이 유지된다', async () => {
    api.detail.mockImplementation(async (requested: string) => {
      if (requested === base.publicToken) return base
      throw new ResultError('만료', { code: 'RESULT_EXPIRED', status: 410 })
    })
    setup()
    expect(await screen.findByText(/비교 대상 결과가 더 이상/)).toBeTruthy()
    expect(screen.getByText('16.67%')).toBeTruthy()
    await waitFor(() => expect(screen.getByRole('img')).toBeTruthy())
  })
})

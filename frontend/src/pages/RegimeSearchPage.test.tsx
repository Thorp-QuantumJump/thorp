import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import { recordRegimeSearch } from '@/api/myRecords'
import { getRegimeBaselineMonths, getRegimeCaseDetail, getRegimeSearch, getRegimeSearchById } from '@/api/regime'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { authenticatedUser } from '@/test/authFixtures'
import { fullText, regimeDetail, regimeResult } from '@/test/regimeFixtures'
import { RegimeSearchPage } from './RegimeSearchPage'

vi.mock('@/api/regime', () => ({ getRegimeBaselineMonths: vi.fn(), getRegimeSearch: vi.fn(), getRegimeSearchById: vi.fn(), getRegimeCaseDetail: vi.fn() }))
vi.mock('@/api/myRecords', () => ({ recordRegimeSearch: vi.fn() }))
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => null }))

const baseline = {
  byField: [{ id: 'field-1', name: '시험 분야', baselineMonth: '2026-07' }],
  composite: '2026-05', range: { earliest: '1995-04', latest: '2026-07' }, note: '전환 안내',
}

/** 010 화면은 주소(`?searchId=`)와 위치 state 를 읽으므로 라우터 안에서 그린다. 로그인 문맥은 주면 쓰고 없으면 비로그인이다. */
function renderPage(options: { entries?: string[]; user?: AuthContextValue['user'] } = {}) {
  const auth: AuthContextValue = {
    status: options.user ? 'authenticated' : 'anonymous', user: options.user ?? null,
    login: vi.fn(), logout: vi.fn(), recheck: vi.fn(),
  }
  return render(
    <MemoryRouter initialEntries={options.entries ?? ['/regime-search']}>
      <AuthContext.Provider value={auth}><RegimeSearchPage /></AuthContext.Provider>
    </MemoryRouter>,
  )
}

async function search(options: Parameters<typeof renderPage>[0] = {}) {
  renderPage(options)
  fireEvent.change(await screen.findByLabelText('경제 분야'), { target: { value: 'field-1' } })
  fireEvent.click(screen.getByRole('button', { name: '과거 사례 찾기' }))
}

describe('경제환경 검색 화면', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(getRegimeBaselineMonths).mockResolvedValue(baseline)
    // 로그인 검사가 기록 요청을 타므로 기본은 성공으로 둔다. 실패를 보는 검사만 따로 바꾼다.
    vi.mocked(recordRegimeSearch).mockResolvedValue({} as never)
  })

  it('계산된 기준월이 없으면 빈 선택 상자 대신 그 사유를 보인다', async () => {
    // 사전 계산이 돌지 않은 서버에서 실제로 이 상태가 났다. 빈 상자만 보이면 사용자는 자기가
    // 잘못 눌렀다고 읽는다(FR-030, 원칙 VI).
    vi.mocked(getRegimeBaselineMonths).mockResolvedValue({
      byField: [], composite: null, range: null, note: '전환 안내',
    } as never)

    renderPage()

    expect(await screen.findByText(/아직 비교할 과거 자료가 준비되지 않았습니다/)).toBeTruthy()
    expect(screen.queryByLabelText('경제 분야')).toBeNull()
    expect(vi.mocked(getRegimeSearch)).not.toHaveBeenCalled()
  })

  describe('시장 흐름에서 넘어온 진입 (009 FR-009)', () => {
    it('주소의 분야·기준월로 바로 검색한다', async () => {
      vi.mocked(getRegimeSearch).mockResolvedValue(regimeResult({}))

      renderPage({ entries: ['/regime-search?field=field-1&month=2026-07'] })

      // 사용자가 분야를 다시 고르지 않아도 그 분야의 결과가 나온다. 다시 고르게 하면
      // 시장 흐름에서 보던 분야를 화면이 잊은 것처럼 보인다.
      await waitFor(() =>
        expect(vi.mocked(getRegimeSearch)).toHaveBeenCalledWith(
          { mode: 'field', field: 'field-1', baselineMonth: '2026-07', limit: 3 },
          expect.any(AbortSignal),
        ),
      )
      expect(await screen.findByLabelText('경제 분야')).toHaveProperty('value', 'field-1')
    })

    it('결과가 없는 달로 넘어오면 그 분야의 최신월로 물러나고 알린다', async () => {
      vi.mocked(getRegimeSearch).mockResolvedValue(regimeResult({}))

      // 2026-08 은 이 분야의 기준월(2026-07)보다 뒤라 결과가 없다. 그대로 보내면 빈 화면이 된다.
      renderPage({ entries: ['/regime-search?field=field-1&month=2026-08'] })

      await waitFor(() =>
        expect(vi.mocked(getRegimeSearch)).toHaveBeenCalledWith(
          { mode: 'field', field: 'field-1', baselineMonth: undefined, limit: 3 },
          expect.any(AbortSignal),
        ),
      )
    })

    it('전략 진단에서 종합 비교로 넘어오면 종합 비교가 선택된 상태로 연다 (S15P21E205-450)', async () => {
      vi.mocked(getRegimeSearch).mockResolvedValue(
        regimeResult({ mode: 'COMPOSITE', field: null }),
      )

      renderPage({ entries: ['/regime-search?mode=composite'] })

      await waitFor(() =>
        expect(vi.mocked(getRegimeSearch)).toHaveBeenCalledWith(
          expect.objectContaining({ mode: 'composite' }),
          expect.any(AbortSignal),
        ),
      )
      const button = await screen.findByRole('button', { name: 'Thorp 종합 비교' })
      await waitFor(() => expect(button.getAttribute('aria-pressed')).toBe('true'))
    })

    it('모르는 분야로 넘어오면 검색하지 않고 화면을 그대로 연다', async () => {
      renderPage({ entries: ['/regime-search?field=없는-분야&month=2026-07'] })

      // 오류 화면을 띄우지 않는다. 고를 수 있는 분야가 목록에 있으므로 사용자는 이어서 쓴다.
      await screen.findByLabelText('경제 분야')
      expect(vi.mocked(getRegimeSearch)).not.toHaveBeenCalled()
    })
  })

  it('API 분야 목록으로 검색하고 다섯 표시와 자료 기준 시점을 보인다', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(regimeResult({
      comparisonMonths: [{ indicatorId: 'indicator-1', indicator: '시험 지표', months: ['2026-05'], omitted: ['2026-06'], note: '시험 지표는 2026년 6월 자료가 없어 그 달을 빼고 비교했습니다.' }],
      counts: { evaluated: 30, eligible: 20, stored: 10, displayed: 1 },
      displayLimitNote: '가까운 순서로 10개까지 보입니다.',
    }))
    await search()
    expect(await screen.findByText('2012-03')).toBeTruthy()
    expect(vi.mocked(getRegimeSearch)).toHaveBeenCalledWith(
      { mode: 'field', field: 'field-1', baselineMonth: undefined, limit: 3 },
      expect.any(AbortSignal),
    )
    for (const label of ['검색 상태', '사례 구분', '예시 자료', '빠진 비교 시점', '역사 비교']) {
      expect(screen.getByRole('status', { name: `시험 분야 2026-07 검색 결과: ${label}` })).toBeTruthy()
    }
    expect(screen.getByText(/대상월 2026-07 · 발표일 2026-09-12/)).toBeTruthy()
    expect(screen.queryByText('30')).toBeNull()
    expect(screen.queryByText('20')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }))
    await waitFor(() => expect(vi.mocked(getRegimeSearch)).toHaveBeenLastCalledWith(
      { mode: 'field', field: 'field-1', baselineMonth: undefined, limit: 10 },
      expect.any(AbortSignal),
    ))
  })

  it('계산 불가를 정상 결과로 보여 주고 조회 실패에는 재시도를 제공한다', async () => {
    vi.mocked(getRegimeSearch)
      .mockResolvedValueOnce(regimeResult({
        status: 'NOT_COMPUTABLE', statusReason: 'NO_CANDIDATE',
        statusNote: '비교할 수 있는 과거 시점이 없습니다.', cases: [],
        counts: { evaluated: 0, eligible: 0, stored: 0, displayed: 0 },
      }))
      .mockRejectedValueOnce(new Error('조회 실패'))
    await search()
    expect(await screen.findByRole('heading', { name: '비교할 수 있는 과거 시점이 없습니다.' })).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '과거 사례 찾기' }))
    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: '비교할 수 있는 과거 시점이 없습니다.' })).toBeNull()
  })

  it('종합으로 전환하며 기준월이 달라질 때 API 문구를 알리고 종합 계산 불가는 그대로 둔다', async () => {
    vi.mocked(getRegimeSearch).mockImplementation(async (query) => query.mode === 'composite'
      ? regimeResult({ mode: 'COMPOSITE', field: null, baselineMonth: '2026-05', baselineOrigin: 'T_ALL', status: 'NOT_COMPUTABLE', statusNote: '종합 비교를 계산할 수 없습니다.', cases: [] })
      : regimeResult())
    await search()
    await screen.findByText('2012-03')
    fireEvent.click(screen.getByRole('button', { name: 'Thorp 종합 비교' }))
    expect(await screen.findByRole('heading', { name: '종합 비교를 계산할 수 없습니다.' })).toBeTruthy()
    expect(screen.getByRole('status', { name: '종합 2026-05 검색 결과: 기준월 변경' }).textContent).toContain('전환 안내')
    expect(screen.queryByText('2012-03')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '과거 사례 찾기' }))
    expect(await screen.findByText('2012-03')).toBeTruthy()
  })

  it('사례 상세를 검색 식별자에 고정해 조회하고 종합 분야 내역을 표시한다', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(regimeResult({ mode: 'COMPOSITE', field: null }))
    vi.mocked(getRegimeCaseDetail).mockResolvedValue(regimeDetail({ contribution: { 'field-1': '1.000000000000' } }))
    await search()
    await screen.findByText('2012-03')
    fireEvent.click(screen.getByRole('button', { name: '상세 보기' }))
    expect(await screen.findByRole('region', { name: '2012-03 사례 상세' })).toBeTruthy()
    expect(vi.mocked(getRegimeCaseDetail)).toHaveBeenCalledWith('2012-03', { searchId: 'search-1' }, expect.any(AbortSignal))
    expect(screen.getByText(fullText('실제 거리 기여 100%'))).toBeTruthy()
  })

  it('과거 기준월을 지정하면 요청과 입력, 결과 제목에 그 월을 유지한다', async () => {
    vi.mocked(getRegimeSearch).mockImplementation(async (query) => regimeResult({
      baselineMonth: query.baselineMonth ?? '2026-07',
      baselineOrigin: query.baselineMonth ? 'USER_SPECIFIED' : 'T_G',
    }))
    await search()
    await screen.findByText('2012-03')
    const month = screen.getByLabelText('기준월 지정') as HTMLInputElement
    expect(month.min).toBe('1995-04')
    expect(month.max).toBe('2026-07')
    fireEvent.change(month, { target: { value: '2020-04' } })
    fireEvent.click(screen.getByRole('button', { name: '과거 사례 찾기' }))
    await waitFor(() => expect(vi.mocked(getRegimeSearch)).toHaveBeenLastCalledWith(
      { mode: 'field', field: 'field-1', baselineMonth: '2020-04', limit: 3 },
      expect.any(AbortSignal),
    ))
    expect(month.value).toBe('2020-04')
    expect(await screen.findByText('2020-04')).toBeTruthy()
    expect(screen.getByRole('status', { name: '시험 분야 2020-04 검색 결과: 기준월 변경' }).textContent).toContain('기준월을 2020-04로')
    fireEvent.click(screen.getByRole('button', { name: 'Thorp 종합 비교' }))
    await waitFor(() => expect(vi.mocked(getRegimeSearch)).toHaveBeenLastCalledWith(
      { mode: 'composite', field: undefined, baselineMonth: '2020-04', limit: 3 },
      expect.any(AbortSignal),
    ))
  })

  it('선택한 월이 종합 최신월보다 뒤이면 종합 모드의 최신월로 전환한다', async () => {
    vi.mocked(getRegimeSearch).mockImplementation(async (query) => regimeResult({
      mode: query.mode === 'composite' ? 'COMPOSITE' : 'FIELD',
      field: query.mode === 'composite' ? null : { id: 'field-1', name: '시험 분야' },
      baselineMonth: query.mode === 'composite' ? '2026-05' : '2026-07',
    }))
    await search()
    await screen.findByText('2012-03')
    fireEvent.change(screen.getByLabelText('기준월 지정'), { target: { value: '2026-07' } })
    fireEvent.click(screen.getByRole('button', { name: 'Thorp 종합 비교' }))
    await waitFor(() => expect(vi.mocked(getRegimeSearch)).toHaveBeenLastCalledWith(
      { mode: 'composite', field: undefined, baselineMonth: undefined, limit: 3 },
      expect.any(AbortSignal),
    ))
    expect((screen.getByLabelText('기준월 지정') as HTMLInputElement).value).toBe('')
    expect(screen.getByRole('status', { name: '종합 2026-05 검색 결과: 기준월 변경' }).textContent).toContain('전환 안내')
  })

  it('키보드로 검색·기준월·모드 전환·더 보기·상세 열기와 다섯 표시에 도달한다', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(regimeResult({
      comparisonMonths: [{ indicatorId: 'indicator-1', indicator: '시험 지표', months: ['2026-05'], omitted: ['2026-06'], note: '빠진 달 안내' }],
      counts: { evaluated: 10, eligible: 10, stored: 10, displayed: 1 },
    }))
    vi.mocked(getRegimeCaseDetail).mockResolvedValue(regimeDetail())
    await search()
    await screen.findByText('2012-03')
    const user = userEvent.setup()
    screen.getByLabelText('경제 분야').focus()
    await user.tab()
    expect(document.activeElement).toBe(screen.getByLabelText('기준월 지정'))
    await user.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '과거 사례 찾기' }))
    await user.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Thorp 종합 비교' }))
    const reached: string[] = []
    for (let index = 0; index < 5; index += 1) {
      await user.tab()
      reached.push(document.activeElement?.getAttribute('aria-label') ?? '')
    }
    // 접어 두는 자리에 넣지 않는다 — 펼치기 전에 이미 탭 순서에 있다(FR-037 ⑤).
    for (const element of screen.getAllByRole('status')) {
      expect(element.closest('details,[aria-expanded]')).toBeNull()
    }
    expect(reached).toEqual([
      '시험 분야 2026-07 검색 결과: 검색 상태',
      '시험 분야 2026-07 검색 결과: 예시 자료',
      '시험 분야 2026-07 검색 결과: 빠진 비교 시점',
      '시험 분야 2026-07 검색 결과: 역사 비교',
      '시험 분야 2026-07 검색 결과: 사례 구분',
    ])
    await user.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '상세 보기' }))
    await user.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '더 보기' }))
    await user.keyboard('{Enter}')
    await waitFor(() => expect(vi.mocked(getRegimeSearch)).toHaveBeenLastCalledWith(
      expect.objectContaining({ limit: 10 }),
      expect.any(AbortSignal),
    ))
    await user.tab({ shift: true })
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '상세 보기' }))
    await user.keyboard('{Enter}')
    expect(await screen.findByRole('region', { name: '2012-03 사례 상세' })).toBeTruthy()
  })

  /** 011 T087: 사례 상세의 사후 구간 자리에서 011 시작 주소로 간다. 섹터는 알 수 없어 싣지 않는다. */
  it('사례 상세에서 011 시작 주소로 가는 링크를 보인다', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(regimeResult())
    vi.mocked(getRegimeCaseDetail).mockResolvedValue(regimeDetail())
    await search()

    fireEvent.click(await screen.findByRole('button', { name: '상세 보기' }))
    const link = await screen.findByRole('link', { name: '이 기간으로 전략 비교' })

    const url = new URL(link.getAttribute('href') ?? '', 'http://localhost')
    expect(url.pathname).toBe('/regime-link/start')
    expect(url.searchParams.get('case')).toBe('search-1~2012-03')
    expect(url.searchParams.has('sector')).toBe(false)
  })

  it('?searchId= 로 열면 저장된 판을 고정 조회하고 폼 검색 API 는 부르지 않는다(006 R17)', async () => {
    vi.mocked(getRegimeSearchById).mockResolvedValue(regimeResult({ searchId: 'saved-1' }))
    renderPage({ entries: ['/regime-search?searchId=saved-1'], user: authenticatedUser })
    expect(await screen.findByText('2012-03')).toBeTruthy()
    expect(vi.mocked(getRegimeSearchById)).toHaveBeenCalledWith('saved-1', 3, expect.any(AbortSignal))
    expect(vi.mocked(getRegimeSearch)).not.toHaveBeenCalled()
    // 저장된 판을 여는 것은 열람이다. 진입 경로 없이 기록을 보내면 원래 기록 옆에 다른 경로의 기록이 하나 더 생긴다.
    expect(vi.mocked(recordRegimeSearch)).not.toHaveBeenCalled()
    expect(screen.getByRole('status', { name: '시험 분야 2026-07 검색 결과: 저장된 검색' })).toBeTruthy()

    // 폼으로 새로 검색하면 고정 조회를 풀고 최신 판을 연다.
    vi.mocked(getRegimeSearch).mockResolvedValue(regimeResult({ searchId: 'fresh-1' }))
    fireEvent.change(await screen.findByLabelText('경제 분야'), { target: { value: 'field-1' } })
    fireEvent.click(screen.getByRole('button', { name: '과거 사례 찾기' }))
    await waitFor(() => expect(vi.mocked(getRegimeSearch)).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.queryByRole('status', { name: '시험 분야 2026-07 검색 결과: 저장된 검색' })).toBeNull())
    // 고정 조회를 푼 뒤의 검색은 정상적으로 남는다. 저장된 판(saved-1)은 여전히 남지 않는다 — 파라미터가 지워지는
    // 커밋에서 result 가 아직 옛 판이면 옛 searchId 가 MARKET_FLOW 로 나갈 수 있어, 횟수 1 이 그것을 잠근다.
    await waitFor(() =>
      expect(vi.mocked(recordRegimeSearch)).toHaveBeenCalledWith('fresh-1', 'MARKET_FLOW', 'T_G'),
    )
    expect(vi.mocked(recordRegimeSearch)).toHaveBeenCalledTimes(1)
  })

  it('로그인 사용자의 검색은 진입 경로·기준월 근거와 함께 한 번만 내 기록에 남긴다(010 FR-032)', async () => {
    // 더 보기가 보이려면 저장된 사례가 표시 수보다 많아야 한다(첫 검사와 같은 모양).
    vi.mocked(getRegimeSearch).mockResolvedValue(regimeResult({
      baselineOrigin: 'T_G',
      counts: { evaluated: 30, eligible: 20, stored: 10, displayed: 1 },
      displayLimitNote: '가까운 순서로 10개까지 보입니다.',
    }))
    vi.mocked(recordRegimeSearch).mockResolvedValue({} as never)
    await search({ user: authenticatedUser })
    await screen.findByText('2012-03')
    await waitFor(() => expect(vi.mocked(recordRegimeSearch)).toHaveBeenCalledWith('search-1', 'MARKET_FLOW', 'T_G'))
    // 더 보기로 같은 검색을 다시 받아도 기록은 한 번이다.
    fireEvent.click(screen.getByRole('button', { name: '더 보기' }))
    await waitFor(() => expect(vi.mocked(getRegimeSearch)).toHaveBeenCalledTimes(2))
    expect(vi.mocked(recordRegimeSearch)).toHaveBeenCalledTimes(1)
  })

  it('전략 결과에서 들어왔으면 그 경로로 남기고, 비로그인은 남기지 않는다', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(regimeResult())
    vi.mocked(recordRegimeSearch).mockResolvedValue({} as never)
    const { unmount } = render(
      <MemoryRouter initialEntries={[{ pathname: '/regime-search', state: { entryPath: 'STRATEGY_RESULT' } }]}>
        <AuthContext.Provider value={{ status: 'authenticated', user: authenticatedUser, login: vi.fn(), logout: vi.fn(), recheck: vi.fn() }}>
          <RegimeSearchPage />
        </AuthContext.Provider>
      </MemoryRouter>,
    )
    fireEvent.change(await screen.findByLabelText('경제 분야'), { target: { value: 'field-1' } })
    fireEvent.click(screen.getByRole('button', { name: '과거 사례 찾기' }))
    await waitFor(() => expect(vi.mocked(recordRegimeSearch)).toHaveBeenCalledWith('search-1', 'STRATEGY_RESULT', 'T_G'))
    unmount()

    vi.mocked(recordRegimeSearch).mockClear()
    await search()
    await screen.findByText('2012-03')
    expect(vi.mocked(recordRegimeSearch)).not.toHaveBeenCalled()
  })

  it('기록에 실패해도 검색 결과는 그대로이고 한 줄만 덧붙인다', async () => {
    vi.mocked(getRegimeSearch).mockResolvedValue(regimeResult())
    vi.mocked(recordRegimeSearch).mockRejectedValue(new Error('network'))
    await search({ user: authenticatedUser })
    expect(await screen.findByText('2012-03')).toBeTruthy()
    expect(await screen.findByRole('status', { name: '시험 분야 2026-07 검색 결과: 내 기록' })).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

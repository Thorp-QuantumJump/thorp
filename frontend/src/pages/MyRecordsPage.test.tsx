import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError } from '@/api/client'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { ProtectedRoute } from '@/auth/ProtectedRoute'
import { NOT_RECORDED } from '@/components/records/RecordDetailPanel'
import { NO_LINK_NOTICE } from '@/components/records/RegimeRecordPanel'
import { WITHDRAWN_REASON } from '@/components/records/recordLabels'
import { SECTOR_SELECTION_PATH, STRATEGY_SETTINGS_PATH } from '@/components/result/prefillStrategySettings'
import { authenticatedUser } from '@/test/authFixtures'
import {
  analysisDetail,
  analysisRecord,
  bundleDetail,
  bundleRecord,
  listPage,
  regimeDetailRecord,
  regimeRecord,
  regimeRecordFromResult,
  reopenableDetail,
  unavailableDetail,
  withdrawnRegimeRecord,
} from '@/test/myRecordsFixtures'
import {
  acceptedFixture,
  completedCandidate,
  configFixture,
  excludedCandidate,
} from '@/test/paramSearchFixtures'
import { EMPTY_MESSAGE, MyRecordsPage } from './MyRecordsPage'

/** 006 T026. quickstart S1·S12·S13, 원칙 II·III. */

const apiMocks = vi.hoisted(() => ({
  fetchMyRecords: vi.fn(),
  fetchMyRecord: vi.fn(),
  renameMyRecord: vi.fn(),
  deleteMyRecord: vi.fn(),
  downloadMyRecordExport: vi.fn(),
}))
const otherMocks = vi.hoisted(() => ({
  fetchSectorCatalog: vi.fn(),
  fetchStrategyRegistry: vi.fn(),
  getParamSearch: vi.fn(),
  getParamSearchCandidates: vi.fn(),
  getRegimeBaselineMonths: vi.fn(),
}))
const prefillMocks = vi.hoisted(() => ({ prefillStrategySettings: vi.fn() }))

vi.mock('@/api/myRecords', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/myRecords')>()
  return { ...actual, ...apiMocks }
})
vi.mock('@/api/sectors', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/sectors')>()
  return { ...actual, fetchSectorCatalog: otherMocks.fetchSectorCatalog }
})
vi.mock('@/api/strategyRegistry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/strategyRegistry')>()
  return { ...actual, fetchStrategyRegistry: otherMocks.fetchStrategyRegistry }
})
vi.mock('@/components/result/prefillStrategySettings', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/components/result/prefillStrategySettings')>()
  return { ...actual, prefillStrategySettings: prefillMocks.prefillStrategySettings }
})
vi.mock('@/api/paramSearch', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/paramSearch')>()
  return {
    ...actual,
    getParamSearch: otherMocks.getParamSearch,
    getParamSearchCandidates: otherMocks.getParamSearchCandidates,
  }
})
vi.mock('@/api/regime', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/regime')>()
  return { ...actual, getRegimeBaselineMonths: otherMocks.getRegimeBaselineMonths }
})
vi.mock('@/components/navigation/AppHeader', () => ({ AppHeader: () => <header>Thorp</header> }))

const anonymousAuth: AuthContextValue = {
  status: 'anonymous',
  user: null,
  login: vi.fn(),
  logout: vi.fn(),
  recheck: vi.fn(),
}
const authenticatedAuth: AuthContextValue = {
  ...anonymousAuth,
  status: 'authenticated',
  user: authenticatedUser,
  recheck: vi.fn().mockResolvedValue({ status: 'authenticated', user: authenticatedUser }),
}

function renderPage(path = '/records', auth = authenticatedAuth) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthContext.Provider value={auth}>
        <Routes>
          <Route
            path="/records/*"
            element={
              <ProtectedRoute>
                <MyRecordsPage />
              </ProtectedRoute>
            }
          />
          <Route path="/login" element={<h1>로그인 화면</h1>} />
          <Route path={STRATEGY_SETTINGS_PATH} element={<h1>전략 설정 화면</h1>} />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

const performanceWords = /수익률|하락 폭|비용순|MDD/

describe('MyRecordsPage', () => {
  beforeEach(() => {
    apiMocks.fetchMyRecords.mockReset()
    apiMocks.fetchMyRecord.mockReset()
    apiMocks.renameMyRecord.mockReset()
    apiMocks.deleteMyRecord.mockReset()
    apiMocks.downloadMyRecordExport.mockReset()
    apiMocks.downloadMyRecordExport.mockResolvedValue(undefined)
    otherMocks.fetchSectorCatalog.mockReset()
    otherMocks.fetchStrategyRegistry.mockReset()
    prefillMocks.prefillStrategySettings.mockReset()
    otherMocks.fetchSectorCatalog.mockReturnValue(new Promise(() => {}))
    otherMocks.fetchStrategyRegistry.mockReturnValue(new Promise(() => {}))
    otherMocks.getParamSearch.mockReset()
    otherMocks.getParamSearchCandidates.mockReset()
    otherMocks.getRegimeBaselineMonths.mockReset()
    otherMocks.getRegimeBaselineMonths.mockReturnValue(new Promise(() => {}))
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('fixture 세 종류를 이름·섹터·전략·기간·저장일·상태로 보이고 종류별 다시 보기 주소를 건다', async () => {
    apiMocks.fetchMyRecords.mockResolvedValue(listPage)

    renderPage()

    const list = await screen.findByRole('list', { name: '내 기록' })
    const rows = within(list).getAllByRole('listitem').filter((el) => el.hasAttribute('data-record-id'))
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByRole('heading', { name: analysisRecord.name })).toBeTruthy()
    expect(within(rows[0]).getByText('SEMICONDUCTOR')).toBeTruthy()
    expect(within(rows[0]).getByText('2019-01-02 ~ 2026-08-29')).toBeTruthy()
    expect(within(rows[0]).getByText('완료')).toBeTruthy()
    // 상태 사유는 상태 옆에 있고 성과와 섞이지 않는다(FR-014).
    expect(within(rows[1]).getByText(/부분 완료/).textContent).toContain('실행 시간 한도에 닿았습니다')
    expect(document.body.textContent).not.toContain('TIME_LIMIT')

    const review = (row: HTMLElement) => within(row).getByRole('link', { name: '다시 보기' })
    expect(review(rows[0]).getAttribute('href')).toBe(
      `/results/${analysisRecord.resultToken}?from=records&recordId=${analysisRecord.recordId}`,
    )
    expect(review(rows[1]).getAttribute('href')).toBe('/param-search?searchId=PS-20260917-000009')
    expect(review(rows[2]).getAttribute('href')).toContain('/regime-search?searchId=FIELD%3AEMPLOYMENT')
    expect(document.body.textContent).not.toMatch(performanceWords)
  })

  it('행마다 주 모양 버튼은 다시 보기 하나다 — 설정 바꿔 비교는 보조 모양이다(원칙 II)', async () => {
    apiMocks.fetchMyRecords.mockResolvedValue(listPage)

    renderPage()

    const list = await screen.findByRole('list', { name: '내 기록' })
    const rows = within(list).getAllByRole('listitem').filter((el) => el.hasAttribute('data-record-id'))
    for (const row of rows) {
      const primary = row.querySelectorAll('[data-slot="button"][data-variant="default"]')
      expect(primary).toHaveLength(1)
      expect(primary[0].textContent).toBe('다시 보기')
    }
    expect(within(rows[0]).getByRole('button', { name: '설정 바꿔 비교' }).getAttribute('data-variant')).toBe(
      'outline',
    )
    // 경제환경 기록에는 옮길 조건이 없어 버튼을 두지 않는다(contracts/record-handoff.md 규칙 3).
    expect(within(rows[2]).queryByRole('button', { name: '설정 바꿔 비교' })).toBeNull()
  })

  it('설정 바꿔 비교는 상세를 받아 reopenPayload 를 005 함수에 그대로 넘기고 옮긴다(FR-007, R4)', async () => {
    const user = userEvent.setup()
    apiMocks.fetchMyRecords.mockResolvedValue({ items: [analysisRecord], nextCursor: null })
    apiMocks.fetchMyRecord.mockResolvedValue(reopenableDetail)
    prefillMocks.prefillStrategySettings.mockImplementation(
      async (_payload: unknown, navigate: (path: string) => void) => {
        navigate(STRATEGY_SETTINGS_PATH)
        return { status: 'moved' as const }
      },
    )

    renderPage()
    await user.click(await screen.findByRole('button', { name: '설정 바꿔 비교' }))

    expect(await screen.findByRole('heading', { name: '전략 설정 화면' })).toBeTruthy()
    expect(apiMocks.fetchMyRecord).toHaveBeenCalledWith(analysisRecord.recordId, expect.any(AbortSignal))
    expect(prefillMocks.prefillStrategySettings).toHaveBeenCalledTimes(1)
    const [passed] = prefillMocks.prefillStrategySettings.mock.calls[0]
    expect(passed).toBe(reopenableDetail.reopenPayload)
    expect(passed.warmupTradingDays).toBe(77)
  })

  it('대응표가 달라 섹터를 넘기지 못하면 그 자리에 문장과 섹터·비중 단계 연결을 두고 옮기지 않는다', async () => {
    const user = userEvent.setup()
    apiMocks.fetchMyRecords.mockResolvedValue({ items: [analysisRecord], nextCursor: null })
    apiMocks.fetchMyRecord.mockResolvedValue(reopenableDetail)
    prefillMocks.prefillStrategySettings.mockResolvedValue({
      status: 'reselectSectors',
      message: '이 결과의 섹터 대응표가 지금 대응표와 달라 섹터는 넘기지 않았습니다.',
    })

    renderPage()
    await user.click(await screen.findByRole('button', { name: '설정 바꿔 비교' }))

    // 페이지 알림 영역(role=status)이 늘 DOM 에 있으므로 문장으로 그 자리를 집는다.
    const notice = (await screen.findByText(/섹터는 넘기지 않았습니다/)).closest(
      '[role="status"]',
    ) as HTMLElement | null
    expect(notice).not.toBeNull()
    if (!notice) throw new Error('알림 자리를 찾지 못했다')
    expect(within(notice).getByRole('link', { name: '섹터·비중 단계로 가기' }).getAttribute('href')).toBe(
      SECTOR_SELECTION_PATH,
    )
    expect(screen.queryByRole('heading', { name: '전략 설정 화면' })).toBeNull()
  })

  it('펼치지 않고 눌러 열 수 없다는 판정을 받으면 문장을 보이고 버튼을 거둔다 — 같은 답을 다시 받지 않는다', async () => {
    const user = userEvent.setup()
    apiMocks.fetchMyRecords.mockResolvedValue({ items: [analysisRecord], nextCursor: null })
    apiMocks.fetchMyRecord.mockResolvedValue(unavailableDetail)

    renderPage()
    await user.click(await screen.findByRole('button', { name: '설정 바꿔 비교' }))

    expect((await screen.findByRole('alert')).textContent).toContain('열 수 없습니다')
    expect(screen.queryByRole('button', { name: '설정 바꿔 비교' })).toBeNull()
    expect(apiMocks.fetchMyRecord).toHaveBeenCalledTimes(1)
    expect(prefillMocks.prefillStrategySettings).not.toHaveBeenCalled()
  })

  it('상세가 열 수 없다고 판정한 기록은 자세히를 열면 버튼이 사라지고, 열려 있던 상세는 다시 받지 않는다', async () => {
    const user = userEvent.setup()
    apiMocks.fetchMyRecords.mockResolvedValue({ items: [analysisRecord], nextCursor: null })
    apiMocks.fetchMyRecord.mockResolvedValue(unavailableDetail)

    renderPage()
    expect(await screen.findByRole('button', { name: '설정 바꿔 비교' })).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '자세히' }))

    await screen.findByRole('heading', { name: '저장 당시 조건' })
    expect(screen.queryByRole('button', { name: '설정 바꿔 비교' })).toBeNull()
    expect(apiMocks.fetchMyRecord).toHaveBeenCalledTimes(1)
    expect(prefillMocks.prefillStrategySettings).not.toHaveBeenCalled()
  })

  it('기본 보기는 상태를 보내지 않고(서버 기본 = 완료), 정렬 선택지에 성과가 없다', async () => {
    apiMocks.fetchMyRecords.mockResolvedValue(listPage)

    renderPage()
    await screen.findByRole('list', { name: '내 기록' })

    expect(apiMocks.fetchMyRecords).toHaveBeenCalledWith({}, expect.any(AbortSignal))
    for (const status of ['완료', '문턱 적용', '실패', '취소']) {
      expect((screen.getByRole('checkbox', { name: status }) as HTMLInputElement).checked).toBe(false)
    }
    const sort = screen.getByRole('combobox', { name: '정렬' })
    const options = within(sort)
      .getAllByRole('option')
      .map((option) => option.textContent)
    expect(options).toEqual(['저장일 최신순', '저장일 오래된순', '이름순'])
    expect(options.join(' ')).not.toMatch(performanceWords)
  })

  it('상태 필터를 켜면 그 상태로 다시 부른다(S13-3)', async () => {
    const user = userEvent.setup()
    apiMocks.fetchMyRecords.mockResolvedValue(listPage)

    renderPage()
    await screen.findByRole('list', { name: '내 기록' })

    await user.click(screen.getByRole('checkbox', { name: '실패' }))

    expect(apiMocks.fetchMyRecords).toHaveBeenLastCalledWith(
      { status: ['FAILED'] },
      expect.any(AbortSignal),
    )
  })

  it('빈 목록은 한 문장과 전략 검증하기 연결이다(FR-001)', async () => {
    apiMocks.fetchMyRecords.mockResolvedValue({ items: [], nextCursor: null })

    renderPage()

    expect(await screen.findByText(EMPTY_MESSAGE)).toBeTruthy()
    expect(screen.getByRole('link', { name: '전략 검증하기' }).getAttribute('href')).toBe(
      '/diagnosis/sectors',
    )
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('철회된 경제환경 항목은 다시 보기가 비활성이고 이유가 보인다', async () => {
    apiMocks.fetchMyRecords.mockResolvedValue({ items: [withdrawnRegimeRecord], nextCursor: null })

    renderPage()

    const button = await screen.findByRole('button', { name: '다시 보기' })
    // disabled 가 아니라 aria-disabled 다 — 초점을 받아야 사유가 읽힌다(FR-021).
    expect(button.getAttribute('aria-disabled')).toBe('true')
    expect((button as HTMLButtonElement).disabled).toBe(false)
    expect(screen.getByText(WITHDRAWN_REASON)).toBeTruthy()
    // 상태 필터의 체크박스 라벨과 겹치므로 행 안에서 찾는다.
    const list = screen.getByRole('list', { name: '내 기록' })
    expect(within(list).getByText('더 볼 수 없음')).toBeTruthy()
  })

  it('자세히를 열면 참조 버전과 대응표 판정 문장이 보이고, 없는 버전은 기록되지 않음이며 Esc로 닫힌다', async () => {
    const user = userEvent.setup()
    apiMocks.fetchMyRecords.mockResolvedValue({ items: [analysisRecord], nextCursor: null })
    apiMocks.fetchMyRecord.mockResolvedValue(analysisDetail)

    renderPage()
    const toggle = await screen.findByRole('button', { name: '자세히' })
    await user.click(toggle)

    expect(await screen.findByText('2차전지 섹터의 대표 ETF가 저장 당시와 다릅니다.', { exact: false })).toBeTruthy()
    expect(screen.getByText('CP-2026-09-17-001 (잠정)')).toBeTruthy()
    // 등록표 버전은 오지 않았다. 지어내지 않고 '기록되지 않음'이다(원칙 VI).
    const registry = screen.getByText('등록표')
    expect(registry.nextElementSibling?.textContent).toBe(NOT_RECORDED)
    expect(document.body.textContent).not.toMatch(/dependencies|numpy|scipy/)

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('heading', { name: '저장 당시 조건' })).toBeNull()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '자세히' }))
  })

  it('더 보기는 커서로 다음 쪽을 받아 뒤에 붙인다', async () => {
    const user = userEvent.setup()
    apiMocks.fetchMyRecords
      .mockResolvedValueOnce({ items: [analysisRecord], nextCursor: 'c1' })
      .mockResolvedValueOnce({ items: [withdrawnRegimeRecord], nextCursor: null })

    renderPage()
    await user.click(await screen.findByRole('button', { name: '더 보기' }))

    expect(apiMocks.fetchMyRecords).toHaveBeenLastCalledWith({ cursor: 'c1' }, expect.any(AbortSignal))
    const list = await screen.findByRole('list', { name: '내 기록' })
    expect(
      within(list).getAllByRole('listitem').filter((el) => el.hasAttribute('data-record-id')),
    ).toHaveLength(2)
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull()
  })

  it('더 보기가 실패하면 받아 둔 항목은 남고, 다시 누르면 같은 커서로 다시 받는다', async () => {
    const user = userEvent.setup()
    apiMocks.fetchMyRecords
      .mockResolvedValueOnce({ items: [analysisRecord], nextCursor: 'c1' })
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ items: [withdrawnRegimeRecord], nextCursor: null })

    renderPage()
    await user.click(await screen.findByRole('button', { name: '더 보기' }))

    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.getByRole('heading', { name: analysisRecord.name })).toBeTruthy()

    // 같은 커서를 다시 넣어도 요청이 나가야 한다. 안 나가면 눌러도 아무 일이 없는 버튼이 된다.
    await user.click(screen.getByRole('button', { name: '더 보기' }))

    expect(await screen.findByRole('heading', { name: withdrawnRegimeRecord.name })).toBeTruthy()
    expect(apiMocks.fetchMyRecords).toHaveBeenCalledTimes(3)
    expect(apiMocks.fetchMyRecords).toHaveBeenLastCalledWith({ cursor: 'c1' }, expect.any(AbortSignal))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('탐색 묶음을 펼치면 부분 완료 N/M, 후보 수, 선정 규칙, 후보 상태·사유가 보이고 정렬 컨트롤은 없다(S7-2·S7-3)', async () => {
    const user = userEvent.setup()
    const searchId = bundleRecord.kind === 'SEARCH_BUNDLE' ? bundleRecord.searchId : ''
    apiMocks.fetchMyRecords.mockResolvedValue({ items: [bundleRecord], nextCursor: null })
    apiMocks.fetchMyRecord.mockResolvedValue(bundleDetail)
    otherMocks.getParamSearch.mockResolvedValue({
      ...acceptedFixture,
      searchId,
      status: 'PARTIAL',
      stopReason: 'TIME_LIMIT',
      partial: true,
      partialNote: '실행 시간 한도로 6개까지 계산했습니다',
      counts: { expected: 10, valid: 9, completed: 6, failed: 1, excluded: 1, pending: 0, skipped: 2 },
      config: configFixture,
    })
    otherMocks.getParamSearchCandidates.mockResolvedValue({
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
      content: [completedCandidate, excludedCandidate],
    })

    renderPage()
    await user.click(await screen.findByRole('button', { name: '자세히' }))

    const banner = await screen.findByText(/부분 완료 — 6\/10 후보/)
    expect(banner.textContent).toContain('실행 시간 한도로 6개까지')
    expect(screen.getByText('예상 10 · 유효 9 · 완료 6 · 실패 1 · 제외 1 · 미실행 2')).toBeTruthy()
    expect(screen.getByText(/손실 제약·인접 설정 안정성 통과 후 비용 차감 후 수익 기준 5개 표시/)).toBeTruthy()
    expect(screen.getByText(/기울기 관측 창: 5, 10, 20 거래일/)).toBeTruthy()

    const table = await screen.findByRole('table')
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(rows[0].textContent).toContain('완료')
    expect(rows[1].textContent).toContain('계산 전 제외')
    // 사유는 서버 문장 그대로다.
    expect(rows[1].textContent).toContain(excludedCandidate.selectionReason?.message ?? '')
    // 성과순 정렬 컨트롤이 없다(원칙 III). 성과 열도 없다.
    expect(within(table).queryByRole('button')).toBeNull()
    expect(within(table).queryByRole('columnheader', { name: /수익|하락|비용/ })).toBeNull()
    expect(otherMocks.getParamSearchCandidates).toHaveBeenCalledWith(
      searchId,
      expect.objectContaining({ size: 20 }),
    )
  })

  it('같은 경제환경 검색이라도 진입 경로가 다르면 두 항목이고, 펼치면 연결 정보 자리에 고정 문장이 있다(S7-5·S7-6)', async () => {
    const user = userEvent.setup()
    apiMocks.fetchMyRecords.mockResolvedValue({
      items: [regimeRecordFromResult, regimeRecord],
      nextCursor: null,
    })
    apiMocks.fetchMyRecord.mockResolvedValue(regimeDetailRecord)
    // 분야 이름은 010 분야 목록에서 한 번 받는다. 코드(EMPLOYMENT)를 그대로 보이지 않는다.
    otherMocks.getRegimeBaselineMonths.mockResolvedValue({
      byField: [{ id: 'EMPLOYMENT', name: '고용환경', baselineMonth: '2026-08' }],
      composite: null,
      range: null,
    })

    renderPage()

    const list = await screen.findByRole('list', { name: '내 기록' })
    const rows = within(list).getAllByRole('listitem').filter((el) => el.hasAttribute('data-record-id'))
    expect(rows.map((row) => row.getAttribute('data-record-id'))).toEqual([
      regimeRecordFromResult.recordId,
      regimeRecord.recordId,
    ])

    await user.click(within(rows[1]).getByRole('button', { name: '자세히' }))

    expect(await screen.findByText(NO_LINK_NOTICE)).toBeTruthy()
    expect(screen.getByText('분야 검색')).toBeTruthy()
    await waitFor(() => expect(screen.getByText('분야').nextElementSibling?.textContent).toBe('고용환경'))
    expect(screen.queryByText('EMPLOYMENT')).toBeNull()
    expect(screen.getByText('분야의 최신 기준월(T_g)')).toBeTruthy()
    expect(screen.getByText('시장 흐름에서')).toBeTruthy()
    expect(screen.getByText('REGIME-V1')).toBeTruthy()
    // 연결 정보에 임의 값이 없다 — 섹터·전략·평가 구간 표기가 어디에도 없다.
    expect(document.body.textContent).not.toMatch(/연결한 섹터|사후 평가 구간|가용성 필터/)
  })

  it('비로그인은 ProtectedRoute 가 로그인으로 보내고 기록을 부르지 않는다(S12)', async () => {
    renderPage('/records', anonymousAuth)

    expect(await screen.findByRole('heading', { name: '로그인 화면' })).toBeTruthy()
    expect(apiMocks.fetchMyRecords).not.toHaveBeenCalled()
  })

  // --- FE-4: 더 보기 메뉴(T045·T048·T049) ------------------------------------------

  async function openMenu(name: string) {
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: `${name} 더 보기` }))
    return user
  }

  it('목록을 그려도 초점이 메뉴 버튼으로 튀지 않는다', async () => {
    apiMocks.fetchMyRecords.mockResolvedValue(listPage)

    renderPage()
    await screen.findByRole('list', { name: '내 기록' })

    // 줄마다 있는 '더 보기'가 마운트 때 초점을 가져가면 사용자는 화면 맨 아래에서 Tab 을 시작한다.
    expect(document.activeElement?.getAttribute('aria-label') ?? '').not.toMatch(/더 보기$/)
  })

  it('앞선 알림이 다음 조회의 불러오는 중 문장을 가리지 않는다', async () => {
    apiMocks.fetchMyRecords.mockResolvedValueOnce({ items: [analysisRecord], nextCursor: null })
    apiMocks.renameMyRecord.mockResolvedValue({ ...analysisDetail, name: '바꾼 이름' })

    renderPage()
    const user = await openMenu(analysisRecord.name)
    await user.click(screen.getByRole('menuitem', { name: '이름 바꾸기' }))
    await user.click(screen.getByRole('button', { name: '저장' }))
    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('바꾼 이름'))

    // 다음 조회는 응답하지 않게 두어 불러오는 중 상태를 붙잡는다.
    apiMocks.fetchMyRecords.mockReturnValue(new Promise(() => {}))
    await user.click(screen.getByRole('checkbox', { name: '경제환경' }))

    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toContain('기록을 불러오는 중입니다.'),
    )
  })

  it('메뉴는 Esc 로 닫히고 초점이 연 버튼으로 돌아온다', async () => {
    apiMocks.fetchMyRecords.mockResolvedValue({ items: [analysisRecord], nextCursor: null })

    renderPage()
    const user = await openMenu(analysisRecord.name)
    expect(screen.getByRole('menu')).toBeTruthy()

    await user.keyboard('{Escape}')

    expect(screen.queryByRole('menu')).toBeNull()
    expect(document.activeElement?.getAttribute('aria-label')).toBe(`${analysisRecord.name} 더 보기`)
  })

  it('이름을 바꾸면 그 줄의 이름만 제자리에서 바뀐다', async () => {
    apiMocks.fetchMyRecords.mockResolvedValue({ items: [analysisRecord], nextCursor: null })
    apiMocks.renameMyRecord.mockResolvedValue({ ...analysisDetail, name: '반도체 비중 높인 안' })

    renderPage()
    const user = await openMenu(analysisRecord.name)
    await user.click(screen.getByRole('menuitem', { name: '이름 바꾸기' }))
    await user.clear(screen.getByLabelText('새 이름'))
    await user.type(screen.getByLabelText('새 이름'), '반도체 비중 높인 안')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('반도체 비중 높인 안')).toBeTruthy()
    expect(screen.queryByRole('heading', { name: analysisRecord.name })).toBeNull()
    expect(apiMocks.renameMyRecord).toHaveBeenCalledWith(analysisRecord.recordId, '반도체 비중 높인 안')
    // 목록을 다시 받지 않는다 — 처음 한 번뿐이다.
    expect(apiMocks.fetchMyRecords).toHaveBeenCalledTimes(1)
  })

  it('이름 변경 오류는 필드 오류로 보이고 이름이 그대로다', async () => {
    apiMocks.fetchMyRecords.mockResolvedValue({ items: [analysisRecord], nextCursor: null })
    apiMocks.renameMyRecord.mockRejectedValue(
      new ApiClientError('api', '이름은 1자 이상이어야 합니다.', { code: 'INVALID_INPUT' }),
    )

    renderPage()
    const user = await openMenu(analysisRecord.name)
    await user.click(screen.getByRole('menuitem', { name: '이름 바꾸기' }))
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect((await screen.findByRole('alert')).textContent).toContain('이름은 1자 이상이어야 합니다.')
    expect(screen.getByLabelText('새 이름').getAttribute('aria-invalid')).toBe('true')
    expect(screen.getByRole('heading', { name: analysisRecord.name })).toBeTruthy()
  })

  it('삭제는 확인을 거치고 그 줄을 없애며 복구를 제안하지 않는다', async () => {
    apiMocks.fetchMyRecords.mockResolvedValue({ items: [analysisRecord], nextCursor: null })
    apiMocks.deleteMyRecord.mockResolvedValue({
      recordId: analysisRecord.recordId,
      deletedAt: '2026-09-21T02:00:00Z',
    })

    renderPage()
    const user = await openMenu(analysisRecord.name)
    await user.click(screen.getByRole('menuitem', { name: '지우기' }))

    // 무엇이 남고 무엇이 사라지는지 확인 문구가 말한다(FR-017).
    expect(screen.getByText('저장된 계산 결과는 남고 내 목록에서만 사라집니다.')).toBeTruthy()
    expect(apiMocks.deleteMyRecord).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '지우기' }))

    await waitFor(() => expect(screen.queryByRole('heading', { name: analysisRecord.name })).toBeNull())
    expect(screen.queryByText(/복구|되돌리기/)).toBeNull()
  })

  it('완료 항목은 두 형식으로 내려받고 진행 중 항목은 사유와 함께 막힌다', async () => {
    apiMocks.fetchMyRecords.mockResolvedValue({
      items: [analysisRecord, { ...analysisRecord, recordId: 'MR-20260918-000099', name: '도는 중', status: 'RUNNING' }],
      nextCursor: null,
    })

    renderPage()
    const user = await openMenu(analysisRecord.name)
    await user.click(screen.getByRole('menuitem', { name: 'CSV로 내보내기' }))

    expect(apiMocks.downloadMyRecordExport).toHaveBeenCalledWith(analysisRecord.recordId, 'csv')
    expect((await screen.findByRole('status')).textContent).toContain('CSV')

    await openMenu('도는 중')
    const running = screen.getAllByRole('menuitem', { name: 'CSV로 내보내기' }).at(-1)
    expect(running?.getAttribute('aria-disabled')).toBe('true')
    expect(screen.getByText('아직 계산이 끝나지 않아 내보낼 결과가 없습니다.')).toBeTruthy()
  })

  it('내보내기가 실패하면 그 사실을 알린다 — 오류 본문을 파일로 받지 않는다', async () => {
    apiMocks.fetchMyRecords.mockResolvedValue({ items: [analysisRecord], nextCursor: null })
    apiMocks.downloadMyRecordExport.mockRejectedValue(
      new ApiClientError('api', '아직 결과가 준비되지 않았습니다.', { code: 'RESULT_NOT_READY' }),
    )

    renderPage()
    const user = await openMenu(analysisRecord.name)
    await user.click(screen.getByRole('menuitem', { name: 'JSON으로 내보내기' }))

    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toContain('내보내기에 실패했습니다'),
    )
    expect(screen.getByRole('status').textContent).toContain('아직 결과가 준비되지 않았습니다.')
  })
})

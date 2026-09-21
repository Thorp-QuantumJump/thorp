import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { ProtectedRoute } from '@/auth/ProtectedRoute'
import { NOT_RECORDED } from '@/components/records/RecordDetailPanel'
import { WITHDRAWN_REASON } from '@/components/records/recordLabels'
import { SECTOR_SELECTION_PATH, STRATEGY_SETTINGS_PATH } from '@/components/result/prefillStrategySettings'
import { authenticatedUser } from '@/test/authFixtures'
import {
  analysisDetail,
  analysisRecord,
  listPage,
  reopenableDetail,
  unavailableDetail,
  withdrawnRegimeRecord,
} from '@/test/myRecordsFixtures'
import { EMPTY_MESSAGE, MyRecordsPage } from './MyRecordsPage'

/** 006 T026. quickstart S1·S12·S13, 원칙 II·III. */

const apiMocks = vi.hoisted(() => ({
  fetchMyRecords: vi.fn(),
  fetchMyRecord: vi.fn(),
}))
const otherMocks = vi.hoisted(() => ({
  fetchSectorCatalog: vi.fn(),
  fetchStrategyRegistry: vi.fn(),
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
    otherMocks.fetchSectorCatalog.mockReset()
    otherMocks.fetchStrategyRegistry.mockReset()
    prefillMocks.prefillStrategySettings.mockReset()
    otherMocks.fetchSectorCatalog.mockReturnValue(new Promise(() => {}))
    otherMocks.fetchStrategyRegistry.mockReturnValue(new Promise(() => {}))
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

    const notice = await screen.findByRole('status')
    expect(notice.textContent).toContain('섹터는 넘기지 않았습니다')
    expect(within(notice).getByRole('link', { name: '섹터·비중 단계로 가기' }).getAttribute('href')).toBe(
      SECTOR_SELECTION_PATH,
    )
    expect(screen.queryByRole('heading', { name: '전략 설정 화면' })).toBeNull()
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

  it('비로그인은 ProtectedRoute 가 로그인으로 보내고 기록을 부르지 않는다(S12)', async () => {
    renderPage('/records', anonymousAuth)

    expect(await screen.findByRole('heading', { name: '로그인 화면' })).toBeTruthy()
    expect(apiMocks.fetchMyRecords).not.toHaveBeenCalled()
  })
})

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import { ProtectedRoute } from '@/auth/ProtectedRoute'
import { NOT_RECORDED } from '@/components/records/RecordDetailPanel'
import { WITHDRAWN_REASON } from '@/components/records/recordLabels'
import { authenticatedUser } from '@/test/authFixtures'
import {
  analysisDetail,
  analysisRecord,
  listPage,
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
    expect(within(rows[1]).getByText(/부분 완료/).textContent).toContain('TIME_LIMIT')

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
      expect(within(row).getByRole('button', { name: '설정 바꿔 비교' }).getAttribute('data-variant')).toBe(
        'outline',
      )
    }
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
    expect((button as HTMLButtonElement).disabled).toBe(true)
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

  it('비로그인은 ProtectedRoute 가 로그인으로 보내고 기록을 부르지 않는다(S12)', async () => {
    renderPage('/records', anonymousAuth)

    expect(await screen.findByRole('heading', { name: '로그인 화면' })).toBeTruthy()
    expect(apiMocks.fetchMyRecords).not.toHaveBeenCalled()
  })
})

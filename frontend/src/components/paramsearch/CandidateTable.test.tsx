import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  completedCandidate,
  configFixture,
  excludedCandidate,
  notInDisplayCandidate,
} from '@/test/paramSearchFixtures'
import type { CandidatePage } from '@/types/paramSearch'
import { CandidateTable } from './CandidateTable'

const apiMocks = vi.hoisted(() => ({
  getParamSearchCandidates: vi.fn(),
}))

vi.mock('@/api/paramSearch', async () => {
  const actual = await vi.importActual<typeof import('@/api/paramSearch')>('@/api/paramSearch')
  return { ...actual, ...apiMocks }
})

const pageFixture: CandidatePage = {
  page: 0,
  size: 20,
  totalElements: 3,
  totalPages: 1,
  content: [completedCandidate, excludedCandidate, notInDisplayCandidate],
}

function lastQuery() {
  const calls = apiMocks.getParamSearchCandidates.mock.calls
  return calls[calls.length - 1][1]
}

function renderClosedTable() {
  return render(
    <CandidateTable
      searchId="PS-20260914-000001"
      config={configFixture}
      onOpenDetail={vi.fn()}
      comparePicks={[]}
      onCompareToggle={vi.fn()}
    />,
  )
}

function renderTable() {
  const view = renderClosedTable()
  fireEvent.click(screen.getByRole('button', { name: '전체 후보 열기' }))
  return view
}

/**
 * 전체 후보 목록과 한 항목 기준 정렬(T085, FR-017·FR-011).
 *
 * <p><b>정렬은 서버가 한다.</b> 화면에서 다시 정렬하면 지금 페이지에 없는 후보가 빠진 채 정렬돼
 * 사실과 어긋난다 — 그래서 정렬을 누르면 질의가 바뀌는지를 본다.
 */
describe('CandidateTable', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.getParamSearchCandidates.mockResolvedValue(pageFixture)
  })

  it('전체 후보를 열 때만 목록을 조회하고 다시 접을 수 있다', async () => {
    const user = userEvent.setup()
    renderClosedTable()

    expect(apiMocks.getParamSearchCandidates).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '전체 후보 열기' }))
    await waitFor(() => expect(apiMocks.getParamSearchCandidates).toHaveBeenCalled())

    await user.click(screen.getByRole('button', { name: '전체 후보 접기' }))
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('처음에는 순번 오름차순으로 묻는다', async () => {
    renderTable()

    await waitFor(() => expect(apiMocks.getParamSearchCandidates).toHaveBeenCalled())
    expect(lastQuery()).toEqual({
      page: 0,
      size: 20,
      sort: 'candidateNo',
      direction: 'asc',
    })
  })

  it('지표 열을 누르면 그 항목 내림차순으로 서버에 다시 묻는다', async () => {
    const user = userEvent.setup()
    renderTable()
    await screen.findByRole('button', { name: /비용 차감 후 수익/ })

    await user.click(screen.getByRole('button', { name: /비용 차감 후 수익/ }))

    // 지표는 큰 값부터 보는 편이 낫다.
    await waitFor(() => expect(lastQuery().sort).toBe('netReturn'))
    expect(lastQuery().direction).toBe('desc')

    await user.click(screen.getByRole('button', { name: /비용 차감 후 수익/ }))
    await waitFor(() => expect(lastQuery().direction).toBe('asc'))
  })

  it('정렬 상태를 보조기술에 알린다', async () => {
    const user = userEvent.setup()
    renderTable()
    await screen.findByRole('button', { name: /거래비용/ })

    await user.click(screen.getByRole('button', { name: /거래비용/ }))

    await waitFor(() => {
      const header = screen.getByRole('columnheader', { name: /거래비용/ })
      expect(header.getAttribute('aria-sort')).toBe('descending')
    })
  })

  it('상태를 고르면 그 상태만 묻는다', async () => {
    const user = userEvent.setup()
    renderTable()
    await screen.findByLabelText('상태')

    await user.selectOptions(screen.getByLabelText('상태'), 'FAILED')

    await waitFor(() => expect(lastQuery().status).toBe('FAILED'))
    expect(lastQuery().page).toBe(0)
  })

  it('계산하지 않은 후보의 칸을 0으로 채우지 않고 사유를 적는다', async () => {
    renderTable()

    // 그 후보의 줄만 본다 — 표 전체를 보면 다른 줄의 정상 숫자가 섞인다.
    const reason = await screen.findAllByText('관측 창이 자료 구간보다 깁니다.')
    const row = reason[0].closest('tr')
    const text = row?.textContent ?? ''

    expect(text).toContain('계산 전 제외')
    // 수익·하락 폭·거래비용 세 칸이 전부 사유로 채워져 있다.
    expect(reason.length).toBeGreaterThanOrEqual(3)
    expect(text).not.toContain('%')
    expect(text).not.toContain('원')
  })

  it('이웃이 없는 안정성을 나쁜 값으로 그리지 않는다', async () => {
    apiMocks.getParamSearchCandidates.mockResolvedValue({
      ...pageFixture,
      content: [
        {
          ...completedCandidate,
          stability: { calculable: false, value: null, neighborCount: 0 },
        },
      ],
    })
    const { container } = renderTable()

    await screen.findByText('이웃한 설정이 없어 계산하지 않았습니다')
    expect(container.textContent).not.toContain('0.00%p')
  })

  it('탈락과 표시 범위 밖을 다른 말로 적는다', async () => {
    const { container } = renderTable()

    await screen.findByText('표시 후보')
    expect(container.textContent).toContain('기준은 통과했으나 표시 범위 밖')
    expect(container.textContent).toContain('계산 전 제외')
  })
})

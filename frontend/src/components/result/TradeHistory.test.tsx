import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resultDetailCompleted, resultSectorCatalog, resultToken, resultTradePage } from '@/test/resultFixtures'
import { TRADES_PAGE_SIZE } from './constants'
import { buildSectorLabels } from './sectorLabels'
import { TradeHistory } from './TradeHistory'

/** 005 T071(거래 내역). FR-007, spec Edge Case "기간·종목으로 좁혀 보기", R-11, SC-001. */

const apiMocks = vi.hoisted(() => ({ fetchResultTrades: vi.fn() }))

vi.mock('@/api/result', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/result')>()
  return { ...actual, ...apiMocks }
})

const items = resultDetailCompleted.config.items
const labels = buildSectorLabels(items, resultSectorCatalog)

function renderHistory() {
  return render(<TradeHistory token={resultToken} items={items} labels={labels} />)
}

describe('TradeHistory', () => {
  beforeEach(() => {
    apiMocks.fetchResultTrades.mockReset()
    apiMocks.fetchResultTrades.mockResolvedValue(resultTradePage)
  })

  it('열기 전에는 거래 내역을 부르지 않는다', () => {
    renderHistory()

    expect(apiMocks.fetchResultTrades).not.toHaveBeenCalled()
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('열면 첫 페이지를 정한 건수로 받고, 보유 중인 거래의 청산 값을 0으로 채우지 않는다', async () => {
    const user = userEvent.setup()
    renderHistory()

    await user.click(screen.getByRole('button', { name: '거래 내역 보기' }))
    const table = await screen.findByRole('table', { name: '거래 내역' })

    expect(apiMocks.fetchResultTrades).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchResultTrades.mock.calls[0][0]).toBe(resultToken)
    expect(apiMocks.fetchResultTrades.mock.calls[0][1]).toEqual({ page: 0, size: TRADES_PAGE_SIZE })

    const openRow = within(table).getByRole('row', { name: /2024-12-02/ })
    expect(within(openRow).getByText('보유 중')).not.toBeNull()
    expect(within(openRow).getAllByText('없음')).toHaveLength(2)
    expect(within(openRow).queryByText('0원')).toBeNull()
    expect(within(openRow).getByText('조선소 · 합성 조선소 ETF')).not.toBeNull()
    // 거래별 비용 열
    expect(within(table).getByText('1,438원')).not.toBeNull()
    expect(within(table).getByText('4,793원')).not.toBeNull()
  })

  it('종목·상태·기간 조건을 서버 질의로 넘기고 첫 페이지부터 다시 받는다', async () => {
    const user = userEvent.setup()
    renderHistory()
    await user.click(screen.getByRole('button', { name: '거래 내역 보기' }))
    await screen.findByRole('table', { name: '거래 내역' })

    await user.selectOptions(screen.getByLabelText('종목'), '900002.XKRX')
    await user.selectOptions(screen.getByLabelText('상태'), 'CLOSED')
    await user.type(screen.getByLabelText('시작일'), '2024-01-01')
    await user.type(screen.getByLabelText('종료일'), '2024-06-30')
    await user.click(screen.getByRole('button', { name: '조건 적용' }))

    await vi.waitFor(() => expect(apiMocks.fetchResultTrades).toHaveBeenCalledTimes(2))
    expect(apiMocks.fetchResultTrades.mock.calls[1][1]).toEqual({
      page: 0,
      size: TRADES_PAGE_SIZE,
      instrumentId: '900002.XKRX',
      status: 'CLOSED',
      from: '2024-01-01',
      to: '2024-06-30',
    })
  })

  it('다른 페이지를 보다가 조건을 바꾸면 첫 페이지부터 받는다', async () => {
    apiMocks.fetchResultTrades.mockImplementation(async (_token: string, query: { page: number }) => ({
      ...resultTradePage,
      page: query.page,
      size: 2,
      totalElements: 5,
      totalPages: 3,
    }))
    const user = userEvent.setup()
    renderHistory()
    await user.click(screen.getByRole('button', { name: '거래 내역 보기' }))
    await screen.findByText('전체 5건 중 1~2번째')
    await user.click(screen.getByRole('button', { name: '다음' }))
    await screen.findByText('전체 5건 중 3~4번째')

    await user.selectOptions(screen.getByLabelText('상태'), 'OPEN')
    await user.click(screen.getByRole('button', { name: '조건 적용' }))

    await vi.waitFor(() => expect(apiMocks.fetchResultTrades).toHaveBeenCalledTimes(3))
    expect(apiMocks.fetchResultTrades.mock.calls[2][1]).toMatchObject({ page: 0, status: 'OPEN' })
  })

  it('페이지를 넘기는 동안 표와 누른 버튼을 남겨 초점을 잃지 않는다', async () => {
    let resolveNext: (value: unknown) => void = () => {}
    apiMocks.fetchResultTrades
      .mockResolvedValueOnce({ ...resultTradePage, size: 2, totalElements: 5, totalPages: 3 })
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveNext = resolve
          }),
      )
    const user = userEvent.setup()
    renderHistory()
    await user.click(screen.getByRole('button', { name: '거래 내역 보기' }))
    const next = await screen.findByRole('button', { name: '다음' })

    next.focus()
    await user.keyboard('{Enter}')

    expect(screen.getByRole('status').textContent).toBe('거래 내역을 불러오는 중입니다.')
    expect(screen.getByRole('table', { name: '거래 내역' })).not.toBeNull()
    expect(document.activeElement).toBe(next)
    // 받는 동안 한 번 더 눌러도 두 번 넘기지 않는다.
    await user.keyboard('{Enter}')
    expect(apiMocks.fetchResultTrades).toHaveBeenCalledTimes(2)

    await act(async () => {
      resolveNext({ ...resultTradePage, page: 1, size: 2, totalElements: 5, totalPages: 3 })
    })
    expect(await screen.findByText('전체 5건 중 3~4번째')).not.toBeNull()
  })

  it('부분 결과면 비어 있어도 거래가 없었다고 단정하지 않는다', async () => {
    apiMocks.fetchResultTrades.mockResolvedValue({
      ...resultTradePage,
      partial: true,
      content: [],
      totalElements: 0,
      totalPages: 0,
    })
    const user = userEvent.setup()
    renderHistory()

    await user.click(screen.getByRole('button', { name: '거래 내역 보기' }))

    expect(await screen.findByText('완료 범위 안에서는 이 조건에 해당하는 거래가 없습니다.')).not.toBeNull()
    expect(screen.queryByText('이 조건에 해당하는 거래가 없습니다.')).toBeNull()
    expect(screen.getByText(/완료 범위까지의 거래입니다/)).not.toBeNull()
  })

  it('시작일이 종료일보다 늦으면 요청하지 않고 알린다', async () => {
    const user = userEvent.setup()
    renderHistory()
    await user.click(screen.getByRole('button', { name: '거래 내역 보기' }))
    await screen.findByRole('table', { name: '거래 내역' })

    await user.type(screen.getByLabelText('시작일'), '2024-06-30')
    await user.type(screen.getByLabelText('종료일'), '2024-01-01')
    await user.click(screen.getByRole('button', { name: '조건 적용' }))

    expect(screen.getByRole('alert').textContent).toContain('시작일이 종료일보다 늦습니다')
    expect(apiMocks.fetchResultTrades).toHaveBeenCalledTimes(1)
  })

  it('다음 페이지를 누르면 페이지 번호를 질의로 넘긴다', async () => {
    apiMocks.fetchResultTrades.mockImplementation(async (_token: string, query: { page: number }) => ({
      ...resultTradePage,
      page: query.page,
      size: 2,
      totalElements: 5,
      totalPages: 3,
    }))
    const user = userEvent.setup()
    renderHistory()
    await user.click(screen.getByRole('button', { name: '거래 내역 보기' }))
    expect(await screen.findByText('전체 5건 중 1~2번째')).not.toBeNull()

    await user.click(screen.getByRole('button', { name: '다음' }))

    expect(await screen.findByText('전체 5건 중 3~4번째')).not.toBeNull()
    expect(apiMocks.fetchResultTrades.mock.calls[1][1]).toMatchObject({ page: 1 })
    expect((screen.getByRole('button', { name: '이전' }) as HTMLButtonElement).disabled).toBe(false)
  })

  it('받지 못하면 빈 표 대신 사유와 다시 불러오기를 둔다', async () => {
    const { ResultError } = await import('@/api/result')
    apiMocks.fetchResultTrades.mockRejectedValueOnce(new ResultError('거래 내역 실패'))
    const user = userEvent.setup()
    renderHistory()

    await user.click(screen.getByRole('button', { name: '거래 내역 보기' }))

    expect(await screen.findByText('거래 내역 실패')).not.toBeNull()
    expect(screen.queryByRole('table')).toBeNull()
    await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
    // 누른 버튼이 사라져도 초점이 문서 처음으로 밀려나지 않는다(FR-022).
    expect(document.activeElement).not.toBe(document.body)
    expect(await screen.findByRole('table', { name: '거래 내역' })).not.toBeNull()
  })
})

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  resultDetailCompleted,
  resultPositions,
  resultSectorCatalog,
  resultToken,
  resultTradePage,
} from '@/test/resultFixtures'
import { TRADES_PAGE_SIZE } from './constants'
import { LossWindowView } from './LossWindowView'

/** 005 T071(손실이 커진 때 보기). FR-010. 구간이 없을 때의 비활성은 `ResultPage.test.tsx`가 본다. */

const apiMocks = vi.hoisted(() => ({
  fetchResultTrades: vi.fn(),
  fetchResultPositions: vi.fn(),
}))
const sectorMocks = vi.hoisted(() => ({ fetchSectorCatalog: vi.fn() }))

vi.mock('@/api/result', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/result')>()
  return { ...actual, ...apiMocks }
})
vi.mock('@/api/sectors', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/sectors')>()
  return { ...actual, ...sectorMocks }
})

const lossWindow = { peakDate: '2025-02-26', troughDate: '2025-03-24' }

function renderView() {
  const onClose = vi.fn()
  render(
    <LossWindowView
      id="loss"
      token={resultToken}
      lossWindow={lossWindow}
      items={resultDetailCompleted.config.items}
      onClose={onClose}
    />,
  )
  return { onClose, view: screen.getByRole('region', { name: /손실이 커진 때/ }) }
}

describe('LossWindowView', () => {
  beforeEach(() => {
    for (const mock of [...Object.values(apiMocks), ...Object.values(sectorMocks)]) mock.mockReset()
    apiMocks.fetchResultTrades.mockResolvedValue(resultTradePage)
    apiMocks.fetchResultPositions.mockResolvedValue(resultPositions)
    sectorMocks.fetchSectorCatalog.mockResolvedValue({ status: 'loaded', catalog: resultSectorCatalog })
  })

  it('고점~저점 구간의 거래를 기간 질의로, 보유를 저점일로 부른다', async () => {
    const { view } = renderView()
    await within(view).findByRole('table', { name: '구간 거래' })

    expect(within(view).getByRole('heading', { name: '손실이 커진 때: 2025-02-26 ~ 2025-03-24' })).not.toBeNull()
    expect(apiMocks.fetchResultTrades).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchResultTrades.mock.calls[0][1]).toEqual({
      page: 0,
      size: TRADES_PAGE_SIZE,
      from: '2025-02-26',
      to: '2025-03-24',
    })
    expect(apiMocks.fetchResultPositions).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchResultPositions.mock.calls[0].slice(0, 2)).toEqual([resultToken, '2025-03-24'])
  })

  it('보유는 받은 스냅샷 날짜와 함께, 거래는 거래별 비용과 함께 보이고 구간 합계를 만들지 않는다', async () => {
    const { view } = renderView()

    const positions = await within(view).findByRole('table', { name: '2025-03-21 기준 보유 구성' })
    expect(within(positions).getByText('항구 · 합성 항구 ETF')).not.toBeNull()
    expect(within(positions).getByText('48.35%')).not.toBeNull()

    const trades = await within(view).findByRole('table', { name: '구간 거래' })
    expect(within(trades).getByRole('columnheader', { name: '수수료' })).not.toBeNull()
    expect(within(trades).getByRole('columnheader', { name: '슬리피지 비용' })).not.toBeNull()
    expect(within(view).queryByText(/합계/)).toBeNull()
  })

  it('열자마자 제목으로 초점이 가고, Esc와 닫기 버튼이 닫기를 부른다', async () => {
    const user = userEvent.setup()
    const { view, onClose } = renderView()
    await within(view).findByRole('table', { name: '구간 거래' })

    expect(document.activeElement).toBe(within(view).getByRole('heading', { level: 2 }))
    await user.keyboard('{Escape}')
    await user.click(within(view).getByRole('button', { name: '닫기' }))

    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('부분 결과의 보유는 완료 범위까지의 기록이라고 적는다', async () => {
    apiMocks.fetchResultPositions.mockResolvedValue({ ...resultPositions, partial: true })
    const { view } = renderView()

    expect(await within(view).findByText(/완료 범위까지의 기록입니다/)).not.toBeNull()
  })

  it('보유를 받지 못하면 사유와 다시 불러오기를 두고 빈 표를 그리지 않는다', async () => {
    const { ResultError } = await import('@/api/result')
    apiMocks.fetchResultPositions.mockRejectedValueOnce(new ResultError('보유 구성 실패'))
    const { view } = renderView()

    expect(await within(view).findByText('보유 구성 실패')).not.toBeNull()
    expect(within(view).queryByRole('table', { name: /보유 구성/ })).toBeNull()
  })
})

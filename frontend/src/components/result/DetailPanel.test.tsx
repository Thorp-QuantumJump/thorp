import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useId } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Button } from '@/components/ui/button'
import { useDisclosure } from '@/hooks/useDisclosure'
import {
  resultDetailCompleted,
  resultExceptions,
  resultSectorCatalog,
  resultToken,
  resultTradePage,
  resultWeights,
} from '@/test/resultFixtures'
import type { PublicResultDetail, ResultWeights } from '@/types/result'
import { CALCULATION_LIMITED, DetailPanel, type DetailCompareTarget, NOT_PROVIDED } from './DetailPanel'

/** 005 T070. FR-007~FR-009·FR-022, SC-001·SC-006. */

const apiMocks = vi.hoisted(() => ({
  fetchResultWeights: vi.fn(),
  fetchResultExceptions: vi.fn(),
  fetchResultTrades: vi.fn(),
  fetchResultPositions: vi.fn(),
}))
const sectorMocks = vi.hoisted(() => ({
  fetchSectorCatalog: vi.fn(),
  fetchInstrumentProfile: vi.fn(),
}))

vi.mock('@/api/result', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/result')>()
  return { ...actual, ...apiMocks }
})
vi.mock('@/api/sectors', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/sectors')>()
  return { ...actual, ...sectorMocks }
})

const targetToken = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'

function renderPanel(detail: PublicResultDetail = resultDetailCompleted, compare?: DetailCompareTarget) {
  const onClose = vi.fn()
  render(<DetailPanel id="detail" token={resultToken} detail={detail} compare={compare} onClose={onClose} />)
  return { onClose, panel: screen.getByRole('region', { name: '자세히 보기' }) }
}

/** 페이지와 같은 여닫기 방식으로 패널을 붙인 시험대. 닫은 뒤 초점이 어디로 가는지 본다. */
function Harness() {
  const panelId = useId()
  const view = useDisclosure(panelId)
  return (
    <>
      <Button type="button" variant="outline" {...view.toggleProps}>
        자세히 보기
      </Button>
      {view.open ? (
        <DetailPanel id={panelId} token={resultToken} detail={resultDetailCompleted} onClose={view.close} />
      ) : null}
    </>
  )
}

describe('DetailPanel', () => {
  beforeEach(() => {
    for (const mock of [...Object.values(apiMocks), ...Object.values(sectorMocks)]) mock.mockReset()
    apiMocks.fetchResultWeights.mockResolvedValue(resultWeights)
    apiMocks.fetchResultExceptions.mockResolvedValue(resultExceptions)
    apiMocks.fetchResultTrades.mockResolvedValue(resultTradePage)
    sectorMocks.fetchSectorCatalog.mockResolvedValue({ status: 'loaded', catalog: resultSectorCatalog })
  })

  it('열 때 비중·예외 기록과 섹터 목록을 한 번씩만 받고 거래 내역은 받지 않는다', async () => {
    renderPanel()
    await screen.findByText('HALT_DEFERRED')

    expect(apiMocks.fetchResultWeights).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchResultWeights.mock.calls[0][0]).toBe(resultToken)
    expect(apiMocks.fetchResultExceptions).toHaveBeenCalledTimes(1)
    expect(sectorMocks.fetchSectorCatalog).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchResultTrades).not.toHaveBeenCalled()
    expect(sectorMocks.fetchInstrumentProfile).not.toHaveBeenCalled()
  })

  it('FR-007 항목 열 가지가 모두 제목으로 있다', async () => {
    const { panel } = renderPanel()
    await screen.findByText('HALT_DEFERRED')

    const headings = within(panel)
      .getAllByRole('heading')
      .map((heading) => heading.textContent ?? '')
      .join('\n')
    for (const item of [
      '회복 기간',
      '회전율',
      '상관관계',
      '위험 기여',
      '구성종목 중복',
      '전체 거래 내역',
      '비용 가정',
      '비교 규칙',
      '대표 ETF 구성',
      '출처',
    ]) {
      expect(headings, item).toContain(item)
    }
  })

  it('004가 아직 내보내지 않는 세 항목은 제공되지 않음이며 추정 기간·산정법 자리가 있다', async () => {
    const { panel } = renderPanel()
    await screen.findByText('HALT_DEFERRED')

    for (const title of ['상관관계', '위험 기여', '손실 기여']) {
      const section = within(panel).getByRole('heading', { name: title }).closest('section') as HTMLElement
      expect(within(section).getAllByText(NOT_PROVIDED).length, title).toBeGreaterThan(0)
    }
    const risk = within(panel).getByRole('heading', { name: '위험 기여' }).closest('section') as HTMLElement
    expect(within(risk).getByText('추정 기간')).not.toBeNull()
    expect(within(risk).getByText('공분산 산정법')).not.toBeNull()
  })

  it('회복 기간·회전율을 004가 준 값으로 보인다', async () => {
    const { panel } = renderPanel()
    await screen.findByText('HALT_DEFERRED')

    expect(within(panel).getByText('9거래일')).not.toBeNull()
    expect(within(panel).getByText('연 122.00%')).not.toBeNull()
  })

  it('분석 지표가 없으면 계산 제한으로 보이고 0으로 채우지 않는다', async () => {
    const withoutAnalytics: PublicResultDetail = {
      ...resultDetailCompleted,
      summary: resultDetailCompleted.summary && { ...resultDetailCompleted.summary, analytics: null },
    }
    const { panel } = renderPanel(withoutAnalytics)
    await screen.findByText('HALT_DEFERRED')

    const recovery = within(panel).getByRole('heading', { name: '회복 기간' }).closest('section') as HTMLElement
    const turnover = within(panel).getByRole('heading', { name: '회전율' }).closest('section') as HTMLElement
    for (const section of [recovery, turnover]) {
      expect(within(section).getByText(new RegExp(CALCULATION_LIMITED))).not.toBeNull()
      expect(within(section).queryByText(/^0/)).toBeNull()
    }
  })

  it('회복 기간의 null은 계산 제한이 아니라 끝까지 회복하지 못했다는 뜻이다', async () => {
    const unrecovered: PublicResultDetail = {
      ...resultDetailCompleted,
      summary: resultDetailCompleted.summary && {
        ...resultDetailCompleted.summary,
        analytics: { recoveryDays: null, turnover: '1.219991' },
      },
    }
    const { panel } = renderPanel(unrecovered)
    await screen.findByText('HALT_DEFERRED')

    const recovery = within(panel).getByRole('heading', { name: '회복 기간' }).closest('section') as HTMLElement
    expect(within(recovery).getByText(/이전 고점을 되찾지 못했습니다/)).not.toBeNull()
    expect(within(recovery).queryByText(new RegExp(CALCULATION_LIMITED))).toBeNull()
  })

  it('비중 출처와 마지막 리밸런싱의 목표·실제 비중, 예외 기록의 종류·규칙을 그대로 보인다', async () => {
    const { panel } = renderPanel()
    await screen.findByText('HALT_DEFERRED')

    expect(within(panel).getByText('비중은 선택한 규칙의 계산 결과이며 입력한 비중과 다릅니다.')).not.toBeNull()
    expect(within(panel).getByText(/마지막 리밸런싱\(2024-12-02\) 기준/)).not.toBeNull()
    expect(within(panel).getByText('45.00%')).not.toBeNull()
    expect(within(panel).getByText('44.73%')).not.toBeNull()
    expect(within(panel).getByText('DEFER_TO_NEXT_TRADABLE')).not.toBeNull()
    // 섹터 목록의 이름을 붙인다.
    expect(within(panel).getAllByText('등대 · 합성 등대 ETF').length).toBeGreaterThan(0)
  })

  it('대표 상품 정보가 패널 안에 섹터마다 접혀 있다', async () => {
    const { panel } = renderPanel()

    const toggle = await within(panel).findByRole('button', { name: '항구 · 합성 항구 ETF' })
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
  })

  it('비중·예외 기록을 받지 못하면 빈 표 대신 사유와 다시 불러오기를 둔다', async () => {
    const { ResultError } = await import('@/api/result')
    apiMocks.fetchResultWeights.mockRejectedValueOnce(new ResultError('비중 기록 실패'))
    const user = userEvent.setup()
    const { panel } = renderPanel()

    expect(await within(panel).findByText('비중 기록 실패')).not.toBeNull()
    await user.click(within(panel).getAllByRole('button', { name: '다시 불러오기' })[0])

    expect(apiMocks.fetchResultWeights).toHaveBeenCalledTimes(2)
    expect(await within(panel).findByText('44.73%')).not.toBeNull()
  })

  it('닫기 버튼이 닫기를 부른다', async () => {
    const user = userEvent.setup()
    const { onClose } = renderPanel()
    await user.click(screen.getByRole('button', { name: '닫기' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('키보드만으로 열고 Esc로 닫으면 초점이 버튼으로 돌아온다', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    const toggle = screen.getByRole('button', { name: '자세히 보기' })

    toggle.focus()
    await user.keyboard('{Enter}')
    const panel = await screen.findByRole('region', { name: '자세히 보기' })
    // 열면 패널 제목으로 초점이 간다.
    expect(document.activeElement).toBe(within(panel).getByRole('heading', { name: '자세히 보기' }))

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('region', { name: '자세히 보기' })).toBeNull()
    expect(document.activeElement).toBe(toggle)
  })

  describe('보유 섹터 차이 (FR-008, T067)', () => {
    const targetItems = [
      { sectorId: 'HARBOR', instrumentId: '900001.XKRX' },
      { sectorId: 'PIER', instrumentId: '900004.XKRX' },
    ]
    const targetWeights: ResultWeights = {
      partial: false,
      weightsSource: 'COMPUTED',
      points: [
        {
          date: '2024-12-02',
          target: { '900001.XKRX': '0.600000', '900004.XKRX': '0.400000' },
          actual: { '900001.XKRX': '0.600000', '900004.XKRX': '0.400000' },
          weightError: '0.000000',
        },
      ],
    }

    it('비교 대상과 실제 보유 섹터가 다르면 선정 효과와 배분 효과를 두 결과의 값으로 나란히 보인다', async () => {
      apiMocks.fetchResultWeights.mockImplementation(async (token: string) =>
        token === targetToken ? targetWeights : resultWeights,
      )
      const { panel } = renderPanel(resultDetailCompleted, {
        token: targetToken,
        name: '썰물 맞추기',
        items: targetItems,
      })

      const section = (await within(panel).findByRole('heading', { name: '보유 섹터 차이' })).closest(
        'section',
      ) as HTMLElement
      expect(apiMocks.fetchResultWeights.mock.calls.map((call) => call[0]).sort()).toEqual(
        [resultToken, targetToken].sort(),
      )
      expect(within(section).getByText('등대, 조선소')).not.toBeNull()
      // 목록에 없는 섹터는 코드 그대로 둔다.
      expect(within(section).getByText('PIER')).not.toBeNull()
      const row = within(section).getByRole('row', { name: /항구/ })
      expect(within(row).getByText('44.73%')).not.toBeNull()
      expect(within(row).getByText('60.00%')).not.toBeNull()
    })

    it('보유 섹터가 같으면 그리지 않는다', async () => {
      apiMocks.fetchResultWeights.mockResolvedValue(resultWeights)
      const { panel } = renderPanel(resultDetailCompleted, {
        token: targetToken,
        name: '썰물 맞추기',
        items: resultDetailCompleted.config.items,
      })
      await screen.findByText('HALT_DEFERRED')
      await vi.waitFor(() => expect(apiMocks.fetchResultWeights).toHaveBeenCalledTimes(2))

      expect(within(panel).queryByRole('heading', { name: '보유 섹터 차이' })).toBeNull()
    })

    it('비교 대상이 없으면 비교 대상의 비중을 부르지 않는다', async () => {
      renderPanel()
      await screen.findByText('HALT_DEFERRED')

      expect(apiMocks.fetchResultWeights).toHaveBeenCalledTimes(1)
    })
  })
})

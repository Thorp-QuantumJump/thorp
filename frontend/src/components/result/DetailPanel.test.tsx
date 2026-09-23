import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  resultDetailCompleted,
  resultExceptions,
  resultSectorCatalog,
  resultToken,
  resultTradePage,
  resultWeights,
} from '@/test/resultFixtures'
import type { PublicResultDetail, ResultWeights } from '@/types/result'
import { CALCULATION_LIMITED, type DetailCompareTarget } from './DetailPanel'
import { ResultDetailTabs } from './ResultDetailTabs'

/** 005 T070·T098. FR-007~FR-009, SC-001·SC-006. 결과 분석·설정·근거 두 탭을 `ResultDetailTabs`로 붙여 본다. */

const apiMocks = vi.hoisted(() => ({
  fetchResultWeights: vi.fn(),
  fetchResultExceptions: vi.fn(),
  fetchResultTrades: vi.fn(),
  fetchResultPositions: vi.fn(),
  fetchResultIndexComparison: vi.fn(),
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

/** 요약 탭이 기본이라 결과 분석 탭을 골라 둔 상태로 돌려준다. */
async function renderPanel(detail: PublicResultDetail = resultDetailCompleted, compare?: DetailCompareTarget) {
  render(
    <ResultDetailTabs
      token={resultToken}
      detail={detail}
      summary={<p>요약 자리</p>}
      lossWindow={null}
      compare={compare}
    />,
  )
  const tablist = screen.getByRole('tablist', { name: '결과 보기' })
  await openTab('결과 분석')
  return { panel: tablist.parentElement as HTMLElement }
}

async function openTab(name: string) {
  await userEvent.setup().click(screen.getByRole('tab', { name }))
}

/** '설정·근거' 탭을 고른다. 그때 `/weights`를 받는다. */
const openSettings = () => openTab('설정·근거')

/** 숨긴 탭 패널의 제목까지 포함해 찾는다. 두 탭에 나뉜 항목을 한 번에 셀 때 쓴다. */
function sectionOf(panel: HTMLElement, title: string | RegExp) {
  return within(panel).getByRole('heading', { name: title, hidden: true }).closest('section') as HTMLElement
}

describe('DetailPanel(결과 분석·설정·근거 탭)', () => {
  beforeEach(() => {
    for (const mock of [...Object.values(apiMocks), ...Object.values(sectorMocks)]) mock.mockReset()
    apiMocks.fetchResultWeights.mockResolvedValue(resultWeights)
    apiMocks.fetchResultExceptions.mockResolvedValue(resultExceptions)
    apiMocks.fetchResultTrades.mockResolvedValue(resultTradePage)
    sectorMocks.fetchSectorCatalog.mockResolvedValue({ status: 'loaded', catalog: resultSectorCatalog })
  })

  it('요약 탭이 기본이고 상세 자료는 어느 탭도 고르기 전에는 받지 않는다', async () => {
    render(
      <ResultDetailTabs token={resultToken} detail={resultDetailCompleted} summary={<p>요약 자리</p>} lossWindow={null} />,
    )

    expect(screen.getByRole('tab', { name: '요약' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByRole('tabpanel').textContent).toContain('요약 자리')
    expect(apiMocks.fetchResultExceptions).not.toHaveBeenCalled()
    expect(apiMocks.fetchResultWeights).not.toHaveBeenCalled()
    expect(apiMocks.fetchResultTrades).not.toHaveBeenCalled()
    // 섹터 목록은 세 상세 탭이 같이 쓰므로 묶음이 한 번 받아 둔다.
    expect(sectorMocks.fetchSectorCatalog).toHaveBeenCalledTimes(1)
  })

  it('결과 분석 탭은 예외 기록을 받고, 비중은 설정·근거를, 거래 내역은 전체 거래 내역을 고를 때 받는다', async () => {
    await renderPanel()
    await screen.findByText('HALT_DEFERRED')

    expect(screen.getByRole('tab', { name: '결과 분석' }).getAttribute('aria-selected')).toBe('true')
    expect(apiMocks.fetchResultExceptions).toHaveBeenCalledTimes(1)
    expect(sectorMocks.fetchSectorCatalog).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchResultWeights).not.toHaveBeenCalled()
    expect(apiMocks.fetchResultTrades).not.toHaveBeenCalled()
    expect(sectorMocks.fetchInstrumentProfile).not.toHaveBeenCalled()
    expect(apiMocks.fetchResultIndexComparison).not.toHaveBeenCalled()

    await openSettings()
    await screen.findByText('44.73%')
    expect(apiMocks.fetchResultWeights).toHaveBeenCalledTimes(1)
    expect(apiMocks.fetchResultWeights.mock.calls[0][0]).toBe(resultToken)
    expect(apiMocks.fetchResultTrades).not.toHaveBeenCalled()

    await openTab('전체 거래 내역')
    await screen.findByRole('table', { name: '거래 내역' })
    expect(apiMocks.fetchResultTrades).toHaveBeenCalledTimes(1)
    // 섹터 목록은 부모가 한 번 받아 세 탭이 같이 쓴다.
    expect(sectorMocks.fetchSectorCatalog).toHaveBeenCalledTimes(1)
  })

  it('FR-007 항목이 세 탭에 나뉘어 모두 제목으로 있다', async () => {
    const { panel } = await renderPanel()
    await screen.findByText('HALT_DEFERRED')
    await openSettings()
    await openTab('전체 거래 내역')

    const headings = within(panel)
      .getAllByRole('heading', { hidden: true })
      .map((heading) => heading.textContent ?? '')
      .join('\n')
    for (const item of [
      '회복 기간',
      '회전율',
      '상관관계',
      '위험 기여',
      '손실 기여',
      '계산 제한 항목',
      '전체 거래 내역',
      '비교 규칙',
      '비용 가정',
      '목표·실제 비중',
      '구성종목 중복',
      '대표 ETF 구성',
      '출처',
    ]) {
      expect(headings, item).toContain(item)
    }
    // 결과에서 나온 값은 결과 분석에, 넣은 조건은 설정·근거에, 거래는 제 탭에 있다.
    const analysis = screen.getAllByRole('tabpanel', { hidden: true })[1]
    expect(within(analysis).queryByRole('heading', { name: '비용 가정', hidden: true })).toBeNull()
    expect(within(analysis).queryByRole('heading', { name: '전체 거래 내역', hidden: true })).toBeNull()
    expect(within(analysis).getByRole('heading', { name: '상관관계', hidden: true })).not.toBeNull()
  })

  it('상관관계·위험 기여·손실 기여를 004가 준 값으로 보이고 추정 기간·산정법이 있다', async () => {
    const { panel } = await renderPanel()
    await screen.findByText('HALT_DEFERRED')

    const correlation = sectionOf(panel, '상관관계')
    expect(within(correlation).getAllByText('-0.19')).toHaveLength(2)
    expect(within(correlation).getAllByText('1.00')).toHaveLength(3)

    const risk = sectionOf(panel, '위험 기여')
    expect(within(risk).getByText('7.95%')).not.toBeNull()
    expect(within(risk).getByText('추정 기간')).not.toBeNull()
    expect(within(risk).getByText('2023-07-03 ~ 2024-12-31')).not.toBeNull()
    expect(within(risk).getByText('공분산 산정법')).not.toBeNull()
    expect(within(risk).getByText('sample-covariance-daily')).not.toBeNull()

    const loss = sectionOf(panel, '손실 기여')
    expect(within(loss).getByText('125.00%')).not.toBeNull()
    expect(within(loss).getByText('-25.00%')).not.toBeNull()
  })

  it('세 항목 중 004가 계산하지 못한 것은 계산 제한으로 보이고 0으로 채우지 않는다', async () => {
    const summary = resultDetailCompleted.summary as NonNullable<PublicResultDetail['summary']>
    const analytics = summary.analytics as NonNullable<typeof summary.analytics>
    const limited: PublicResultDetail = {
      ...resultDetailCompleted,
      summary: {
        ...summary,
        analytics: { ...analytics, correlationMatrix: null, riskContribution: null, lossContribution: null },
      },
    }
    const { panel } = await renderPanel(limited)
    await screen.findByText('HALT_DEFERRED')

    for (const title of ['상관관계', '위험 기여', '손실 기여']) {
      const section = sectionOf(panel, title)
      expect(within(section).getByText(new RegExp(`^${CALCULATION_LIMITED}`)), title).not.toBeNull()
      expect(within(section).queryByText('0.00%'), title).toBeNull()
      expect(within(section).queryByRole('table'), title).toBeNull()
    }
  })

  it('회복 기간·회전율을 004가 준 값으로 보인다', async () => {
    const { panel } = await renderPanel()
    await screen.findByText('HALT_DEFERRED')

    expect(within(panel).getByText('9거래일')).not.toBeNull()
    expect(within(panel).getByText('연 122.00%')).not.toBeNull()
  })

  it('분석 지표가 없으면 계산 제한으로 보이고 0으로 채우지 않는다', async () => {
    const withoutAnalytics: PublicResultDetail = {
      ...resultDetailCompleted,
      summary: resultDetailCompleted.summary && { ...resultDetailCompleted.summary, analytics: null },
    }
    const { panel } = await renderPanel(withoutAnalytics)
    await screen.findByText('HALT_DEFERRED')

    const recovery = sectionOf(panel, '회복 기간')
    const turnover = sectionOf(panel, '회전율')
    for (const section of [recovery, turnover]) {
      expect(within(section).getByText(new RegExp(CALCULATION_LIMITED))).not.toBeNull()
    }
    // 값 자리(`거래일`·`연 …%`)가 어떤 숫자로도 채워지지 않는다.
    expect(within(recovery).queryByText(/거래일$/)).toBeNull()
    expect(within(turnover).queryByText(/^연 /)).toBeNull()
  })

  it('요약 자체가 없는 결과는 계산 제한이 아니라 지표가 계산되지 않았다고 보인다', async () => {
    const withoutSummary: PublicResultDetail = {
      ...resultDetailCompleted,
      status: 'CANCELLED',
      partial: true,
      summary: null,
      summaryUnavailable: 'CANCELLED_WHILE_RUN_CONTINUED',
    }
    const { panel } = await renderPanel(withoutSummary)
    await screen.findByText('HALT_DEFERRED')
    await openSettings()

    for (const title of ['회복 기간', '회전율', '비용 가정']) {
      const section = sectionOf(panel, title)
      expect(within(section).getByText('취소 시점까지의 지표는 계산되지 않았습니다.'), title).not.toBeNull()
      expect(within(section).queryByText(new RegExp(CALCULATION_LIMITED)), title).toBeNull()
    }
  })

  it('마지막 리밸런싱에 담은 종목이 없으면 머리글만 있는 표 대신 그 사실을 적는다', async () => {
    apiMocks.fetchResultWeights.mockResolvedValue({
      ...resultWeights,
      points: [...resultWeights.points, { date: '2024-12-30', target: {}, actual: {}, weightError: '0.000000' }],
    })
    const { panel } = await renderPanel()
    await openSettings()

    expect(await within(panel).findByText(/마지막 리밸런싱\(2024-12-30\)에서는 담은 종목이 없습니다/)).not.toBeNull()
    expect(within(sectionOf(panel, '목표·실제 비중')).queryByRole('table')).toBeNull()
  })

  it.each([
    [{ status: 'emptyCatalog', catalog: { ...resultSectorCatalog, sectors: [] } }],
    [{ status: 'mapNotReady', message: '준비 중' }],
  ])('섹터 목록이 아직 준비 중(%o)이면 섹터가 빠졌다고 하지 않고 다시 불러오기를 준다', async (result) => {
    sectorMocks.fetchSectorCatalog.mockResolvedValueOnce(result)
    const user = userEvent.setup()
    const { panel } = await renderPanel()
    await openSettings()

    const profile = sectionOf(panel, /대표 ETF 구성/)
    expect(await within(profile).findByText(/섹터 목록이 아직 준비 중이라/)).not.toBeNull()
    expect(within(profile).queryByText(/지금 섹터 목록에 없는 섹터라/)).toBeNull()

    await user.click(within(profile).getByRole('button', { name: '다시 불러오기' }))
    expect(await within(profile).findByRole('button', { name: '항구 · 합성 항구 ETF' })).not.toBeNull()
    expect(sectorMocks.fetchSectorCatalog).toHaveBeenCalledTimes(2)
  })

  it('회복 기간의 null은 계산 제한이 아니라 끝까지 회복하지 못했다는 뜻이다', async () => {
    const unrecovered: PublicResultDetail = {
      ...resultDetailCompleted,
      summary: resultDetailCompleted.summary && {
        ...resultDetailCompleted.summary,
        analytics: { ...resultDetailCompleted.summary.analytics!, recoveryDays: null },
      },
    }
    const { panel } = await renderPanel(unrecovered)
    await screen.findByText('HALT_DEFERRED')

    const recovery = sectionOf(panel, '회복 기간')
    expect(within(recovery).getByText(/이전 고점을 되찾지 못했습니다/)).not.toBeNull()
    expect(within(recovery).queryByText(new RegExp(CALCULATION_LIMITED))).toBeNull()
  })

  it('예외 기록의 종류·규칙은 결과 분석에, 비중 출처와 마지막 리밸런싱의 목표·실제 비중은 설정·근거에 그대로 보인다', async () => {
    const { panel } = await renderPanel()
    await screen.findByText('HALT_DEFERRED')

    expect(within(panel).getByText('DEFER_TO_NEXT_TRADABLE')).not.toBeNull()
    // 섹터 목록의 이름을 붙인다.
    expect(within(panel).getAllByText('등대 · 합성 등대 ETF').length).toBeGreaterThan(0)

    await openSettings()
    expect(await within(panel).findByText('비중은 선택한 규칙의 계산 결과이며 입력한 비중과 다릅니다.')).not.toBeNull()
    expect(within(panel).getByText(/마지막 리밸런싱\(2024-12-02\) 기준/)).not.toBeNull()
    expect(within(panel).getByText('45.00%')).not.toBeNull()
    expect(within(panel).getByText('44.73%')).not.toBeNull()
  })

  it('대표 상품 정보가 설정·근거 탭 안에 섹터마다 접혀 있다', async () => {
    const { panel } = await renderPanel()
    await openSettings()

    const toggle = await within(panel).findByRole('button', { name: '항구 · 합성 항구 ETF' })
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
  })

  it('비중·예외 기록을 받지 못하면 빈 표 대신 사유와 다시 불러오기를 둔다', async () => {
    const { ResultError } = await import('@/api/result')
    apiMocks.fetchResultWeights.mockRejectedValueOnce(new ResultError('비중 기록 실패'))
    const user = userEvent.setup()
    const { panel } = await renderPanel()
    await openSettings()

    expect(await within(panel).findByText('비중 기록 실패')).not.toBeNull()
    await user.click(within(panel).getAllByRole('button', { name: '다시 불러오기' })[0])

    expect(apiMocks.fetchResultWeights).toHaveBeenCalledTimes(2)
    expect(await within(panel).findByText('44.73%')).not.toBeNull()
  })

  it('전체 거래 내역 탭은 고르면 바로 목록을 보이고, 조건 칸에서 누른 Esc는 채우던 값을 지우지 않는다', async () => {
    const user = userEvent.setup()
    const { panel } = await renderPanel()
    await openTab('전체 거래 내역')
    await within(panel).findByRole('table', { name: '거래 내역' })
    expect(within(panel).queryByRole('button', { name: /거래 내역 (보기|접기)/ })).toBeNull()

    const from = within(panel).getByLabelText('시작일')
    await user.type(from, '2024-01-01')
    from.focus()
    await user.keyboard('{Escape}')

    expect((from as HTMLInputElement).value).toBe('2024-01-01')
    expect(screen.getByRole('tab', { name: '전체 거래 내역' }).getAttribute('aria-selected')).toBe('true')
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
      const { panel } = await renderPanel(resultDetailCompleted, {
        token: targetToken,
        name: '썰물 맞추기',
        items: targetItems,
      })
      await openSettings()

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
      const { panel } = await renderPanel(resultDetailCompleted, {
        token: targetToken,
        name: '썰물 맞추기',
        items: resultDetailCompleted.config.items,
      })
      await openSettings()
      await vi.waitFor(() => expect(apiMocks.fetchResultWeights).toHaveBeenCalledTimes(2))

      expect(within(panel).queryByRole('heading', { name: '보유 섹터 차이' })).toBeNull()
    })

    it('비교 대상이 없으면 비교 대상의 비중을 부르지 않는다', async () => {
      await renderPanel()
      await openSettings()
      await screen.findByText('44.73%')

      expect(apiMocks.fetchResultWeights).toHaveBeenCalledTimes(1)
    })
  })
})

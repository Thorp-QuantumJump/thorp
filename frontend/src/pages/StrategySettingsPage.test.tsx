import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SectorCatalogResult } from '@/api/sectors'
import { AuthContext, type AuthContextValue } from '@/auth/auth-context'
import {
  type AnalysisSupport,
  type AnalysisVersions,
  type WarmupJudgement,
} from '@/components/strategy/analysisSupport'
import {
  SECTOR_SELECTION_PAYLOAD_VERSION,
  SECTOR_SELECTION_STORAGE_KEY,
} from '@/diagnosis/selectionStorage'
import { STRATEGY_SETTINGS_STORAGE_KEY } from '@/hooks/useStrategySettings'
import {
  registryFixture,
  selectableStrategy,
  unselectableStrategy,
} from '@/test/strategyRegistryFixtures'
import type { BacktestCreateRequest } from '@/types/backtestRequest'
import type { SectorSummary } from '@/types/sector'
import { StrategySettingsPage } from './StrategySettingsPage'

/**
 * 카드 선택 한 번과 '분석하기' 한 번으로 요청이 만들어지는지 고정한다(SC-001·SC-005·SC-008,
 * quickstart 4·9).
 *
 * <p><b>등록표 픽스처의 전략과 축은 등록표에 없는 이름이다.</b> 실제 이름을 쓰면 화면이 그 전략을
 * 알고 있어도 검사가 통과한다 — 지어낸 이름이라야 "응답만 보고 그린다"가 증명된다(FR-005).
 *
 * <p><b>분석 조건 버전과 평가 기간은 흉내를 넘긴다.</b> 둘 다 아직 없는 통로이며(①·②) 화면은 이를
 * props로 받는다. 기본값(연결되지 않은 상태)에서 '분석하기'가 정직하게 막히는 것도 함께 본다.
 */

const apiMocks = vi.hoisted(() => ({
  fetchSectorCatalog: vi.fn(),
  fetchStrategyRegistry: vi.fn(),
}))

vi.mock('@/api/sectors', async () => {
  const actual = await vi.importActual<typeof import('@/api/sectors')>('@/api/sectors')
  return { ...actual, ...apiMocks }
})

vi.mock('@/api/strategyRegistry', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/strategyRegistry')>('@/api/strategyRegistry')
  return { ...actual, ...apiMocks }
})

function sector(code: string, name: string): SectorSummary {
  return {
    code,
    name,
    classification: 'INDUSTRY',
    representativeEtf: {
      instrumentId: `${code}.XKRX`,
      name: `KODEX ${name}`,
      listedOn: '2018-09-12',
      benchmarkIndex: `KRX ${name}`,
    },
  }
}

const catalogSectors = [sector('ALPHA', '가섹터'), sector('BETA', '나섹터'), sector('GAMMA', '다섹터')]

const loadedCatalog: SectorCatalogResult = {
  status: 'loaded',
  catalog: { mapVersion: 'SEM-TEST-1', isSample: false, sectors: catalogSectors },
}

/** 002가 마친 선택을 그 계약 형식대로 보관한다. */
function storeSelection(codes: string[] = ['ALPHA', 'BETA', 'GAMMA']) {
  const share = Math.floor(1000 / codes.length)
  window.sessionStorage.setItem(
    SECTOR_SELECTION_STORAGE_KEY,
    JSON.stringify({
      version: SECTOR_SELECTION_PAYLOAD_VERSION,
      mapVersion: 'SEM-TEST-1',
      selected: codes.map((code, index) => ({
        code,
        weightTenths: index === 0 ? 1000 - share * (codes.length - 1) : share,
      })),
      weightsTouched: true,
    }),
  )
}

const versionsFixture: AnalysisVersions = {
  rulesVersion: 'RULES-TEST-1',
  costProfileVersion: 'PROVISIONAL-DEMO-1',
  costProfileProvisional: true,
}

const windowFixture = { start: '2020-01-02', end: '2024-12-30' }

/**
 * 화면이 바깥에서 받는 세 통로의 흉내.
 *
 * 보낸 요청은 `requests`에 그대로 쌓는다 — 모의 함수의 호출 기록에서 꺼내는 것보다 어느 검사가
 * 무엇을 견주는지 읽기 쉽고, 요청 본문의 타입도 그대로 남는다.
 */
function makeSupport(overrides: Partial<AnalysisSupport> = {}) {
  const requests: BacktestCreateRequest[] = []
  const createBacktest: AnalysisSupport['createBacktest'] = async (request) => {
    requests.push(request)
    return { backtestId: 'BT-20260916-000001', status: 'QUEUED' as const }
  }
  const judgeWarmup: AnalysisSupport['judgeWarmup'] = (): WarmupJudgement => ({
    verdict: { state: 'ok' },
    window: windowFixture,
  })
  const fetchAnalysisVersions: AnalysisSupport['fetchAnalysisVersions'] = async () => versionsFixture

  const support: AnalysisSupport = {
    fetchAnalysisVersions,
    judgeWarmup,
    createBacktest,
    ...overrides,
  }
  return { support, requests }
}

function renderPage(support?: AnalysisSupport) {
  const authValue: AuthContextValue = {
    status: 'anonymous',
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
  }

  return render(
    <MemoryRouter initialEntries={['/strategy']}>
      <AuthContext.Provider value={authValue}>
        <StrategySettingsPage support={support} />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

/** 합계가 100%에서 0.1%p 모자란 선택을 002 계약 형식대로 보관한다. 002는 이 상태도 정상으로 보관한다. */
function storeUnbalancedSelection() {
  window.sessionStorage.setItem(
    SECTOR_SELECTION_STORAGE_KEY,
    JSON.stringify({
      version: SECTOR_SELECTION_PAYLOAD_VERSION,
      mapVersion: 'SEM-TEST-1',
      selected: [
        { code: 'ALPHA', weightTenths: 333 },
        { code: 'BETA', weightTenths: 333 },
        { code: 'GAMMA', weightTenths: 333 },
      ],
      weightsTouched: true,
    }),
  )
}

/** 앞 단계로 이동하는 버튼이 실제로 그 경로에 닿는지 보려고 목적지 화면을 흉내 낸다. */
function renderPageWithSectorRoute(support: AnalysisSupport) {
  const authValue: AuthContextValue = {
    status: 'anonymous',
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
  }

  return render(
    <MemoryRouter initialEntries={['/strategy']}>
      <AuthContext.Provider value={authValue}>
        <Routes>
          <Route path="/strategy" element={<StrategySettingsPage support={support} />} />
          <Route path="/diagnosis/sectors" element={<p>섹터·비중 단계 화면</p>} />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

/**
 * 자동 비교로 넘어간 주소를 읽으려고 007 화면 자리에 지금 위치를 그대로 보이는 대역을 둔다.
 * 007 화면 자체를 띄우지 않는 까닭은 이 검사가 **무엇을 넘기는가**만 보기 때문이다 — 넘긴 질의
 * 문자열을 007이 어떻게 읽는지는 `ParamSearchPage.test.tsx`가 고정한다.
 */
function ParamSearchProbe() {
  const location = useLocation()
  return <p data-testid="param-search-location">{location.pathname + location.search}</p>
}

function renderPageWithParamSearchRoute(support: AnalysisSupport) {
  const authValue: AuthContextValue = {
    status: 'anonymous',
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
  }

  return render(
    <MemoryRouter initialEntries={['/strategy']}>
      <AuthContext.Provider value={authValue}>
        <Routes>
          <Route path="/strategy" element={<StrategySettingsPage support={support} />} />
          <Route path="/param-search" element={<ParamSearchProbe />} />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

/** 행동 버튼에 이어진 이유 문장. 이유가 없으면 빈 문자열이다. */
function reasonOf(button: HTMLElement): string {
  const reasonId = button.getAttribute('aria-describedby')
  return reasonId ? (document.getElementById(reasonId)?.textContent ?? '') : ''
}

/**
 * 부모를 다시 그릴 수 있는 호스트로 화면을 띄운다.
 *
 * `makeSupportFor`는 부모가 그릴 때마다 불리며 몇 번째로 다시 그렸는지(`generation`)를 받는다.
 * 통로를 렌더마다 새로 만드는 호출자를 흉내 내려는 것이다 — 이 화면의 상태 변화는 부모를 다시
 * 그리지 않으므로, 부모 쪽 사정으로 다시 그리는 경우는 이렇게 따로 만들어야 검사가 닿는다.
 */
function renderHost(makeSupportFor: (generation: number) => AnalysisSupport) {
  function Host() {
    const [generation, bump] = useState(0)
    const support = makeSupportFor(generation)
    return (
      <>
        <button type="button" onClick={() => bump((count) => count + 1)}>
          부모 다시 그리기
        </button>
        <StrategySettingsPage support={support} />
      </>
    )
  }

  const authValue: AuthContextValue = {
    status: 'anonymous',
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    recheck: vi.fn(),
  }

  return render(
    <MemoryRouter initialEntries={['/strategy']}>
      <AuthContext.Provider value={authValue}>
        <Host />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

/**
 * 카드와 행동 버튼의 접근 가능 이름은 이름·설명·사유를 이어 붙인 글이라 전체 일치로는 찾을 수 없다.
 * 정규식이 아니라 함수로 견주는 까닭은 전략 이름에 괄호 같은 정규식 기호가 들어 있을 수 있기
 * 때문이다(`똑같이 나누기(시험용)`).
 */
async function findCardNamed(name: string): Promise<HTMLElement> {
  return screen.findByRole('button', { name: (accessibleName) => accessibleName.includes(name) })
}

function actionButton(label: string): HTMLElement {
  return screen.getByRole('button', { name: (accessibleName) => accessibleName.includes(label) })
}

beforeEach(() => {
  apiMocks.fetchSectorCatalog.mockResolvedValue(loadedCatalog)
  apiMocks.fetchStrategyRegistry.mockResolvedValue(registryFixture)
  storeSelection()
})

afterEach(() => {
  vi.clearAllMocks()
  window.sessionStorage.clear()
})

describe('StrategySettingsPage', () => {
  it('카드 한 번과 분석하기 한 번으로 기본값 요청이 만들어진다', async () => {
    // SC-001, quickstart 4. 상세 설정을 한 번도 열지 않는다.
    const user = userEvent.setup()
    const { support, requests } = makeSupport()
    renderPage(support)

    await user.click(await findCardNamed(selectableStrategy.name))
    await waitFor(() => expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('false'))
    await user.click(actionButton('분석하기'))

    await waitFor(() => expect(requests).toHaveLength(1))

    const request = requests[0]
    expect(request.executionType).toBe('SINGLE')
    expect(request.strategy.strategyId).toBe(selectableStrategy.strategyId)
    expect(request.strategy.registryVersion).toBe(registryFixture.registryVersion)
    expect(request.strategy.rulesVersion).toBe(versionsFixture.rulesVersion)

    // 등록표의 기본값 그대로다. 사용자는 값을 하나도 입력하지 않았다.
    for (const axis of selectableStrategy.axes) {
      expect(request.strategy.parameters[axis.name]).toEqual(axis.defaultValue)
    }

    // 워밍업은 규칙으로 계산한 값이며 카드의 기본 설정 워밍업과 같다.
    expect(request.conditions.warmupTradingDays).toBe(selectableStrategy.warmupTradingDays)
    expect(request.conditions.start).toBe(windowFixture.start)
    expect(request.conditions.end).toBe(windowFixture.end)
    expect(request.conditions.scenario).toBe('FULL_PERIOD')
    // 금액은 등록표의 기본값을 decimal 문자열로 싣는다.
    expect(request.conditions.initialCash).toBe(
      registryFixture.analysisConditions.initialAmount.default.toFixed(2),
    )

    // 자동 배분 전략이므로 목표 비중을 싣지 않는다(계약 §2, targetWeightSource).
    expect(request.sectorAllocation.mappingVersion).toBe('SEM-TEST-1')
    expect(request.sectorAllocation.items).toHaveLength(3)
    for (const item of request.sectorAllocation.items) {
      expect(item).not.toHaveProperty('targetWeight')
    }

    expect(await screen.findByText(/BT-20260916-000001/)).toBeTruthy()
  })

  it('사용자가 정한 비중을 쓰는 전략에만 목표 비중을 싣는다', async () => {
    const user = userEvent.setup()
    const userWeighted = registryFixture.strategies.find(
      (strategy) => strategy.targetWeightSource === 'USER',
    )!
    // 그 전략만 고를 수 있게 둔 응답이다. 화면이 전략 ID로 분기하지 않는다는 것을 본다.
    apiMocks.fetchStrategyRegistry.mockResolvedValue({
      ...registryFixture,
      strategies: [{ ...userWeighted, selectable: true, unselectableReason: undefined }],
    })
    const { support, requests } = makeSupport()
    renderPage(support)

    await user.click(await findCardNamed(userWeighted.name))
    await waitFor(() => expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('false'))
    await user.click(actionButton('분석하기'))

    await waitFor(() => expect(requests).toHaveLength(1))
    const request = requests[0]
    for (const item of request.sectorAllocation.items) {
      expect(item.targetWeight).toMatch(/^\d+\.\d{4}$/)
    }
    const total = request.sectorAllocation.items.reduce(
      (sum, item) => sum + Number(item.targetWeight),
      0,
    )
    expect(total).toBeCloseTo(1, 4)
  })

  it('사용자 비중을 쓰는 전략은 비중 합계가 100%가 아니면 분석하기가 막히고 되돌아갈 길을 보인다', async () => {
    // T091. 002는 합계가 어긋난 상태도 정상으로 보관하고, 004는 목표 비중 합을 1로 요구한다.
    // 그 사이를 잇는 이 화면이 막지 않으면 서버가 400으로 거절하고 사용자는 무엇을 고칠지 모른다.
    storeUnbalancedSelection()
    const user = userEvent.setup()
    const userWeighted = registryFixture.strategies.find(
      (strategy) => strategy.targetWeightSource === 'USER',
    )!
    apiMocks.fetchStrategyRegistry.mockResolvedValue({
      ...registryFixture,
      strategies: [{ ...userWeighted, selectable: true, unselectableReason: undefined }],
    })
    const { support, requests } = makeSupport()
    renderPageWithSectorRoute(support)

    await user.click(await findCardNamed(userWeighted.name))

    const analyze = actionButton('분석하기')
    await waitFor(() => expect(analyze.getAttribute('aria-disabled')).toBe('true'))
    const reasonId = analyze.getAttribute('aria-describedby')
    const reason = document.getElementById(reasonId!)?.textContent ?? ''
    expect(reason).toContain('합계가 100%가 아닙니다')
    // 얼마나 어긋났는지 002 화면과 같은 형식으로 보인다.
    expect(reason).toContain('99.9%')

    await user.click(analyze)
    expect(requests).toHaveLength(0)

    // 고칠 자리가 다른 화면이라 이동 수단을 함께 둔다.
    await user.click(screen.getByRole('button', { name: '섹터·비중 단계로 이동' }))
    expect(await screen.findByText('섹터·비중 단계 화면')).toBeTruthy()
  })

  it('비중 합계가 어긋나도 자동 배분 전략은 막지 않는다', async () => {
    // T091. 자동 배분 전략은 이 비중을 읽지 않는다. 합계 때문에 막으면 필요 이상으로 넓게 막는다.
    storeUnbalancedSelection()
    const user = userEvent.setup()
    const { support, requests } = makeSupport()
    renderPage(support)

    // 픽스처의 이 전략은 등록표가 자동 배분(`COMPUTED`)이라고 말한다.
    expect(selectableStrategy.targetWeightSource).not.toBe('USER')
    await user.click(await findCardNamed(selectableStrategy.name))
    await waitFor(() => expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('false'))
    expect(screen.queryByRole('button', { name: '섹터·비중 단계로 이동' })).toBeNull()

    await user.click(actionButton('분석하기'))
    await waitFor(() => expect(requests).toHaveLength(1))
    for (const item of requests[0].sectorAllocation.items) {
      expect(item).not.toHaveProperty('targetWeight')
    }
  })

  it('요약 줄에 보인 시작 금액이 그대로 요청에 실린다', async () => {
    // T092, quickstart 4. 사용자가 보지 못한 금액으로 분석이 접수되지 않는다.
    const user = userEvent.setup()
    const { support, requests } = makeSupport()
    renderPage(support)

    await user.click(await findCardNamed(selectableStrategy.name))

    const amount = registryFixture.analysisConditions.initialAmount
    const summary = screen.getByLabelText('분석 조건 요약')
    expect(summary.textContent).toContain(
      `${amount.default.toLocaleString('ko-KR')}${amount.unit ?? ''}`,
    )

    await waitFor(() => expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('false'))
    await user.click(actionButton('분석하기'))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0].conditions.initialCash).toBe(amount.default.toFixed(2))
  })

  it('기본 화면의 입력은 전략 선택 하나다', async () => {
    // SC-005. 상세 설정과 기간·금액·조건은 접혀 있고 입력 요소를 내놓지 않는다.
    const user = userEvent.setup()
    const { support } = makeSupport()
    const { container } = renderPage(support)

    await user.click(await findCardNamed(selectableStrategy.name))

    expect(container.querySelectorAll('input, select, textarea')).toHaveLength(0)

    // 축 라벨은 응답에 실려 오지만 기본 화면에 보이지 않는다.
    for (const axis of selectableStrategy.axes) {
      expect(screen.queryByText(axis.label)).toBeNull()
    }
  })

  it('기본 설정 표시는 상태이지 토글이 아니다', async () => {
    // FR-002. 누를 수 있는 요소로 그리지 않는다.
    const user = userEvent.setup()
    const { support } = makeSupport()
    renderPage(support)

    await user.click(await findCardNamed(selectableStrategy.name))

    const badge = await screen.findByText('기본 설정')
    expect(badge.closest('button')).toBeNull()
    expect(screen.getByText(/기본값은 편의용 출발점이며 최적값이 아닙니다/)).toBeTruthy()
  })

  it('등록표가 임시값이면 그 사실을 문구로 보인다', async () => {
    // NFR-002. 색만으로 구분하지 않는다.
    const user = userEvent.setup()
    const { support } = makeSupport()
    renderPage(support)

    await user.click(await findCardNamed(selectableStrategy.name))

    expect(screen.getAllByText('임시값').length).toBeGreaterThan(0)
    expect(screen.getByText(/팀 확정 전 임시값입니다/)).toBeTruthy()
    // 비용 조건도 임시 버전이 그대로 보인다(FR-002·FR-010).
    expect(screen.getByText(new RegExp(versionsFixture.costProfileVersion))).toBeTruthy()
  })

  it('등록표 조회가 실패하면 두 행동 버튼이 이유와 함께 막힌다', async () => {
    // SC-008, quickstart 9. 임시 값이나 예비 등록표로 대신 동작하지 않는다.
    apiMocks.fetchStrategyRegistry.mockRejectedValue(new Error('registry down'))
    const { support, requests } = makeSupport()
    const user = userEvent.setup()
    renderPage(support)

    expect(await screen.findByRole('alert')).toBeTruthy()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy()

    const analyze = actionButton('분석하기')
    const autoCompare = actionButton('설정 조합 자동 비교')
    expect(analyze.getAttribute('aria-disabled')).toBe('true')
    expect(autoCompare.getAttribute('aria-disabled')).toBe('true')

    // 이유가 함께 전달된다 — `disabled`로 막으면 키보드 사용자가 읽을 수 없다(NFR-001).
    const reasonId = analyze.getAttribute('aria-describedby')
    expect(reasonId).toBeTruthy()
    expect(document.getElementById(reasonId!)?.textContent).toContain('불러오기 전에는')

    await user.click(analyze)
    expect(requests).toHaveLength(0)
  })

  it('분석 조건 버전을 확인할 수 없으면 분석하기가 막힌다', async () => {
    // "착수 전에 알아둘 것" ②. 값을 지어내 보내지 않는다.
    const user = userEvent.setup()
    const { support, requests } = makeSupport({ fetchAnalysisVersions: async () => null })
    renderPage(support)

    await user.click(await findCardNamed(selectableStrategy.name))

    const analyze = actionButton('분석하기')
    await waitFor(() => expect(analyze.getAttribute('aria-disabled')).toBe('true'))
    const reasonId = analyze.getAttribute('aria-describedby')
    expect(document.getElementById(reasonId!)?.textContent).toContain('분석 조건 버전')

    await user.click(analyze)
    expect(requests).toHaveLength(0)
  })

  it('아무것도 연결되지 않은 기본 상태에서는 두 통로가 모두 막힌 것으로 보인다', async () => {
    // ①·② 둘 다 미결일 때의 화면이다. 카드는 판정하지 못했다는 사실을 보이고 분석은 막힌다.
    renderPage()

    await findCardNamed(selectableStrategy.name)

    expect(screen.getAllByText(/공통 평가 기간을 확인할 수 없어/).length).toBeGreaterThan(0)
    expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('true')
  })

  it('워밍업을 채우지 못하는 전략은 고를 수 없고 분석도 막힌다', async () => {
    // FR-011. 카드가 막히면 '분석하기'도 그 이유로 막힌다.
    const user = userEvent.setup()
    const { support, requests } = makeSupport({
      judgeWarmup: () => ({
        verdict: { state: 'blocked', message: '최소 평가 기간(3년)에 못 미칩니다.' },
        window: null,
      }),
    })
    renderPage(support)

    const card = await findCardNamed(selectableStrategy.name)
    expect(card.getAttribute('aria-disabled')).toBe('true')
    expect(within(card).getByText(/최소 평가 기간\(3년\)에 못 미칩니다/)).toBeTruthy()

    await user.click(card)
    expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('true')
    expect(requests).toHaveLength(0)
  })

  it('앞 단계를 마치지 않았으면 카드를 그리지 않고 되돌린다', async () => {
    // spec Edge Cases. 섹터가 두 개 미만이면 기본값을 만들지 않는다.
    storeSelection(['ALPHA'])
    const { support } = makeSupport()
    renderPage(support)

    expect(await screen.findByText(/섹터·비중 단계를 먼저 마쳐 주세요/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: (accessibleName) => accessibleName.includes(selectableStrategy.name) })).toBeNull()
    // 앞 단계를 확인하기 전에는 등록표를 조회하지도 않는다.
    expect(apiMocks.fetchStrategyRegistry).not.toHaveBeenCalled()
  })

  it('등록표를 섹터 수와 함께 조회한다', async () => {
    const { support } = makeSupport()
    renderPage(support)

    await waitFor(() => expect(apiMocks.fetchStrategyRegistry).toHaveBeenCalled())
    expect(apiMocks.fetchStrategyRegistry.mock.calls[0][0]).toBe(catalogSectors.length)
  })

  it('전략을 고르기 전에는 분석하기가 그 이유로 막힌다', async () => {
    const { support } = makeSupport()
    renderPage(support)

    await findCardNamed(selectableStrategy.name)
    const analyze = actionButton('분석하기')
    expect(analyze.getAttribute('aria-disabled')).toBe('true')
    const reasonId = analyze.getAttribute('aria-describedby')
    expect(document.getElementById(reasonId!)?.textContent).toContain('전략 카드를 먼저')
  })

  it('보관값이 현재 등록표에서 유효하지 않으면 알리고 분석을 막는다', async () => {
    // FR-017. 자동 보정하지 않고 복원 선택을 보인다.
    const numberAxis = selectableStrategy.axes.find((axis) => axis.inputType === 'NUMBER')!
    window.sessionStorage.setItem(
      STRATEGY_SETTINGS_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        registryVersion: 'OLD-REGISTRY',
        universeSize: 3,
        selectedStrategyId: selectableStrategy.strategyId,
        valuesByStrategy: {
          [selectableStrategy.strategyId]: { [numberAxis.name]: 999 },
        },
        analysisCondition: { minEvaluationYears: 1, scenario: 'FULL_PERIOD' },
        invalidValues: [],
      }),
    )

    const user = userEvent.setup()
    const { support, requests } = makeSupport()
    renderPage(support)

    expect(await screen.findByText(new RegExp(`${numberAxis.label}.*999`))).toBeTruthy()
    expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('true')

    await user.click(screen.getByRole('button', { name: '기본값 복원' }))

    await waitFor(() => expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('false'))
    await user.click(actionButton('분석하기'))
    await waitFor(() => expect(requests).toHaveLength(1))
    const request = requests[0]
    expect(request.strategy.parameters[numberAxis.name]).toEqual(numberAxis.defaultValue)
  })

  it('요청을 보내는 동안 다시 누를 수 없다', async () => {
    const user = userEvent.setup()
    // 응답을 붙들어 둔 채 '보내는 중' 상태를 만든다. 해제 함수를 홀더에 담는 까닭은 프로미스 안에서
    // 대입한 값을 바깥에서 그대로 읽기 위해서다.
    const pending: { release: () => void } = { release: () => {} }
    const calls: BacktestCreateRequest[] = []
    const { support } = makeSupport({
      createBacktest: (request) => {
        calls.push(request)
        return new Promise((resolve) => {
          pending.release = () => resolve({ backtestId: 'BT-1', status: 'QUEUED' })
        })
      },
    })
    renderPage(support)

    await user.click(await findCardNamed(selectableStrategy.name))
    await waitFor(() => expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('false'))

    await user.click(actionButton('분석하기'))
    await waitFor(() => expect(calls).toHaveLength(1))

    // 보내는 중에는 같은 버튼이 다른 이름으로 바뀌고 다시 눌리지 않는다.
    const busy = actionButton('분석 요청을 보내는 중')
    expect(busy.getAttribute('aria-disabled')).toBe('true')
    await user.click(busy)
    expect(calls).toHaveLength(1)

    pending.release()
  })

  it('전략과 무관한 보관값의 무효 안내는 전략을 고르기 전에 보인다', async () => {
    // T093, FR-017. 최소 평가 기간은 카드의 선택 가능 여부를 바꾸는 값이라(FR-011a) 카드를 보기
    // 전에 알아야 한다. 전략 설정 안내에 묶어 두면 고른 뒤에야 알게 된다.
    const condition = registryFixture.analysisConditions.minEvaluationYears
    const notAChoice = Math.max(...condition.choices.map((choice) => Number(choice.value))) + 99
    window.sessionStorage.setItem(
      STRATEGY_SETTINGS_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        registryVersion: registryFixture.registryVersion,
        universeSize: catalogSectors.length,
        selectedStrategyId: null,
        valuesByStrategy: {},
        analysisCondition: { minEvaluationYears: notAChoice, scenario: 'FULL_PERIOD' },
        invalidValues: [],
      }),
    )

    const { support } = makeSupport()
    renderPage(support)

    // 전략을 하나도 고르지 않은 상태에서 보인다.
    const notice = await screen.findByRole('status', { name: '다시 확인할 분석 조건' })
    expect(notice.textContent).toContain(String(notAChoice))
    expect(screen.queryByRole('status', { name: '다시 확인할 설정값' })).toBeNull()
  })

  it('부모가 같은 조회 함수를 담아 통로를 새로 펼쳐도 버전을 다시 조회하지 않는다', async () => {
    // T094. 통로 객체의 참조가 아니라 조회 함수의 참조를 따른다. 호출자가 렌더마다 `{ ...base }`로
    // 새 객체를 만들어도 담긴 함수가 같으면 요청은 한 번이다.
    let fetchCount = 0
    const { support: base } = makeSupport()
    const stableFetch: AnalysisSupport['fetchAnalysisVersions'] = async () => {
      fetchCount += 1
      return versionsFixture
    }

    renderHost(() => ({ ...base, fetchAnalysisVersions: stableFetch }))

    const user = userEvent.setup()
    await user.click(await findCardNamed(selectableStrategy.name))
    await waitFor(() => expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('false'))

    await user.click(screen.getByRole('button', { name: '부모 다시 그리기' }))
    await user.click(screen.getByRole('button', { name: '부모 다시 그리기' }))

    expect(fetchCount).toBe(1)
    expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('false')
  })

  it('다시 조회하는 동안 받은 버전을 거두지 않아 분석하기가 깜빡이지 않는다', async () => {
    // T094가 막으려던 사용자 쪽 결함은 요청 횟수가 아니라, 다시 조회하는 동안 '확인하는 중'으로
    // 돌아가 '분석하기'가 잠깐씩 막히는 것이다. **두 번째 조회를 끝나지 않게 붙들어** 그 틈을 검사가
    // 반드시 보게 한다. 되돌아간다면 이 틈에서 버튼이 막혀 있다.
    let fetchCount = 0
    const { support: base } = makeSupport()

    renderHost(() => ({
      ...base,
      // 일부러 렌더마다 새 함수를 만든다. 첫 조회만 끝나고 뒤의 조회는 끝나지 않는다.
      fetchAnalysisVersions: () => {
        fetchCount += 1
        return fetchCount === 1 ? Promise.resolve(versionsFixture) : new Promise<never>(() => {})
      },
    }))

    const user = userEvent.setup()
    await user.click(await findCardNamed(selectableStrategy.name))
    await waitFor(() => expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('false'))

    await user.click(screen.getByRole('button', { name: '부모 다시 그리기' }))

    // 조회는 다시 나갔고(함수가 바뀌었으므로) 아직 끝나지 않았다. 그래도 버튼은 막히지 않는다.
    expect(fetchCount).toBeGreaterThan(1)
    expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('false')
  })

  it('통로를 실제로 바꾸면 버전 조회도 새 통로를 따른다', async () => {
    // S15P21E205-274. 화면이 열릴 때 한 번만 조회하던 때는 연결 전 통로의 `null`에 머물러, 새 통로로
    // 바꿔도 '분석하기'가 "분석 조건 버전을 확인할 수 없습니다"로 계속 막혔다.
    const { support: connected } = makeSupport()
    const disconnected: AnalysisSupport = { ...connected, fetchAnalysisVersions: async () => null }

    renderHost((generation) => (generation === 0 ? disconnected : connected))

    const user = userEvent.setup()
    await user.click(await findCardNamed(selectableStrategy.name))
    await waitFor(() => {
      const reasonId = actionButton('분석하기').getAttribute('aria-describedby')
      expect(document.getElementById(reasonId!)?.textContent).toContain('분석 조건 버전')
    })

    // 부모가 연결된 통로로 바꾼다.
    await user.click(screen.getByRole('button', { name: '부모 다시 그리기' }))

    await waitFor(() => expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('false'))
  })

  it('보관된 시작 금액이 등록표 범위 밖이면 그 금액을 보이지도 보내지도 않고 복원을 기다린다', async () => {
    // S15P21E205-274, SC-002·FR-017. 재검증이 분석 조건을 통째로 옮겨 범위 밖 금액이 살아남던 결함이다.
    // 비운 금액이 곧 기본값이 되면 조용히 바뀐 것과 같으므로, 복원을 누르기 전까지 금액을 모른다.
    const amount = registryFixture.analysisConditions.initialAmount
    const outOfRange = amount.min - amount.step
    window.sessionStorage.setItem(
      STRATEGY_SETTINGS_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        registryVersion: registryFixture.registryVersion,
        universeSize: catalogSectors.length,
        selectedStrategyId: null,
        valuesByStrategy: {},
        analysisCondition: {
          minEvaluationYears: registryFixture.analysisConditions.minEvaluationYears.default,
          scenario: 'FULL_PERIOD',
          initialAmount: outOfRange,
        },
        invalidValues: [],
      }),
    )

    const user = userEvent.setup()
    const { support, requests } = makeSupport()
    renderPage(support)

    // 전략을 고르기 전에도 안내가 보인다(T093과 같은 자리).
    const notice = await screen.findByRole('status', { name: '다시 확인할 분석 조건' })
    expect(notice.textContent).toContain(String(outOfRange))

    await user.click(await findCardNamed(selectableStrategy.name))

    // 기본값으로 대신 보이지 않는다.
    const summary = screen.getByLabelText('분석 조건 요약')
    expect(summary.textContent).not.toContain(amount.default.toLocaleString('ko-KR'))
    expect(summary.textContent).toContain('확인할 수 없음')

    const analyze = actionButton('분석하기')
    await waitFor(() => expect(analyze.getAttribute('aria-disabled')).toBe('true'))
    await user.click(analyze)
    expect(requests).toHaveLength(0)

    // 사용자가 복원을 누르면 그제야 기본값이 쓰인다.
    await user.click(within(notice).getByRole('button', { name: '기본값 복원' }))
    await waitFor(() => expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('false'))
    await user.click(actionButton('분석하기'))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0].conditions.initialCash).toBe(amount.default.toFixed(2))
  })

  it('요청을 조립하다 실패하면 실패 안내를 보인다', async () => {
    // S15P21E205-274. 조립 예외가 `try` 밖이라 거부된 프로미스로 버려져, 버튼을 눌러도 아무 일도
    // 일어나지 않았다. 계약을 어긴 등록표(기본 시작 금액 0)로 조립이 반드시 실패하게 만든다.
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    apiMocks.fetchStrategyRegistry.mockResolvedValue({
      ...registryFixture,
      analysisConditions: {
        ...registryFixture.analysisConditions,
        initialAmount: { ...registryFixture.analysisConditions.initialAmount, default: 0 },
      },
    })

    const user = userEvent.setup()
    const { support, requests } = makeSupport()
    renderPage(support)

    await user.click(await findCardNamed(selectableStrategy.name))
    await waitFor(() => expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('false'))
    await user.click(actionButton('분석하기'))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('분석 요청을 만들지 못했습니다')
    // 내부 예외 문장을 사용자에게 그대로 보이지 않는다.
    expect(alert.textContent).not.toContain('RangeError')
    expect(requests).toHaveLength(0)
    // 원인은 개발 도구에 남긴다.
    expect(consoleError).toHaveBeenCalled()
    // 이 파일의 afterEach는 모의를 지우기만 하고 되돌리지 않는다. 되돌리지 않으면 뒤의 검사에서
    // 오류가 조용히 삼켜진다.
    consoleError.mockRestore()
  })

  it('선택을 만든 뒤 섹터 대응표가 바뀌었으면 요청을 만들지 않고 앞 단계로 안내한다', async () => {
    // S15P21E205-274, 원칙 VIII. 종목은 지금 목록에서, 버전은 보관값에서 오면 서로 다른 대응표가
    // 한 요청에 섞인다. 서버는 요청의 버전으로 (섹터, 종목) 쌍을 검증한다.
    window.sessionStorage.setItem(
      SECTOR_SELECTION_STORAGE_KEY,
      JSON.stringify({
        version: SECTOR_SELECTION_PAYLOAD_VERSION,
        mapVersion: 'SEM-TEST-0',
        selected: [
          { code: 'ALPHA', weightTenths: 500 },
          { code: 'BETA', weightTenths: 500 },
        ],
        weightsTouched: true,
      }),
    )

    const { support } = makeSupport()
    renderPage(support)

    expect(await screen.findByText(/섹터 대응표가 바뀌었습니다/)).toBeTruthy()
    expect(screen.getByRole('button', { name: '섹터·비중 단계로 이동' })).toBeTruthy()
    // 앞 단계를 확인하기 전에는 카드도 등록표 조회도 없다.
    expect(
      screen.queryByRole('button', {
        name: (accessibleName) => accessibleName.includes(selectableStrategy.name),
      }),
    ).toBeNull()
    expect(apiMocks.fetchStrategyRegistry).not.toHaveBeenCalled()
  })

  it('축이 빠진 설정으로는 요청을 보내지 않는다', async () => {
    // T095. 차단 판정을 "복원 안내가 있는가"가 아니라 "축이 다 찼는가"로 묻는다. 축이 빠진 요청은
    // 서버가 400으로 거절한다(T048).
    const numberAxis = selectableStrategy.axes.find((axis) => axis.inputType === 'NUMBER')!
    window.sessionStorage.setItem(
      STRATEGY_SETTINGS_STORAGE_KEY,
      JSON.stringify({
        version: 1,
        registryVersion: 'OLD-REGISTRY',
        universeSize: catalogSectors.length,
        selectedStrategyId: selectableStrategy.strategyId,
        valuesByStrategy: {
          [selectableStrategy.strategyId]: { [numberAxis.name]: 9_999 },
        },
        analysisCondition: { minEvaluationYears: 1, scenario: 'FULL_PERIOD' },
        invalidValues: [],
      }),
    )

    const user = userEvent.setup()
    const { support, requests } = makeSupport()
    renderPage(support)

    const analyze = await screen.findByRole('button', {
      name: (accessibleName) => accessibleName.includes('분석하기'),
    })
    await waitFor(() => expect(analyze.getAttribute('aria-disabled')).toBe('true'))

    await user.click(analyze)
    expect(requests).toHaveLength(0)

    // 복원하면 축이 다 차고 그제야 보낼 수 있다.
    await user.click(screen.getByRole('button', { name: '기본값 복원' }))
    await waitFor(() => expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('false'))
    await user.click(actionButton('분석하기'))
    await waitFor(() => expect(requests).toHaveLength(1))

    for (const axis of selectableStrategy.axes) {
      expect(requests[0].strategy.parameters[axis.name]).toEqual(axis.defaultValue)
    }
  })

  it('전송에 실패하면 그 사실을 알리고 조용히 다시 보내지 않는다', async () => {
    const user = userEvent.setup()
    const calls: BacktestCreateRequest[] = []
    const { support } = makeSupport({
      createBacktest: async (request) => {
        calls.push(request)
        throw new Error('서버가 요청을 거절했습니다.')
      },
    })
    renderPage(support)

    await user.click(await findCardNamed(selectableStrategy.name))
    await waitFor(() => expect(actionButton('분석하기').getAttribute('aria-disabled')).toBe('false'))
    await user.click(actionButton('분석하기'))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('서버가 요청을 거절했습니다.')
    // 조용히 다시 보내지 않는다.
    expect(calls).toHaveLength(1)
  })
  describe('설정 조합 자동 비교로 넘어가기(FR-013, quickstart 11)', () => {
    it('고른 전략 하나와 섹터 후보 집합 전부를 007 화면으로 넘긴다', async () => {
      const user = userEvent.setup()
      const { support, requests } = makeSupport()
      renderPageWithParamSearchRoute(support)

      // 다른 전략을 한 번 골랐다가 옮겨도 마지막에 고른 전략 하나만 넘어간다.
      const other = registryFixture.strategies.find(
        (strategy) => strategy.selectable && strategy.strategyId !== selectableStrategy.strategyId,
      )!
      await user.click(await findCardNamed(other.name))
      await user.click(await findCardNamed(selectableStrategy.name))

      await waitFor(() =>
        expect(actionButton('설정 조합 자동 비교').getAttribute('aria-disabled')).toBe('false'),
      )
      await user.click(actionButton('설정 조합 자동 비교'))

      const location = await screen.findByTestId('param-search-location')
      const url = new URL(location.textContent!, 'http://localhost')
      expect(url.pathname).toBe('/param-search')
      expect(url.searchParams.get('strategies')).toBe(selectableStrategy.strategyId)
      expect(url.searchParams.get('instruments')?.split(',')).toEqual(
        catalogSectors.map((item) => item.representativeEtf.instrumentId),
      )
      // 질의 문자열은 둘뿐이다. 후보 숫자나 설정값을 싣지 않는다 — 탐색 범위는 007이 정한다.
      expect([...url.searchParams.keys()].sort()).toEqual(['instruments', 'strategies'])
      // 분석 요청을 대신 보내지 않는다.
      expect(requests).toHaveLength(0)
    })

    it('분석 조건 버전이 없어 분석하기가 막혀도 자동 비교로는 넘어갈 수 있다', async () => {
      // 버전은 '분석하기'의 요청 조립에만 필요하다. 자동 비교의 조건 확인은 007 화면이 한다.
      const user = userEvent.setup()
      renderPageWithParamSearchRoute(makeSupport({ fetchAnalysisVersions: async () => null }).support)

      await user.click(await findCardNamed(selectableStrategy.name))
      await waitFor(() => expect(reasonOf(actionButton('분석하기'))).toContain('분석 조건 버전'))
      expect(actionButton('설정 조합 자동 비교').getAttribute('aria-disabled')).toBe('false')

      await user.click(actionButton('설정 조합 자동 비교'))
      expect(await screen.findByTestId('param-search-location')).toBeTruthy()
    })

    it('전략을 고르기 전에는 자동 비교가 그 이유로 막히고 이동하지 않는다', async () => {
      const user = userEvent.setup()
      renderPageWithParamSearchRoute(makeSupport().support)

      await findCardNamed(selectableStrategy.name)
      // 카드가 먼저 그려지고 보관값 확인이 뒤따르므로 이유가 자리 잡기를 기다린다.
      await waitFor(() =>
        expect(reasonOf(actionButton('설정 조합 자동 비교'))).toContain('전략 카드를 먼저'),
      )
      const autoCompare = actionButton('설정 조합 자동 비교')
      expect(autoCompare.getAttribute('aria-disabled')).toBe('true')

      await user.click(autoCompare)
      expect(screen.queryByTestId('param-search-location')).toBeNull()
    })

    it('등록표를 받지 못했으면 자동 비교가 막히고 이동하지 않는다', async () => {
      apiMocks.fetchStrategyRegistry.mockRejectedValue(new Error('registry down'))
      const user = userEvent.setup()
      renderPageWithParamSearchRoute(makeSupport().support)

      expect(await screen.findByRole('alert')).toBeTruthy()
      const autoCompare = actionButton('설정 조합 자동 비교')
      expect(autoCompare.getAttribute('aria-disabled')).toBe('true')
      expect(reasonOf(autoCompare)).toContain('불러오기 전에는')

      await user.click(autoCompare)
      expect(screen.queryByTestId('param-search-location')).toBeNull()
    })

    it('보관된 선택이 지금 고를 수 없는 전략이면 그 이유로 막힌다', async () => {
      window.sessionStorage.setItem(
        STRATEGY_SETTINGS_STORAGE_KEY,
        JSON.stringify({
          version: 1,
          registryVersion: registryFixture.registryVersion,
          universeSize: 3,
          selectedStrategyId: unselectableStrategy.strategyId,
          valuesByStrategy: {},
          analysisCondition: { minEvaluationYears: 1, scenario: 'FULL_PERIOD' },
          invalidValues: [],
        }),
      )
      const user = userEvent.setup()
      renderPageWithParamSearchRoute(makeSupport().support)

      await findCardNamed(unselectableStrategy.name)
      await waitFor(() =>
        expect(reasonOf(actionButton('설정 조합 자동 비교'))).toContain(
          unselectableStrategy.unselectableReason!,
        ),
      )
      const autoCompare = actionButton('설정 조합 자동 비교')
      expect(autoCompare.getAttribute('aria-disabled')).toBe('true')

      await user.click(autoCompare)
      expect(screen.queryByTestId('param-search-location')).toBeNull()
    })
  })
})

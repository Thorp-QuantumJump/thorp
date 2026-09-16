import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
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
import { registryFixture, selectableStrategy } from '@/test/strategyRegistryFixtures'
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
})

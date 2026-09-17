import { renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  STRATEGY_SETTINGS_PATH,
  buildSelectionSnapshot,
  prefillStrategySettings,
  toWeightTenths,
} from './prefillStrategySettings'
import { StrategyRegistryError } from '@/api/strategyRegistry'
import {
  SECTOR_SELECTION_PAYLOAD_VERSION,
  SECTOR_SELECTION_STORAGE_KEY,
  readStoredSelection,
} from '@/diagnosis/selectionStorage'
import {
  STRATEGY_SETTINGS_PAYLOAD_VERSION,
  STRATEGY_SETTINGS_STORAGE_KEY,
  defaultValuesOf,
  useStrategySettings,
} from '@/hooks/useStrategySettings'
import { resultDetailCompleted, resultSectorCatalog } from '@/test/resultFixtures'
import { registryFixture, selectableStrategy, unselectableStrategy } from '@/test/strategyRegistryFixtures'
import type { ResultConfig } from '@/types/result'

/** 005 T072. FR-010 '다른 조건으로 비교'. */

const registryMock = vi.hoisted(() => ({ fetchStrategyRegistry: vi.fn() }))
const sectorMock = vi.hoisted(() => ({ fetchSectorCatalog: vi.fn() }))

vi.mock('@/api/strategyRegistry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/strategyRegistry')>()
  return { ...actual, ...registryMock }
})
vi.mock('@/api/sectors', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/sectors')>()
  return { ...actual, ...sectorMock }
})

function readStored(key: string): Record<string, unknown> {
  return JSON.parse(window.sessionStorage.getItem(key) ?? 'null') as Record<string, unknown>
}

/** 등록표에 있는 전략으로 낸 결과. 사용자 비중이 실린 요청이다 */
const config: ResultConfig = {
  ...resultDetailCompleted.config,
  strategyId: selectableStrategy.strategyId,
  parameters: { ...defaultValuesOf(selectableStrategy), driftWindow: 90 },
}

/** 규칙이 비중을 계산한 결과. 요청에 비중이 없다 */
const computedConfig: ResultConfig = {
  ...config,
  items: config.items.map(({ sectorId, instrumentId }) => ({ sectorId, instrumentId })),
}

describe('prefillStrategySettings', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    registryMock.fetchStrategyRegistry.mockReset()
    registryMock.fetchStrategyRegistry.mockResolvedValue(registryFixture)
    sectorMock.fetchSectorCatalog.mockReset()
    sectorMock.fetchSectorCatalog.mockResolvedValue({ status: 'loaded', catalog: resultSectorCatalog })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('결과의 조건을 002·003의 보관 키와 형식으로 쓰고 설정 화면으로 옮긴다', async () => {
    const navigate = vi.fn()

    const outcome = await prefillStrategySettings(config, navigate)

    expect(outcome).toEqual({ status: 'moved' })
    expect(navigate).toHaveBeenCalledExactlyOnceWith(STRATEGY_SETTINGS_PATH)
    expect(STRATEGY_SETTINGS_PATH).toBe('/strategy')
    expect(registryMock.fetchStrategyRegistry.mock.calls[0][0]).toBe(3)
    // 설정 화면이 이 선택을 이어받을 수 있는지(대응표 버전·섹터 수) 보려고 섹터 목록도 받는다.
    expect(sectorMock.fetchSectorCatalog).toHaveBeenCalledTimes(1)

    expect(readStored(SECTOR_SELECTION_STORAGE_KEY)).toEqual({
      version: SECTOR_SELECTION_PAYLOAD_VERSION,
      mapVersion: config.mappingVersion,
      selected: [
        { code: 'HARBOR', weightTenths: 400 },
        { code: 'LIGHTHOUSE', weightTenths: 300 },
        { code: 'DOCKYARD', weightTenths: 300 },
      ],
      weightsTouched: true,
    })

    expect(readStored(STRATEGY_SETTINGS_STORAGE_KEY)).toEqual({
      version: STRATEGY_SETTINGS_PAYLOAD_VERSION,
      registryVersion: registryFixture.registryVersion,
      universeSize: 3,
      selectedStrategyId: config.strategyId,
      valuesByStrategy: { [config.strategyId]: config.parameters },
      analysisCondition: {
        // 결과에 없는 값이라 003이 처음 열릴 때와 같은 등록표 기본값이다.
        minEvaluationYears: registryFixture.analysisConditions.minEvaluationYears.default,
        scenario: 'FULL_PERIOD',
        periodStart: config.start,
        periodEnd: config.end,
        initialAmount: 10_000_000,
      },
      invalidValues: [],
    })
  })

  it('003 설정 화면이 보관값을 읽으면 전략·설정값·기간·금액이 무효 항목 없이 돌아온다', async () => {
    await prefillStrategySettings(config, vi.fn())

    const { result } = renderHook(() => useStrategySettings(registryFixture, 3))

    expect(result.current.ready).toBe(true)
    expect(result.current.selectedStrategy?.strategyId).toBe(config.strategyId)
    expect(result.current.values).toEqual(config.parameters)
    expect(result.current.analysisCondition).toMatchObject({
      periodStart: config.start,
      periodEnd: config.end,
      initialAmount: 10_000_000,
    })
    expect(result.current.invalidValues).toEqual([])
  })

  it('002 화면이 보관값을 읽으면 섹터와 사용자 비중이 그대로 돌아온다', async () => {
    await prefillStrategySettings(config, vi.fn())

    const restored = readStoredSelection(resultSectorCatalog.sectors)

    expect(restored.problem).toBeNull()
    expect(restored.dropped).toEqual([])
    expect(restored.snapshot?.weightTenths).toEqual({ HARBOR: 400, LIGHTHOUSE: 300, DOCKYARD: 300 })
    expect(restored.snapshot?.weightsTouched).toBe(true)
  })

  it('결과를 만든 뒤 대응표가 바뀌었으면 보관값을 건드리지 않고 사유를 돌려준다', async () => {
    // 003은 보관된 대응표 버전이 지금과 다르면 조건을 이어받지 않고 앞 단계로 되돌린다.
    sectorMock.fetchSectorCatalog.mockResolvedValue({
      status: 'loaded',
      catalog: { ...resultSectorCatalog, mapVersion: 'SECTOR-ETF-TEST-2' },
    })
    const previous = '{"version":1,"mapVersion":"SECTOR-ETF-TEST-2","selected":[],"weightsTouched":false}'
    window.sessionStorage.setItem(SECTOR_SELECTION_STORAGE_KEY, previous)
    const navigate = vi.fn()

    const outcome = await prefillStrategySettings(config, navigate)

    expect(outcome.status).toBe('failed')
    expect(navigate).not.toHaveBeenCalled()
    expect(window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY)).toBe(previous)
    expect(window.sessionStorage.getItem(STRATEGY_SETTINGS_STORAGE_KEY)).toBeNull()
  })

  it('설정 화면이 이어받을 수 있는 섹터가 둘 미만이면 넘기지 않는다', async () => {
    const single: ResultConfig = { ...config, items: config.items.slice(0, 1) }
    const navigate = vi.fn()

    const outcome = await prefillStrategySettings(single, navigate)

    expect(outcome.status).toBe('failed')
    expect(navigate).not.toHaveBeenCalled()
    expect(window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY)).toBeNull()
  })

  it('지금 목록에 없는 섹터를 빼면 둘 미만이 되는 결과도 넘기지 않는다', async () => {
    sectorMock.fetchSectorCatalog.mockResolvedValue({
      status: 'loaded',
      catalog: {
        ...resultSectorCatalog,
        sectors: resultSectorCatalog.sectors.filter((sector) => sector.code === 'HARBOR'),
      },
    })
    const navigate = vi.fn()

    expect((await prefillStrategySettings(config, navigate)).status).toBe('failed')
    expect(navigate).not.toHaveBeenCalled()
  })

  it('섹터 목록을 받지 못하면 넘기지 않는다 — 이어받을 수 있는지 판정할 수 없다', async () => {
    sectorMock.fetchSectorCatalog.mockResolvedValue({
      status: 'failed',
      kind: 'network',
      code: null,
      message: '연결 실패',
      retryable: true,
    })
    const navigate = vi.fn()

    const outcome = await prefillStrategySettings(config, navigate)

    expect(outcome.status).toBe('failed')
    expect(navigate).not.toHaveBeenCalled()
    expect(window.sessionStorage.getItem(STRATEGY_SETTINGS_STORAGE_KEY)).toBeNull()
  })

  it('같은 탭에서 다른 전략에 맞춰 둔 값과 고른 최소 평가 기간을 지우지 않는다', async () => {
    const otherId = unselectableStrategy.strategyId
    const otherValues = { pickCount: 4 }
    window.sessionStorage.setItem(
      STRATEGY_SETTINGS_STORAGE_KEY,
      JSON.stringify({
        version: STRATEGY_SETTINGS_PAYLOAD_VERSION,
        registryVersion: registryFixture.registryVersion,
        universeSize: 3,
        selectedStrategyId: otherId,
        valuesByStrategy: { [otherId]: otherValues },
        analysisCondition: { minEvaluationYears: 3, scenario: 'FULL_PERIOD' },
        invalidValues: [],
      }),
    )

    await prefillStrategySettings(config, vi.fn())

    const stored = readStored(STRATEGY_SETTINGS_STORAGE_KEY)
    expect(stored.valuesByStrategy).toEqual({
      [otherId]: otherValues,
      [config.strategyId]: config.parameters,
    })
    expect(stored.selectedStrategyId).toBe(config.strategyId)
    // 결과에 없는 값이라 이 탭에서 고른 값을 그대로 둔다.
    expect(stored.analysisCondition).toMatchObject({ minEvaluationYears: 3 })
  })

  it('기준 결과의 조건 객체를 바꾸지 않는다', async () => {
    const before = structuredClone(config)

    await prefillStrategySettings(config, vi.fn())

    expect(config).toEqual(before)
  })

  it('등록표 범위 밖 값도 고치지 않고 넘기고, 003이 무엇이 어긋났는지 알린다', async () => {
    const outOfRange: ResultConfig = {
      ...config,
      parameters: { ...config.parameters, driftWindow: 7 },
    }

    await prefillStrategySettings(outOfRange, vi.fn())

    const stored = readStored(STRATEGY_SETTINGS_STORAGE_KEY)
    expect(stored.valuesByStrategy).toEqual({ [config.strategyId]: outOfRange.parameters })
    const { result } = renderHook(() => useStrategySettings(registryFixture, 3))
    expect(result.current.invalidValues.map((entry) => entry.axisName)).toEqual(['driftWindow'])
  })

  it('규칙이 비중을 계산한 결과는 002가 손대지 않은 선택처럼 표시 순서의 첫 섹터에 잔여분을 붙인다', async () => {
    await prefillStrategySettings(computedConfig, vi.fn())

    expect(sectorMock.fetchSectorCatalog).toHaveBeenCalledTimes(1)
    const restored = readStoredSelection(resultSectorCatalog.sectors)
    // 표시 순서는 업종(등대·항구) 다음 테마(조선소)다.
    expect(restored.snapshot?.selectedCodes).toEqual(['LIGHTHOUSE', 'HARBOR', 'DOCKYARD'])
    expect(restored.snapshot?.weightTenths).toEqual({ LIGHTHOUSE: 334, HARBOR: 333, DOCKYARD: 333 })
    expect(restored.snapshot?.weightsTouched).toBe(false)
  })

  it('섹터 목록을 받지 못해도 멈추지 않고 결과의 순서로 동일 비중을 넣는다', () => {
    expect(buildSelectionSnapshot(computedConfig, null).weightTenths).toEqual({
      HARBOR: 334,
      LIGHTHOUSE: 333,
      DOCKYARD: 333,
    })
  })

  it('비중 문자열을 반올림 없이 0.1%p 정수로 옮긴다', () => {
    expect(toWeightTenths('0.4000')).toBe(400)
    expect(toWeightTenths('0.3330')).toBe(333)
    expect(toWeightTenths('1.0000')).toBe(1000)
    expect(toWeightTenths('0.5')).toBe(500)
    // 0.1%p보다 잘게 나뉜 값과 읽을 수 없는 값은 002가 '쓸 수 없는 비중'으로 알리게 둔다.
    expect(toWeightTenths('0.33333')).toBeNaN()
    expect(toWeightTenths('abc')).toBeNaN()
    expect(toWeightTenths('')).toBeNaN()
  })

  it('설정 화면이 다루지 않는 별도 시나리오 결과는 조건을 바꿔 넘기지 않는다', async () => {
    const navigate = vi.fn()

    const outcome = await prefillStrategySettings({ ...config, scenario: 'TARGET_RISK' }, navigate)

    expect(outcome.status).toBe('failed')
    expect(navigate).not.toHaveBeenCalled()
    expect(registryMock.fetchStrategyRegistry).not.toHaveBeenCalled()
    expect(window.sessionStorage.getItem(STRATEGY_SETTINGS_STORAGE_KEY)).toBeNull()
  })

  it('등록표를 받지 못하면 옮기지 않고 사유를 돌려준다', async () => {
    registryMock.fetchStrategyRegistry.mockRejectedValue(
      new StrategyRegistryError('network', '연결 실패'),
    )
    const navigate = vi.fn()

    const outcome = await prefillStrategySettings(config, navigate)

    expect(outcome.status).toBe('failed')
    expect(navigate).not.toHaveBeenCalled()
    expect(window.sessionStorage.getItem(STRATEGY_SETTINGS_STORAGE_KEY)).toBeNull()
    expect(window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY)).toBeNull()
  })

  it('화면을 떠나 끊긴 요청은 실패로 알리지 않는다', async () => {
    registryMock.fetchStrategyRegistry.mockRejectedValue(
      new StrategyRegistryError('canceled', '취소'),
    )

    expect(await prefillStrategySettings(config, vi.fn())).toEqual({ status: 'canceled' })
  })

  it('저장소가 막혀 있으면 옮기지 않는다', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const navigate = vi.fn()

    const outcome = await prefillStrategySettings(config, navigate)

    expect(outcome.status).toBe('failed')
    expect(navigate).not.toHaveBeenCalled()
  })

  it('설정 보관만 실패하면 먼저 덮어쓴 002 선택을 앞선 값으로 되돌린다', async () => {
    const previous = '{"version":1,"mapVersion":"OLD","selected":[],"weightsTouched":false}'
    window.sessionStorage.setItem(SECTOR_SELECTION_STORAGE_KEY, previous)
    const original = Storage.prototype.setItem
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
      this: Storage,
      key: string,
      value: string,
    ) {
      if (key === STRATEGY_SETTINGS_STORAGE_KEY) throw new Error('quota')
      original.call(this, key, value)
    })
    const navigate = vi.fn()

    const outcome = await prefillStrategySettings(config, navigate)

    expect(outcome.status).toBe('failed')
    expect(navigate).not.toHaveBeenCalled()
    expect(window.sessionStorage.getItem(SECTOR_SELECTION_STORAGE_KEY)).toBe(previous)
  })

  it('003이 읽지 못하는 설정 모양이면 옮기지 않는다', async () => {
    const nested: ResultConfig = { ...config, parameters: { deep: { inner: { value: 1 } } } }
    const navigate = vi.fn()

    const outcome = await prefillStrategySettings(nested, navigate)

    expect(outcome.status).toBe('failed')
    expect(navigate).not.toHaveBeenCalled()
  })
})

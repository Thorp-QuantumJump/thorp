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
} from '@/diagnosis/selectionStorage'
import {
  STRATEGY_SETTINGS_PAYLOAD_VERSION,
  STRATEGY_SETTINGS_STORAGE_KEY,
} from '@/hooks/useStrategySettings'
import { resultDetailCompleted } from '@/test/resultFixtures'
import { registryFixture } from '@/test/strategyRegistryFixtures'
import type { ResultConfig } from '@/types/result'

/** 005 T072. FR-010 '다른 조건으로 비교'. */

const registryMock = vi.hoisted(() => ({ fetchStrategyRegistry: vi.fn() }))

vi.mock('@/api/strategyRegistry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/strategyRegistry')>()
  return { ...actual, ...registryMock }
})

function readStored(key: string): Record<string, unknown> {
  return JSON.parse(window.sessionStorage.getItem(key) ?? 'null') as Record<string, unknown>
}

const config = resultDetailCompleted.config

describe('prefillStrategySettings', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    registryMock.fetchStrategyRegistry.mockReset()
    registryMock.fetchStrategyRegistry.mockResolvedValue(registryFixture)
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
    expect(registryMock.fetchStrategyRegistry).toHaveBeenCalledWith(3, undefined)

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

  it('기준 결과의 조건 객체를 바꾸지 않는다', async () => {
    const before = structuredClone(config)

    await prefillStrategySettings(config, vi.fn())

    expect(config).toEqual(before)
  })

  it('등록표 범위 밖 값도 고치지 않고 넘긴다 — 판정은 설정 화면이 한다', async () => {
    const outOfRange: ResultConfig = {
      ...config,
      initialCash: '1.00',
      parameters: { tideWindow: 'NOT_A_CHOICE' },
    }

    await prefillStrategySettings(outOfRange, vi.fn())

    const stored = readStored(STRATEGY_SETTINGS_STORAGE_KEY)
    expect(stored.valuesByStrategy).toEqual({ [config.strategyId]: { tideWindow: 'NOT_A_CHOICE' } })
    expect(stored.analysisCondition).toMatchObject({ initialAmount: 1 })
  })

  it('규칙이 비중을 계산한 결과는 002가 손대지 않은 선택처럼 동일 비중으로 넘긴다', () => {
    const computed: ResultConfig = {
      ...config,
      items: config.items.map(({ sectorId, instrumentId }) => ({ sectorId, instrumentId })),
    }

    expect(buildSelectionSnapshot(computed)).toEqual({
      mapVersion: config.mappingVersion,
      selectedCodes: ['HARBOR', 'LIGHTHOUSE', 'DOCKYARD'],
      weightTenths: { HARBOR: 334, LIGHTHOUSE: 333, DOCKYARD: 333 },
      weightsTouched: false,
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

  it('저장소가 막혀 있으면 옮기지 않는다 — 빈 설정 화면을 채워진 화면으로 읽게 하지 않는다', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const navigate = vi.fn()

    const outcome = await prefillStrategySettings(config, navigate)

    expect(outcome.status).toBe('failed')
    expect(navigate).not.toHaveBeenCalled()
  })

  it('003이 읽지 못하는 설정 모양이면 옮기지 않는다', async () => {
    const nested: ResultConfig = { ...config, parameters: { deep: { inner: { value: 1 } } } }
    const navigate = vi.fn()

    const outcome = await prefillStrategySettings(nested, navigate)

    expect(outcome.status).toBe('failed')
    expect(navigate).not.toHaveBeenCalled()
  })
})

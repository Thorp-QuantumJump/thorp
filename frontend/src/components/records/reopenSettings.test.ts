import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { regimeRecord, reopenableDetail as reopenable } from '@/test/myRecordsFixtures'
import type { MyRecordDetail } from '@/types/myRecords'

/** 006 T029. research R4 — 006은 빌더를 만들지 않고 005 함수를 그대로 부른다. */

const prefillMocks = vi.hoisted(() => ({ prefillStrategySettings: vi.fn() }))

vi.mock('@/components/result/prefillStrategySettings', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/components/result/prefillStrategySettings')>()
  return { ...actual, prefillStrategySettings: prefillMocks.prefillStrategySettings }
})

const { REOPEN_UNAVAILABLE, reopenSettings } = await import('./reopenSettings')

// 경제환경 기록의 상세. 서버가 reopenSettings.available=false 로 판정하고 reopenPayload 를 만들지 않는다.
const regimeDetail: MyRecordDetail = {
  ...regimeRecord,
  lastViewedAt: null,
  referenceVersions: {},
  reopenSettings: { available: false },
}

describe('reopenSettings', () => {
  beforeEach(() => {
    prefillMocks.prefillStrategySettings.mockReset()
    prefillMocks.prefillStrategySettings.mockResolvedValue({ status: 'moved' })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('reopenPayload 를 005 함수에 그대로 넘긴다 — 자르거나 채우지 않고 warmupTradingDays 도 그대로다', async () => {
    const navigate = vi.fn()
    const controller = new AbortController()

    await expect(reopenSettings(reopenable, navigate, controller.signal)).resolves.toEqual({
      status: 'moved',
    })

    expect(prefillMocks.prefillStrategySettings).toHaveBeenCalledTimes(1)
    const [passed, passedNavigate, passedSignal] = prefillMocks.prefillStrategySettings.mock.calls[0]
    expect(passed).toBe(reopenable.reopenPayload)
    expect(passed.warmupTradingDays).toBe(77)
    expect(passedNavigate).toBe(navigate)
    expect(passedSignal).toBe(controller.signal)
  })

  it('대응표가 달라 섹터를 넘기지 못하면 005 의 reselectSectors 결과를 그대로 드러낸다', async () => {
    prefillMocks.prefillStrategySettings.mockResolvedValue({
      status: 'reselectSectors',
      message: '이 결과의 섹터 대응표가 지금 대응표와 달라 섹터는 넘기지 않았습니다.',
    })

    const outcome = await reopenSettings(reopenable, vi.fn())

    expect(outcome.status).toBe('reselectSectors')
    expect(outcome.status === 'reselectSectors' && outcome.message).toContain('대응표')
  })

  it('서버가 열 수 없다고 판정한 기록(경제환경·별도 시나리오)은 005 함수를 부르지 않는다', async () => {
    const navigate = vi.fn()

    await expect(reopenSettings(regimeDetail, navigate)).resolves.toEqual({
      status: 'failed',
      message: REOPEN_UNAVAILABLE,
    })
    await expect(
      reopenSettings({ ...reopenable, reopenSettings: { available: false } }, navigate),
    ).resolves.toMatchObject({ status: 'failed' })

    expect(prefillMocks.prefillStrategySettings).not.toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
  })
})

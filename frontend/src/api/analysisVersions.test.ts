import { AxiosError, AxiosHeaders, type AxiosResponse } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { resolveRulesVersion } from '@/components/strategy/analysisSupport'
import { fetchAnalysisVersions, readBacktestRegistry } from './analysisVersions'
import { apiClient } from './client'

function axiosResponse<T>(data: T, status = 200): AxiosResponse<T> {
  return { data, status, statusText: 'OK', headers: {}, config: { headers: new AxiosHeaders() } }
}

const body = {
  strategyRules: [
    { strategyId: 'EQUAL_WEIGHT', rulesVersion: '1', status: 'REGISTERED', provisional: true },
    { strategyId: 'MIN_VARIANCE', rulesVersion: '1', status: 'PENDING_DECISION', provisional: true },
    { strategyId: 'TARGET_RISK_OVERLAY', rulesVersion: '1', status: 'REGISTERED', provisional: true },
  ],
  defaultCostProfile: {
    version: 'PROVISIONAL-DEMO-1',
    provisional: true,
    commissionBps: 1.5,
    spreadBps: 5,
    slippageBps: 2,
    taxBps: 20,
  },
}

describe('규칙·비용 버전 조회(T039, 004 GET /backtests/registry)', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('004 등록표를 한 번 받아 규칙 행과 기본 비용 조건을 읽는다', async () => {
    const getSpy = vi
      .spyOn(apiClient, 'get')
      .mockResolvedValue(axiosResponse({ success: true, data: body, error: null }))

    const versions = await fetchAnalysisVersions()

    expect(getSpy).toHaveBeenCalledTimes(1)
    expect(getSpy.mock.calls[0][0]).toBe('/backtests/registry')
    expect(versions).toEqual({
      strategyRules: body.strategyRules,
      costProfileVersion: 'PROVISIONAL-DEMO-1',
      costProfileProvisional: true,
      costRates: { commissionBps: 1.5, spreadBps: 5, slippageBps: 2, taxBps: 20 },
    })
  })

  it('엔드포인트가 없으면(404) 거부한다', async () => {
    const notFound = new AxiosError('not found')
    notFound.response = axiosResponse(
      { success: false, data: null, error: { code: 'RESOURCE_NOT_FOUND', message: '없음' } },
      404,
    ) as AxiosResponse
    vi.spyOn(apiClient, 'get').mockRejectedValue(notFound)

    await expect(fetchAnalysisVersions()).rejects.toMatchObject({ code: 'RESOURCE_NOT_FOUND' })
  })

  it('응답이 계약과 어긋나면 거부한다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      axiosResponse({ success: true, data: { strategyRules: body.strategyRules }, error: null }),
    )

    await expect(fetchAnalysisVersions()).rejects.toMatchObject({ kind: 'unexpected' })
  })

  it.each([
    ['비용 수치가 빠졌다', { ...body, defaultCostProfile: { version: 'V', provisional: true } }],
    [
      '규칙 행의 임시 여부가 빠졌다',
      { ...body, strategyRules: [{ strategyId: 'A', rulesVersion: '1', status: 'REGISTERED' }] },
    ],
    [
      '비용 수치가 수가 아니다',
      { ...body, defaultCostProfile: { ...body.defaultCostProfile, taxBps: '20' } },
    ],
  ])('%s면 일부만 살리지 않는다', (_, data) => {
    expect(readBacktestRegistry(data)).toBeNull()
  })
})

describe('고른 전략의 규칙 버전 찾기', () => {
  const versions = readBacktestRegistry(body)

  it('등록된 행의 버전을 쓰고, 미확정이면 그 사실을, 행이 없으면 알 수 없음을 돌려준다', () => {
    if (!versions) throw new Error('픽스처를 읽지 못했습니다.')
    expect(resolveRulesVersion(versions, 'EQUAL_WEIGHT')).toEqual({
      status: 'registered',
      rulesVersion: '1',
    })
    expect(resolveRulesVersion(versions, 'MIN_VARIANCE')).toEqual({ status: 'pendingDecision' })
    expect(resolveRulesVersion(versions, 'SECTOR_MOMENTUM')).toEqual({ status: 'unavailable' })
  })

  it('등록된 행이 둘이면 순서로 고르지 않는다', () => {
    if (!versions) throw new Error('픽스처를 읽지 못했습니다.')
    const doubled = {
      ...versions,
      strategyRules: [
        ...versions.strategyRules,
        { strategyId: 'EQUAL_WEIGHT', rulesVersion: '2', status: 'REGISTERED', provisional: false },
      ],
    }
    expect(resolveRulesVersion(doubled, 'EQUAL_WEIGHT')).toEqual({ status: 'unavailable' })
  })
})

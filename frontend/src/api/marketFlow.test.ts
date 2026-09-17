import { AxiosHeaders, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { FieldStatus, FieldSummary, MarketFlowFieldId } from '@/types/marketFlow'
import { apiClient } from './client'
import { fetchMarketFlowField, fetchMarketFlowFields } from './marketFlow'

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: new AxiosHeaders() } }
}

function field(fieldId: MarketFlowFieldId, status: FieldStatus): FieldSummary {
  return {
    fieldId,
    displayName: fieldId,
    displayOrder: 1,
    status,
    narrative: status === 'READY' ? '생산 증가율이 낮아졌습니다.' : null,
    narrativeSubjects: [],
    referenceMonth: '2026-07',
    referenceMonthHeldBackBy: [],
    lastUpdatedAt: '2026-09-15T21:10:00Z',
    isExampleData: status === 'EXAMPLE',
  }
}

describe('시장 흐름 API', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('네 가지 자료 상태를 모두 정상 목록으로 읽는다', async () => {
    const fields = [
      field('production', 'READY'),
      field('consumption_investment', 'EXAMPLE'),
      field('external_trade', 'EMPTY'),
      field('labor', 'COLLECTION_FAILED'),
    ]
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      response({
        httpStatus: 'OK', isSuccess: true, message: 'ok', code: 200,
        result: {
          registryVersion: 'registry-v1', datasetVersion: 'dataset-v1',
          narrativeRuleVersion: 'narrative-v1', historicalComparisonNotice: '개정자료 안내', fields,
        },
      }),
    )

    await expect(fetchMarketFlowFields()).resolves.toMatchObject({ fields })
    expect(apiClient.get).toHaveBeenCalledWith('/market-flow/fields', { signal: undefined })
  })

  it('분야 상세 경로를 인코딩해 공용 클라이언트로 조회한다', async () => {
    const summary = field('labor', 'READY')
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      response({
        httpStatus: 'OK', isSuccess: true, message: 'ok', code: 200,
        result: {
          ...summary,
          multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.',
          indicators: [{
            indicatorId: 'employment', name: '취업자 수', unit: '%', value: 0.7,
            path: [
              { month: '2026-06', value: 1.4 }, { month: '2026-07', value: 1.0 },
              { month: '2026-08', value: 0.7 },
            ],
            referenceMonth: '2026-08', releaseDate: '2026-09-10',
            source: '경제활동인구조사', agency: '통계청', afterReferenceMonthRelease: null,
          }],
        },
      }),
    )

    await expect(fetchMarketFlowField('labor')).resolves.toMatchObject({ fieldId: 'labor' })
    expect(apiClient.get).toHaveBeenCalledWith('/market-flow/fields/labor', { signal: undefined })
  })
})

import { AxiosHeaders, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { FieldStatus, FieldSummary, MarketFlowFieldId } from '@/types/marketFlow'
import { ApiClientError, apiClient } from './client'
import { fetchMarketFlowField, fetchMarketFlowFields } from './marketFlow'

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: new AxiosHeaders() } }
}

/** 계약을 지키는 목록 응답. 각 검증 테스트는 여기서 한 군데만 망가뜨린다. */
function validSummaryBody() {
  return {
    httpStatus: 'OK',
    isSuccess: true,
    message: 'ok',
    code: 200,
    result: {
      registryVersion: 'registry-v1',
      datasetVersion: 'dataset-v1',
      narrativeRuleVersion: 'narrative-v1',
      historicalComparisonNotice: '개정자료 안내',
      fields: [field('production', 'READY')],
    },
  }
}

/**
 * 파서가 던진 Error는 `normalizeApiError`가 `ApiClientError('unexpected')`로 감싼다.
 * 원래 문장은 `cause`에만 남으므로, 삼킨 게 아니라 감싼 것인지 거기까지 확인한다.
 */
async function expectRejectedByParser(promise: Promise<unknown>, parserMessage: string) {
  const caught: unknown = await promise.then(
    () => {
      throw new Error('파서가 막지 않고 값을 돌려주었다.')
    },
    (error: unknown) => error,
  )

  expect(caught).toBeInstanceOf(ApiClientError)
  const error = caught as ApiClientError
  expect(error.kind).toBe('unexpected')
  expect((error.cause as Error).message).toBe(parserMessage)
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

  const ENVELOPE_MESSAGE = '시장 흐름 API 응답 형식이 계약과 다릅니다.'
  const SUMMARY_MESSAGE = '시장 흐름 분야 목록을 읽을 수 없습니다.'

  // 성공 봉투가 아닌 응답은 result를 읽기 전에 막는다. 여기서 통과시키면
  // 실패 응답의 result가 화면까지 흘러가 빈 값이 정상 자료처럼 보인다.
  it.each([
    ['httpStatus가 OK가 아니면', { httpStatus: 'BAD_REQUEST' }],
    ['isSuccess가 false면', { isSuccess: false }],
    ['code가 200이 아니면', { code: 500 }],
    ['message가 문자열이 아니면', { message: null }],
  ])('%s 봉투 단계에서 막는다', async (_label, broken) => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(response({ ...validSummaryBody(), ...broken }))

    await expectRejectedByParser(fetchMarketFlowFields(), ENVELOPE_MESSAGE)
  })

  it.each([
    ['응답이 객체가 아니면', 'unexpected string'],
    ['봉투가 비어 있으면', null],
    ['result 키가 아예 없으면', { httpStatus: 'OK', isSuccess: true, message: 'ok', code: 200 }],
  ])('%s 봉투 단계에서 막는다', async (_label, body) => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(response(body))

    await expectRejectedByParser(fetchMarketFlowFields(), ENVELOPE_MESSAGE)
  })

  // 봉투는 멀쩡한데 result가 계약과 다른 경우다. 타입 단언만 있고 검사가 없으면
  // 여기서 통과해 화면이 undefined를 렌더하다 죽는다.
  // FR-012는 역사 비교를 보일 때 안내 문구를 함께 두라고 요구한다. 문구 없이 분야 자료만
  // 그리는 편이 낫다고 판단해 파서를 느슨하게 풀면 이 두 테스트가 막는다.
  // 계약도 이 필드를 required로 두고, 백엔드도 상수로 항상 채운다.
  it.each([
    ['키가 없으면', { historicalComparisonNotice: undefined }],
    ['null로 오면', { historicalComparisonNotice: null }],
  ])('역사 비교 안내 문구가 %s 목록 전체를 막는다', async (_label, brokenResult) => {
    const body = validSummaryBody()
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      response({ ...body, result: { ...body.result, ...brokenResult } }),
    )

    await expectRejectedByParser(fetchMarketFlowFields(), SUMMARY_MESSAGE)
  })

  it.each([
    ['fields가 배열이 아니면', { fields: {} }],
    ['registryVersion이 없으면', { registryVersion: undefined }],
    ['narrativeRuleVersion이 문자열이 아니면', { narrativeRuleVersion: 7 }],
  ])('%s 목록 검증에서 막는다', async (_label, brokenResult) => {
    const body = validSummaryBody()
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      response({ ...body, result: { ...body.result, ...brokenResult } }),
    )

    await expectRejectedByParser(fetchMarketFlowFields(), SUMMARY_MESSAGE)
  })

  it.each([
    ['모르는 분야 id는', { fieldId: 'crypto' }],
    ['타입에 없는 STALE 상태는', { status: 'STALE' }],
    ['isExampleData가 boolean이 아니면', { isExampleData: 'false' }],
    ['narrativeSubjects가 문자열 배열이 아니면', { narrativeSubjects: [1, 2] }],
  ])('%s 분야 하나만 어긋나도 목록 전체를 막는다', async (_label, brokenField) => {
    const body = validSummaryBody()
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      response({
        ...body,
        result: {
          ...body.result,
          fields: [field('production', 'READY'), { ...field('labor', 'READY'), ...brokenField }],
        },
      }),
    )

    await expectRejectedByParser(fetchMarketFlowFields(), SUMMARY_MESSAGE)
  })

  it('3개월 경로가 3칸이 아닌 지표는 상세 조회를 막는다', async () => {
    const summary = field('labor', 'READY')
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      response({
        httpStatus: 'OK', isSuccess: true, message: 'ok', code: 200,
        result: {
          ...summary,
          multipleReferenceMonthNotice: '분야마다 기준월이 다를 수 있습니다.',
          indicators: [{
            indicatorId: 'employment', name: '취업자 수', unit: '%', value: 0.7,
            // FR-007이 요구하는 3개월 경로가 두 칸뿐이다.
            path: [{ month: '2026-07', value: 1.0 }, { month: '2026-08', value: 0.7 }],
            referenceMonth: '2026-08', releaseDate: '2026-09-10',
            source: '경제활동인구조사', agency: '통계청', afterReferenceMonthRelease: null,
          }],
        },
      }),
    )

    await expectRejectedByParser(
      fetchMarketFlowField('labor'),
      '시장 흐름 분야 상세를 읽을 수 없습니다.',
    )
  })
})

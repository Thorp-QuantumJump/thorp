import assert from 'node:assert/strict'
import { registerHooks } from 'node:module'
import { test } from 'node:test'
import { apiClient } from './marketClientStub.ts'
import type {
  ApiEnvelope,
  MarketIndicatorCode,
  MarketIndicatorResponse,
  MarketSeriesResponse,
} from '../types/marketApi.ts'

const clientStubUrl = new URL('./marketClientStub.ts', import.meta.url).href

// 운영 모듈은 그대로 두고 Node 단독 테스트에서만 공용 HTTP 클라이언트를 대역으로 교체한다.
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === './client' && context.parentURL?.endsWith('/api/market.ts')) {
      return { url: clientStubUrl, shortCircuit: true }
    }
    return nextResolve(specifier, context)
  },
})

const { fetchLatestMarketIndicators, fetchMarketSeries, marketIndicatorCodes } = await import('./market.ts')

const codeByEndpoint: Record<string, MarketIndicatorCode> = {
  '/markets/kospi': 'KOSPI',
  '/markets/kosdaq': 'KOSDAQ',
  '/markets/usd-krw': 'USD_KRW',
  '/markets/vkospi200': 'VKOSPI200',
  '/markets/ktb-3y': 'KTB_3Y',
  '/markets/coincident-cycle': 'COINCIDENT_CYCLE',
  '/markets/leading-cycle': 'LEADING_CYCLE',
  '/markets/esi-cycle': 'ESI_CYCLE',
  '/markets/core-cpi-yoy': 'CORE_CPI_YOY',
  '/markets/exports-usd': 'EXPORTS_USD',
  '/markets/m2': 'M2',
}

function responseFor(code: MarketIndicatorCode): ApiEnvelope<MarketIndicatorResponse> {
  return {
    httpStatus: 'OK',
    isSuccess: true,
    message: '성공',
    code: 200,
    result: {
      code,
      name: code,
      group: code === 'KOSPI' ? 'market' : 'macro',
      value: 100,
      unit: '지수',
      frequency: 'monthly',
      observationDate: '2026-08',
      retrievedAt: '2026-09-08T00:00:00Z',
      source: { provider: 'test', datasetName: 'test' },
      status: 'ok',
      statusMessage: null,
    },
  }
}

async function withMockedGet(
  handler: (endpoint: string) => ApiEnvelope<MarketIndicatorResponse> | Promise<never>,
) {
  const originalGet = apiClient.get
  apiClient.get = (async (endpoint: string) => ({
    data: await handler(endpoint),
  })) as typeof apiClient.get

  try {
    return await fetchLatestMarketIndicators()
  } finally {
    apiClient.get = originalGet
  }
}

test('한 지표 요청이 실패해도 나머지 열 개의 성공 결과를 유지한다', async () => {
  const batch = await withMockedGet((endpoint) => {
    const code = codeByEndpoint[endpoint]
    assert.ok(code, endpoint)
    if (code === 'KOSPI') return Promise.reject(new Error('network error'))
    return responseFor(code)
  })

  assert.equal(Object.keys(batch.data).length, marketIndicatorCodes.length - 1)
  assert.equal(batch.data.KOSPI, undefined)
  assert.match(batch.errors.KOSPI ?? '', /network error/)
  assert.equal(batch.data.KOSDAQ?.value, 100)
})

test('실패 envelope, 코드 불일치, NaN과 Infinity를 개별 오류로 격리한다', async () => {
  const batch = await withMockedGet((endpoint) => {
    const requestedCode = codeByEndpoint[endpoint]
    assert.ok(requestedCode, endpoint)
    const body = responseFor(requestedCode)

    if (requestedCode === 'KOSPI') body.isSuccess = false
    if (requestedCode === 'KOSDAQ') body.result.code = 'KOSPI'
    if (requestedCode === 'USD_KRW') body.result.value = Number.NaN
    if (requestedCode === 'VKOSPI200') body.result.value = Number.POSITIVE_INFINITY

    return body
  })

  assert.deepEqual(
    Object.keys(batch.errors).sort(),
    ['KOSDAQ', 'KOSPI', 'USD_KRW', 'VKOSPI200'],
  )
  assert.equal(Object.keys(batch.data).length, marketIndicatorCodes.length - 4)
  assert.equal(batch.data.KTB_3Y?.value, 100)
})

function seriesFor(points: MarketSeriesResponse['points']): ApiEnvelope<MarketSeriesResponse> {
  return {
    httpStatus: 'OK',
    isSuccess: true,
    message: '성공',
    code: 200,
    result: {
      code: 'KOSPI',
      name: 'KOSPI 지수',
      unit: 'pt',
      frequency: 'daily',
      points,
      retrievedAt: '2026-09-08T00:00:00Z',
      source: { provider: 'KRX', datasetName: 'test' },
      status: 'ok',
      statusMessage: null,
    },
  }
}

async function withSeries(body: ApiEnvelope<MarketSeriesResponse>) {
  const originalGet = apiClient.get
  let requested = ''
  apiClient.get = (async (endpoint: string) => {
    requested = endpoint
    return { data: body }
  }) as typeof apiClient.get
  try {
    return { series: await fetchMarketSeries('KOSPI'), requested }
  } finally {
    apiClient.get = originalGet
  }
}

test('최근 흐름은 지표별 series 경로에서 날짜 오름차순 값을 받는다', async () => {
  const { series, requested } = await withSeries(
    seriesFor([
      { date: '2026-09-04', value: 2480 },
      { date: '2026-09-07', value: 2510.5 },
    ]),
  )
  assert.equal(requested, '/markets/kospi/series')
  assert.deepEqual(
    series.points.map((point) => point.value),
    [2480, 2510.5],
  )
})

test('날짜가 뒤섞이거나 값이 숫자가 아닌 흐름은 받지 않는다', async () => {
  await assert.rejects(
    withSeries(
      seriesFor([
        { date: '2026-09-07', value: 2510 },
        { date: '2026-09-04', value: 2480 },
      ]),
    ),
  )
  await assert.rejects(
    withSeries(
      seriesFor([
        { date: '2026-09-04', value: 2480 },
        { date: '2026-09-07', value: Number.NaN },
      ]),
    ),
  )
})

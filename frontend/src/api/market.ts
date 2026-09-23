import { apiClient } from './client'
import type {
  ApiEnvelope,
  MarketIndicatorBatch,
  MarketIndicatorCode,
  MarketIndicatorResponse,
} from '@/types/marketApi'

const indicatorEndpoints: Record<MarketIndicatorCode, string> = {
  KOSPI: '/markets/kospi',
  KOSDAQ: '/markets/kosdaq',
  USD_KRW: '/markets/usd-krw',
  VKOSPI200: '/markets/vkospi200',
  KTB_3Y: '/markets/ktb-3y',
  COINCIDENT_CYCLE: '/markets/coincident-cycle',
  LEADING_CYCLE: '/markets/leading-cycle',
  ESI_CYCLE: '/markets/esi-cycle',
  CORE_CPI_YOY: '/markets/core-cpi-yoy',
  EXPORTS_USD: '/markets/exports-usd',
  M2: '/markets/m2',
}

export const marketIndicatorCodes = Object.keys(indicatorEndpoints) as MarketIndicatorCode[]

/** 지표별 최신값 API를 호출하고 응답 코드가 요청 지표와 일치하는지 확인한다. */
async function fetchMarketIndicator(code: MarketIndicatorCode) {
  const response = await apiClient.get<ApiEnvelope<MarketIndicatorResponse>>(
    indicatorEndpoints[code],
  )
  const body = response.data

  if (!body.isSuccess || !body.result || body.result.code !== code) {
    throw new Error(`${code} 지표 응답 형식이 올바르지 않습니다.`)
  }

  if (!Number.isFinite(body.result.value)) {
    throw new Error(`${code} 지표 값이 숫자가 아닙니다.`)
  }

  return body.result
}

/**
 * 한 원천기관의 실패가 다른 지표를 가리지 않도록 요청 결과를 독립적으로 모은다.
 *
 * 필요한 지표만 고를 수 있게 목록을 받는다. 참고 정보 영역 하나 때문에 11개를 모두
 * 부르면 그 영역의 지연이 다른 화면까지 끌고 간다.
 */
export async function fetchMarketIndicatorsFor(
  codes: readonly MarketIndicatorCode[],
): Promise<MarketIndicatorBatch> {
  const settled = await Promise.allSettled(codes.map(fetchMarketIndicator))
  const batch: MarketIndicatorBatch = { data: {}, errors: {} }

  settled.forEach((result, index) => {
    const code = codes[index]
    if (result.status === 'fulfilled') {
      batch.data[code] = result.value
      return
    }

    batch.errors[code] = result.reason instanceof Error
      ? result.reason.message
      : '지표를 불러오지 못했습니다.'
  })

  return batch
}

/** 대시보드가 쓰는 11개 전부다. */
export async function fetchLatestMarketIndicators(): Promise<MarketIndicatorBatch> {
  return fetchMarketIndicatorsFor(marketIndicatorCodes)
}

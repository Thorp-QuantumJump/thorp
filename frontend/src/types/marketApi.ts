export type MarketIndicatorCode =
  | 'KOSPI'
  | 'KOSDAQ'
  | 'USD_KRW'
  | 'VKOSPI200'
  | 'KTB_3Y'
  | 'COINCIDENT_CYCLE'
  | 'LEADING_CYCLE'
  | 'ESI_CYCLE'
  | 'CORE_CPI_YOY'
  | 'EXPORTS_USD'
  | 'M2'

export interface ApiEnvelope<T> {
  httpStatus: string
  isSuccess: boolean
  message: string
  code: number
  result: T
}

export interface MarketIndicatorResponse {
  code: MarketIndicatorCode
  name: string
  group: 'market' | 'macro'
  value: number
  unit: string
  frequency: 'daily' | 'monthly'
  observationDate: string
  retrievedAt: string
  source: {
    provider: string
    datasetName: string
  }
  status: 'ok' | 'stale'
  statusMessage: string | null
}

export interface MarketIndicatorBatch {
  data: Partial<Record<MarketIndicatorCode, MarketIndicatorResponse>>
  errors: Partial<Record<MarketIndicatorCode, string>>
}

/** 최근 흐름을 주는 일별 지표. 첫 화면 차트가 쓴다. */
export type MarketSeriesCode = 'KOSPI' | 'KOSDAQ' | 'USD_KRW' | 'VKOSPI200'

/** `GET /markets/{slug}/series`. `points`는 날짜 오름차순이다(market-api.md "최근 흐름 API"). */
export interface MarketSeriesResponse {
  code: MarketSeriesCode
  name: string
  unit: string
  frequency: 'daily'
  points: { date: string; value: number }[]
  retrievedAt: string
  source: {
    provider: string
    datasetName: string
  }
  status: 'ok' | 'stale'
  statusMessage: string | null
}

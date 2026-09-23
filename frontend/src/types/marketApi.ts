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

export type DataFrequency = 'daily' | 'monthly' | 'quarterly' | 'event'

export type IndicatorCategory =
  | 'growth'
  | 'inflation'
  | 'liquidity'
  | 'sentiment'
  | 'market'
  | 'stability'

export type IndicatorTone = 'positive' | 'negative' | 'warning' | 'neutral'

export type IndicatorDataStatus = 'example' | 'loading' | 'live' | 'stale' | 'error'

export interface DataSource {
  provider: string
  datasetName: string
  frequency: DataFrequency
  delayDescription?: string
  isBackfilled?: boolean
}

export interface IndicatorSeriesPoint {
  date: string
  value: number
}

export interface Indicator {
  id: string
  code: string
  name: string
  category: IndicatorCategory
  value: number
  unit: string
  decimals: number
  change: number | null
  changeUnit?: string
  direction: 'up' | 'down' | 'flat'
  tone: IndicatorTone
  interpretation: string
  observationDate: string
  releaseDate?: string
  availableAt?: string
  retrievedAt: string
  revisionStatus?: 'initial' | 'revised' | 'backfilled'
  source: DataSource
  series: IndicatorSeriesPoint[]
  dataStatus?: IndicatorDataStatus
  statusMessage?: string | null
}

export interface MarketData {
  marketIndicators: Indicator[]
  macroIndicators: Indicator[]
}

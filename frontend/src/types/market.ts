export type DataFrequency = 'daily' | 'monthly' | 'quarterly' | 'event'

export type IndicatorCategory =
  | 'growth'
  | 'inflation'
  | 'liquidity'
  | 'sentiment'
  | 'market'
  | 'stability'

export type IndicatorTone = 'positive' | 'negative' | 'warning' | 'neutral'

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
}

export interface MarketCyclePoint {
  date: string
  value: number
}

export interface MarketOverviewChart {
  id: string
  code: string
  tabLabel: string
  name: string
  insight: string
  axisLabel: string
  valueDecimals: number
  valueUnit: string
  sourceLabel: string
  yDomain: [number, number]
  yTicks: number[]
  referenceValue?: number
  referenceLabel?: string
  series: MarketCyclePoint[]
}

export interface MarketRegime {
  cycle: 'recovery' | 'expansion' | 'slowdown' | 'contraction'
  inflation: 'easing' | 'neutral' | 'pressure'
  risk: 'riskOn' | 'neutral' | 'riskOff'
  headline: string
  summary: string
  drivers: string[]
  overviewCharts: MarketOverviewChart[]
  asOf: string
  isMock: boolean
}

export interface SimilarMarketComparison {
  indicatorId: string
  currentValue: number
  historicalValue: number
  unit: string
}

export interface SimilarMarketPeriod {
  periodStart: string
  periodEnd: string
  comparisonWindowMonths: number
  similarityScore: number
  events: string[]
  similarities: string[]
  differences: string[]
  futureReturn3m: number
  futureReturn6m: number
  comparisons: SimilarMarketComparison[]
}

export interface StrategyRecommendation {
  id: string
  name: string
  suitability: number
  reason: string
  risk: string
  backtestPath: string
}

export interface DataFreshnessItem {
  id: string
  provider: string
  coverage: string
  frequency: DataFrequency
  status: 'example' | 'api-ready' | 'review-needed'
  note: string
}

export interface MarketData {
  regime: MarketRegime
  marketIndicators: Indicator[]
  macroIndicators: Indicator[]
  stabilityIndicators: Indicator[]
  similarPeriod: SimilarMarketPeriod
  strategyRecommendations: StrategyRecommendation[]
  dataFreshness: DataFreshnessItem[]
}

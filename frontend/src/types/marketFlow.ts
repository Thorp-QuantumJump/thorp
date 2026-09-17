export type MarketFlowFieldId =
  | 'production'
  | 'consumption_investment'
  | 'external_trade'
  | 'labor'
  | 'prices_commodities'
  | 'finance_housing'
  | 'overseas'

export type FieldStatus = 'READY' | 'EXAMPLE' | 'EMPTY' | 'COLLECTION_FAILED'

export interface FieldSummary {
  fieldId: MarketFlowFieldId
  displayName: string
  displayOrder: number
  status: FieldStatus
  narrative: string | null
  narrativeSubjects: string[]
  referenceMonth: string | null
  referenceMonthHeldBackBy: string[]
  lastUpdatedAt: string | null
  isExampleData: boolean
}

export interface FieldSummaryResult {
  registryVersion: string
  datasetVersion: string
  narrativeRuleVersion: string
  historicalComparisonNotice: string
  fields: FieldSummary[]
}

export interface PathPoint {
  month: string
  value: number | null
}

export interface AfterReferenceMonthRelease {
  referenceMonth: string
  value: number
  unit: string
}

export interface IndicatorDisplay {
  indicatorId: string
  name: string
  unit: string
  value: number | null
  path: [PathPoint, PathPoint, PathPoint]
  referenceMonth: string
  releaseDate: string | null
  source: string
  agency: string
  afterReferenceMonthRelease: AfterReferenceMonthRelease | null
}

export interface FieldDetailResult extends FieldSummary {
  multipleReferenceMonthNotice: string
  indicators: IndicatorDisplay[]
}

export interface MarketFlowSuccessEnvelope<T> {
  httpStatus: 'OK'
  isSuccess: true
  message: string
  code: 200
  result: T
}

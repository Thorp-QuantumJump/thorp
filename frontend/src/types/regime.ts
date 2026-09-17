/** spec 010 조회 계약. 계산에 쓰이는 decimal은 응답 그대로 문자열로 보관한다. */
export type RegimeMode = 'FIELD' | 'COMPOSITE'
export type RegimeStatus = 'NOT_COMPUTABLE' | 'NEAREST_ONLY' | 'THRESHOLD_APPLIED'
export type RegimeStatusReason =
  | 'SCALE_WINDOW_TOO_SHORT'
  | 'SCALE_ZERO'
  | 'QUERY_INPUT_MISSING'
  | 'NO_CANDIDATE'
  | 'THRESHOLD_NOT_CALIBRATED'
  | string

export interface RegimeField {
  id: string
  name: string
}

export interface RegimeComparisonMonths {
  indicatorId: string
  indicator: string | null
  months: string[]
  omitted: string[]
  note?: string
}

export interface RegimeDataAsOf {
  indicators: {
    indicatorId: string
    indicator: string | null
    targetMonth: string | null
    publishedAt: string | null
  }[]
  note: string
  revisions: {
    indicatorId: string
    indicator: string | null
    month: string | null
    revisedAt: string | null
  }[]
  newerReleases: {
    indicatorId: string
    indicator: string | null
    month: string | null
    publishedAt: string | null
  }[]
}

export interface RegimeCaseSummary {
  endMonth: string
  rank: number
  observationMonths: string[]
  distance: string
  similar: string[]
  different: string[]
  detailAvailable: boolean
}

export interface RegimeSearchResult {
  searchId: string
  mode: RegimeMode
  field: RegimeField | null
  baselineMonth: string
  baselineOrigin: 'T_G' | 'T_ALL' | 'USER_SPECIFIED'
  observationMonths: string[]
  comparisonMonths: RegimeComparisonMonths[]
  status: RegimeStatus
  statusReason: RegimeStatusReason | null
  statusNote: string
  caseLabel: string | null
  displayLimitNote: string | null
  basis: { kind: 'HISTORICAL_REVISION'; note: string }
  dataAsOf: RegimeDataAsOf
  versions: {
    data: string
    registry: string
    model: string
    revision: number
    sampleData: boolean
    sampleRegistry: boolean
  }
  counts: { evaluated: number; eligible: number; stored: number; displayed: number }
  cases: RegimeCaseSummary[]
}

export interface RegimeCaseDetail {
  searchId: string
  endMonth: string
  observationMonths: string[]
  distance: string
  byField: Record<string, string>
  contribution?: Record<string, string>
  indicators: {
    id: string
    name: string
    unit: string
    comparisonMonths: string[]
    caseMonths: string[]
    queryRaw: string[]
    caseRaw: string[]
    queryZ: string[]
    caseZ: string[]
    e: string
    nominalWeight: string
    distanceShare?: string
    scale: {
      median: string
      spread: string
      method: string
      windowStart: string
      windowEnd: string
      months: number
    } | null
  }[]
  eraPosition: Record<string, {
    sentence: string
    method: string
    windowStart: string
    windowEnd: string
    months: number
  }> | null
  postPeriod: { endMonth: string; available: boolean; note: string }
}

export interface RegimeCandidates {
  searchId: string
  candidates: { endMonth: string; distance: string; rank: number }[]
}

export interface RegimeBaselineMonths {
  byField: (RegimeField & { baselineMonth: string })[]
  composite: string | null
  range: { earliest: string | null; latest: string | null } | null
  note: string
}

export interface RegimeSearchQuery {
  mode: 'field' | 'composite'
  field?: string
  baselineMonth?: string
  limit?: 3 | 10
}

export type RegimeContextQuery =
  | { searchId: string }
  | { mode: 'field' | 'composite'; field?: string; baselineMonth?: string }

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
  note: string | null
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
  /** v3 종합에서 블록 안 엇갈림이 컸던 사례. v1 결과는 늘 false다. */
  unusual?: boolean
  /** 특이 사례의 관측 구간 안 공식 발간물·사건. 검색 입력이 아니다. 없으면 키가 없다. */
  context?: RegimeContextLink[]
  detailAvailable: boolean
}

export interface RegimeContextLink {
  kind: 'KDI_TRENDS' | 'BASE_RATE' | 'CYCLE_TURN'
  date: string
  title: string
  url?: string
  source: string
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
  /** 지금 시점의 블록 안 엇갈림 안내(v3 종합). 없으면 null이다. */
  phaseNote?: string | null
  cases: RegimeCaseSummary[]
  /** 교정된 결과(THRESHOLD_APPLIED)에서 기준 충족 사례가 없을 때의 안내. */
  thresholdNote?: string | null
  /** 교정된 결과의 미충족 참고 사례 목록 이름과 사례(spec FR-021). 그 밖에는 키가 없다. */
  referenceLabel?: string | null
  referenceCases?: RegimeCaseSummary[] | null
}

export interface RegimeCaseDetail {
  searchId: string
  endMonth: string
  observationMonths: string[]
  distance: string
  byField: Record<string, string>
  contribution?: Record<string, string>
  /** v3 종합의 경제 블록 거리. 있으면 byField가 비어 있다. */
  blocks?: { id: string; name: string; gap: string; contribution?: string; spread: string }[]
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

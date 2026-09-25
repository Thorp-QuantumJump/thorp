import type { RegimeCaseDetail, RegimeSearchResult } from '@/types/regime'

export function regimeResult(overrides: Partial<RegimeSearchResult> = {}): RegimeSearchResult {
  return {
    searchId: 'search-1', mode: 'FIELD', field: { id: 'field-1', name: '시험 분야' },
    baselineMonth: '2026-07', baselineOrigin: 'T_G',
    observationMonths: ['2026-05', '2026-06', '2026-07'], comparisonMonths: [],
    status: 'NEAREST_ONLY', statusReason: 'THRESHOLD_NOT_CALIBRATED',
    statusNote: '가까운 순서만 제공합니다. 유사성은 아직 판정하지 않습니다.',
    caseLabel: '최근접 참고 사례', displayLimitNote: null,
    basis: { kind: 'HISTORICAL_REVISION', note: '최신 개정자료를 이용한 역사 비교입니다.' },
    dataAsOf: {
      indicators: [{ indicatorId: 'indicator-1', indicator: '시험 지표', targetMonth: '2026-07', publishedAt: '2026-09-12' }],
      note: '시험 분야 지표는 2026년 7월분까지 반영되어 있습니다.', revisions: [], newerReleases: [],
    },
    versions: { data: 'FIXTURE-v1', registry: 'r1', model: 'm1', revision: 1, sampleData: true, sampleRegistry: true },
    counts: { evaluated: 1, eligible: 1, stored: 1, displayed: 1 },
    cases: [{
      endMonth: '2012-03', rank: 1, observationMonths: ['2012-01', '2012-02', '2012-03'],
      distance: '0.412938571034', similar: ['시험 지표가 비슷합니다.'],
      different: ['다른 움직임이 있습니다.'], detailAvailable: true,
    }],
    ...overrides,
  }
}

export function regimeDetail(overrides: Partial<RegimeCaseDetail> = {}): RegimeCaseDetail {
  return {
    searchId: 'search-1', endMonth: '2012-03', observationMonths: ['2012-01', '2012-02', '2012-03'],
    distance: '0.412938571034', byField: { 'field-1': '0.412938571034' },
    indicators: [{
      id: 'indicator-1', name: '시험 지표', unit: '%',
      comparisonMonths: ['2026-05'], caseMonths: ['2012-01'],
      queryRaw: ['3.1'], caseRaw: ['3.2'], queryZ: ['-0.590178531422'], caseZ: ['-0.421556093873'],
      e: '0.168622437549', nominalWeight: '0.500000000000', distanceShare: '0.204173755344',
      scale: { median: '3.45', spread: '0.593040887402', method: 'MAD', windowStart: '2016-05', windowEnd: '2026-04', months: 120 },
    }],
    eraPosition: { 'indicator-1': { sentence: '시험 지표는 당시 평소보다 높은 편이었습니다.', method: 'MAD', windowStart: '2002-01', windowEnd: '2011-12', months: 120 } },
    postPeriod: { endMonth: '2012-03', available: false, note: '사례 이후 결과는 아직 연결되지 않았습니다.' },
    ...overrides,
  }
}


/** 숫자가 `<Num>`으로 나뉘어도 요소 하나의 전체 글자로 찾는다(S15P21E205-447). */
export const fullText = (text: string) => (_: string, element: Element | null) =>
  element?.textContent === text && ![...element.children].some((child) => child.textContent === text)

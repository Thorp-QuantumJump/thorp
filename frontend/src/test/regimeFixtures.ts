import type { RegimeSearchResult } from '@/types/regime'

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

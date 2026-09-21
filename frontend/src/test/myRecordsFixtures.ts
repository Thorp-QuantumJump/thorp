import type { MyRecordDetail, MyRecordListItem, MyRecordListPage } from '@/types/myRecords'

type AnalysisItem = Extract<MyRecordListItem, { kind: 'ANALYSIS' }>
type RegimeItem = Extract<MyRecordListItem, { kind: 'REGIME_SEARCH' }>

/** contracts/my-records-api.md 의 예시를 옮긴 것이다. 성과 수치 필드는 계약에 없어 여기도 없다. */

export const analysisRecord: AnalysisItem = {
  recordId: 'MR-20260918-000042',
  kind: 'ANALYSIS',
  name: '반도체 외 2 섹터 모멘텀 2026-09-18',
  sectorCodes: ['SEMICONDUCTOR', 'BATTERY', 'SHIPBUILDING'],
  strategyIds: ['SECTOR_MOMENTUM'],
  period: { start: '2019-01-02', end: '2026-08-29' },
  savedAt: '2026-09-18T01:12:03Z',
  status: 'COMPLETED',
  statusReason: null,
  resultToken: '3f1c9a7b4e0d83526af1c9d0b7e34512',
  isCopy: false,
}

export const bundleRecord: MyRecordListItem = {
  recordId: 'MR-20260917-000031',
  kind: 'SEARCH_BUNDLE',
  name: '은행 역변동성 탐색 2026-09-17',
  sectorCodes: ['BANK'],
  strategyIds: ['INVERSE_VOLATILITY'],
  period: { start: '2019-01-02', end: '2026-08-29' },
  savedAt: '2026-09-17T08:40:00Z',
  status: 'PARTIAL',
  statusReason: 'TIME_LIMIT',
  searchId: 'PS-20260917-000009',
  isCopy: false,
}

export const regimeRecord: RegimeItem = {
  recordId: 'MR-20260916-000007',
  kind: 'REGIME_SEARCH',
  name: '고용환경 2026-08 2026-09-16',
  sectorCodes: [],
  strategyIds: [],
  period: null,
  savedAt: '2026-09-16T22:05:11Z',
  status: 'THRESHOLD_APPLIED',
  statusReason: null,
  regime: {
    snapshotKey: 'FIELD:EMPLOYMENT:2026-08-01:MACRO-20260915:REG-3:REGIME-V1',
    mode: 'FIELD',
    fieldId: 'EMPLOYMENT',
    baselineMonth: '2026-08-01',
    baselineOrigin: 'T_G',
    entryPath: 'MARKET_FLOW',
    withdrawn: false,
  },
  isCopy: false,
}

export const withdrawnRegimeRecord: RegimeItem = {
  ...regimeRecord,
  recordId: 'MR-20260915-000003',
  name: '고용환경 2026-07 2026-09-15',
  status: 'WITHDRAWN',
  regime: { ...regimeRecord.regime, withdrawn: true },
}

export const listPage: MyRecordListPage = {
  items: [analysisRecord, bundleRecord, regimeRecord],
  nextCursor: null,
}

export const analysisDetail: MyRecordDetail = {
  ...analysisRecord,
  lastViewedAt: '2026-09-18T03:20:00Z',
  referenceVersions: {
    datasetVersion: 'DS-2026-09-17-001',
    sectorEtfMapVersion: 'SEM-2026-09-16-001',
    costProfileVersion: { version: 'CP-2026-09-17-001', provisional: true },
    rulesVersion: { version: '1', provisional: false },
    engineVersion: 'worker-0.9.3',
  },
  mappingDrift: {
    changed: true,
    kind: 'INSTRUMENT_REPLACED',
    sectorCodes: ['BATTERY'],
    message: '2차전지 섹터의 대표 ETF가 저장 당시와 다릅니다.',
  },
}

/** 판정할 근거가 없어 `mappingDrift` 키가 없는 상세. `changed: false`와 다르다. */
export const analysisDetailUnjudged: MyRecordDetail = {
  ...analysisRecord,
  lastViewedAt: null,
  referenceVersions: { datasetVersion: 'DS-2026-09-17-001' },
}

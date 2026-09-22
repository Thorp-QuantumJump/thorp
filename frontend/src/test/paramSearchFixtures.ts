import type {
  ParamSearchBaseline,
  ParamSearchConfig,
  ParamSearchDetail,
  ParamSearchPreview,
  ParamSearchSelection,
  SearchCandidateItem,
} from '@/types/paramSearch'

/**
 * 007 화면 테스트용 응답 픽스처.
 *
 * <p><b>등록표에 없는 전략과 축을 일부러 쓴다.</b> 실제 전략 이름으로 픽스처를 만들면 화면이 그
 * 전략을 알고 있어도 테스트가 통과한다 — 이름을 지어내야 "응답만 보고 그린다"가 증명된다(SC-010).
 * 픽스처에 전략 데이터를 두는 것은 FR-025의 명시적 예외다.
 */
export const previewFixture: ParamSearchPreview = {
  registryVersion: 'TEST-REGISTRY-1',
  strategies: [
    { strategyId: 'ALPHA_TILT', name: '알파 기울기', executable: true },
    { strategyId: 'CARRY_ROTATION', name: '캐리 순환', executable: false },
  ],
  spaceSummary: [
    {
      strategyId: 'ALPHA_TILT',
      strategyName: '알파 기울기',
      axes: [
        {
          axis: 'tiltWindow',
          label: '기울기 관측 창',
          unit: '거래일',
          values: [5, 10, 20],
          valueLabels: ['5', '10', '20'],
          defaultValue: 10,
          allowedRange: { min: 5, max: 60, step: 5, values: null },
        },
        /**
         * **후보값이 객체인 축.** 등록표에는 이런 축이 실제로 있고(리밸런싱 주기의 `{mode, unit}`),
         * 값만으로는 그릴 수 없어 화면에 `[object Object]`가 나왔다. 후보값을 전부 숫자·문자열로
         * 둔 틀로는 그 회귀를 잡지 못하므로 여기 하나를 객체로 둔다.
         */
        {
          axis: 'rebalanceMode',
          label: '재조정 방식',
          unit: null,
          values: [
            { mode: 'CALENDAR', unit: 'MONTH_END' },
            { mode: 'CALENDAR', unit: 'QUARTER_END' },
          ],
          valueLabels: ['월말', '분기말'],
          defaultValue: { mode: 'CALENDAR', unit: 'MONTH_END' },
          allowedRange: null,
        },
      ],
      combinations: 6,
    },
    {
      strategyId: 'CARRY_ROTATION',
      strategyName: '캐리 순환',
      axes: [],
      combinations: 1,
    },
  ],
  workload: {
    totalCombinations: 8,
    validCombinations: 7,
    excludedCombinations: 1,
    exclusionReasons: [
      { code: 'WINDOW_TOO_SHORT', message: '관측 창이 자료 구간보다 깁니다.', count: 1 },
    ],
  },
  limits: {
    maxCandidates: 100,
    timeLimitSeconds: 1800,
    dispatchWindow: 4,
    consecutiveFailureLimit: 5,
    provisional: ['maxCandidates', 'dispatchWindow'],
    exceeded: false,
  },
  commonPeriod: { start: '2015-12-22', end: '2026-09-14', shortenedBy: null },
  validationLabel: 'UNVALIDATED',
  validationNote: '평가 분할이 등록되지 않아 결과는 모두 미검증으로 표시됩니다',
}

/** 등록표가 바뀐 뒤의 미리보기. 후보값과 조합 수가 달라진다. */
export const changedPreviewFixture: ParamSearchPreview = {
  ...previewFixture,
  registryVersion: 'TEST-REGISTRY-2',
  spaceSummary: [
    {
      ...previewFixture.spaceSummary[0],
      axes: [
        { ...previewFixture.spaceSummary[0].axes[0], values: [5, 10] },
        previewFixture.spaceSummary[0].axes[1],
      ],
      combinations: 4,
    },
    previewFixture.spaceSummary[1],
  ],
  workload: { ...previewFixture.workload, totalCombinations: 5, validCombinations: 5 },
}

/** 접수 직후 응답. 뜻이 있는 값은 searchId·status 와 검증 표시뿐이다. */
export const acceptedFixture: ParamSearchDetail = {
  searchId: 'PS-20260914-000001',
  status: 'QUEUED',
  stopReason: null,
  counts: null,
  partial: false,
  partialNote: null,
  config: null,
  baseline: null,
  validationLabel: 'UNVALIDATED',
  validationNote: '평가 분할이 등록되지 않아 결과는 모두 미검증으로 표시됩니다',
  selection: null,
  startedAt: null,
  finishedAt: null,
}

/** 상태 응답의 config. 축 라벨·전략 이름을 여기서 읽는다 — 화면이 갖고 있지 않다. */
export const configFixture: ParamSearchConfig = {
  strategyIds: ['ALPHA_TILT'],
  instrumentIds: ['TEST.XKRX'],
  registryVersion: 'TEST-REGISTRY-1',
  searchMethod: 'FULL_GRID',
  randomSeed: null,
  space: {
    ALPHA_TILT: {
      strategyName: '알파 기울기',
      axes: [
        { name: 'tiltWindow', label: '기울기 관측 창', unit: '거래일', values: [5, 10, 20] },
      ],
    },
  },
  selectionRule: {
    drawdownFilter: { type: 'NOT_WORSE_THAN_BASELINE', baseline: 'CARRY_ROTATION' },
    stabilityFilter: { type: 'BATCH_PERCENTILE', keepTopPercent: 50 },
    sortMetric: 'NET_RETURN',
    displayCount: 5,
    tieHandling: 'SHOW_ALL_TIED',
    provisional: ['displayCount'],
  },
  limits: null,
  referenceVersions: null,
  dataVersionChanged: false,
}

/** 결과가 있는 후보. 세 숫자가 모두 채워져 있다. */
export const completedCandidate: SearchCandidateItem = {
  candidateId: 'PS-20260914-000001-C001',
  candidateNo: 1,
  strategyId: 'ALPHA_TILT',
  parameters: { tiltWindow: 5 },
  status: 'COMPLETED',
  backtestId: 'BT-20260914-000001',
  netReturn: '0.300000',
  maxDrawdown: '-0.100000',
  tradingCost: '17078.49',
  stability: { calculable: true, value: '0.05000000', neighborCount: 1 },
  retryCount: 0,
  validationLabel: 'UNVALIDATED',
  selected: true,
  selectionReason: { outcome: 'SELECTED', tied: false, passedFilters: ['DRAWDOWN', 'STABILITY'] },
}

/** 이웃이 없어 안정성을 말할 수 없는 후보. **나쁜 값이 아니라 없는 값이다.** */
export const noNeighborCandidate: SearchCandidateItem = {
  ...completedCandidate,
  candidateId: 'PS-20260914-000001-C002',
  candidateNo: 2,
  parameters: { tiltWindow: 10 },
  netReturn: '0.200000',
  stability: { calculable: false, value: null, neighborCount: 0 },
  selectionReason: { outcome: 'SELECTED', tied: false, passedFilters: ['STABILITY'] },
}

/** 계산 전에 제외된 후보. 성과가 전부 null 이고 **0이 아니다**(FR-011). */
export const excludedCandidate: SearchCandidateItem = {
  candidateId: 'PS-20260914-000001-C003',
  candidateNo: 3,
  strategyId: 'ALPHA_TILT',
  parameters: { tiltWindow: 20 },
  status: 'EXCLUDED',
  backtestId: null,
  netReturn: null,
  maxDrawdown: null,
  tradingCost: null,
  stability: null,
  retryCount: 0,
  validationLabel: 'UNVALIDATED',
  selected: false,
  selectionReason: {
    outcome: 'EXCLUDED_BEFORE_RUN',
    code: 'WINDOW_TOO_SHORT',
    message: '관측 창이 자료 구간보다 깁니다.',
  },
}

/** 기준을 통과했으나 표시 범위 밖인 후보. **탈락이 아니다.** */
export const notInDisplayCandidate: SearchCandidateItem = {
  ...completedCandidate,
  candidateId: 'PS-20260914-000001-C004',
  candidateNo: 4,
  netReturn: '0.010000',
  selected: false,
  selectionReason: {
    outcome: 'NOT_IN_DISPLAY',
    passedFilters: ['DRAWDOWN', 'STABILITY'],
  },
}

export const selectionFixture: ParamSearchSelection = {
  displayed: [completedCandidate.candidateId, noNeighborCandidate.candidateId],
  shortOfDisplayCount: true,
  note: '기준을 충족한 후보가 2개입니다',
  drawdownFilterApplied: false,
}

export const baselineFixture: ParamSearchBaseline = {
  strategyId: 'CARRY_ROTATION',
  strategyName: '캐리 순환',
  backtestId: 'BT-20260914-000009',
  status: 'COMPLETED',
  netReturn: '0.100000',
  maxDrawdown: '-0.200000',
}

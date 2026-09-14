import type { ParamSearchDetail, ParamSearchPreview } from '@/types/paramSearch'

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
          defaultValue: 10,
          allowedRange: { min: 5, max: 60, step: 5, values: null },
        },
        {
          axis: 'rebalanceMode',
          label: '재조정 방식',
          unit: null,
          values: ['월말', '분기말'],
          defaultValue: '월말',
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
    maxCandidates: 120,
    timeLimitSeconds: 120,
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

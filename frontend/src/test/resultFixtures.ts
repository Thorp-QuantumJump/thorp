import type {
  PublicResultDetail,
  ResultEnvelope,
  ResultExceptions,
  ResultPositions,
  ResultTimeseries,
  ResultTimeseriesPoint,
  ResultTradePage,
  ResultWeights,
} from '@/types/result'
import type { InstrumentProfile, SectorCatalog } from '@/types/sector'

/**
 * 005 결과 화면 테스트용 픽스처.
 *
 * <p>모양은 계약 예시(`docs/specs/result-api/examples/*.json`)와 같고, `resultFixtures.test.ts`가 키 집합과
 * `status`·`validationTier`를 파일과 대조한다.
 *
 * <p><b>전략 이름과 식별자는 등록표에 없는 합성값이다.</b> 실제 전략 이름을 쓰면 화면이 그 전략을 알고
 * 있어도 테스트가 통과한다 — 지어낸 이름이 그대로 보여야 "응답만 보고 그린다"가 증명된다.
 * `strategyRegistryFixtures.ts`가 같은 이유로 같은 방식을 쓴다.
 *
 * <p>숫자와 버전 문자열은 계약 형식을 보이려는 값이다. 제품의 기준값이 아니다.
 */

export const resultToken = '9f2c1a7b4e0d83526af1c9d0b7e34512'

export const syntheticStrategyName = '물결 고르기'

export const resultDetailCompleted: PublicResultDetail = {
  publicToken: resultToken,
  status: 'COMPLETED',
  resultSource: 'NEW',
  partial: false,
  cancelRequested: false,
  expiresAt: '2026-09-23T04:11:00Z',
  progress: null,
  queuedAt: '2026-09-16T04:11:00Z',
  startedAt: '2026-09-16T04:11:03Z',
  finishedAt: '2026-09-16T04:12:41Z',
  queueWaitSeconds: null,
  config: {
    end: '2024-12-31',
    items: [
      { sectorId: 'HARBOR', instrumentId: '900001.XKRX', targetWeight: '0.4000' },
      { sectorId: 'LIGHTHOUSE', instrumentId: '900002.XKRX', targetWeight: '0.3000' },
      { sectorId: 'DOCKYARD', instrumentId: '900003.XKRX', targetWeight: '0.3000' },
    ],
    initialCash: '10000000.00',
    parameters: { tideWindow: 'MONTH_END' },
    mappingVersion: 'SECTOR-ETF-TEST-1',
    start: '2023-07-03',
    evaluationSplitVersion: 'SPLIT-1',
    warmupTradingDays: 0,
    scenario: 'FULL_PERIOD',
    rulesVersion: '1',
    strategyId: 'WAVE_LEVELING',
    currency: 'KRW',
    effectiveStart: '2023-07-03',
    effectiveEnd: '2024-12-31',
  },
  versions: {
    engine: 'engine-test / worker-test',
    rules: { version: '1', provisional: false },
    data: 'FIXTURE-test',
    evaluationSplit: 'SPLIT-1',
    costProfile: { version: 'PROVISIONAL-TEST-1', provisional: true },
    mapping: 'SECTOR-ETF-TEST-1',
  },
  summary: {
    initialEquity: '10000000.00',
    finalEquity: '11666521.51',
    currency: 'KRW',
    totalReturn: '0.166652',
    cagr: '0.366917',
    maxDrawdown: '-0.041757',
    sharpeRatio: '2.342630',
    volatility: '0.132307',
    tradeCount: 2,
    winRate: '0.500000',
    totalCommission: '3941.19',
    totalSlippageCost: '13137.30',
    netTotalReturn: '0.166652',
    totalTradingCost: '17078.49',
    costs: {
      commission: '3941.19',
      spread: '0.00',
      slippage: '13137.30',
      tax: '0.00',
      total: '17078.49',
    },
    liquidation: {
      markToMarket: { finalEquity: '11666521.51', totalReturn: '0.166652' },
      liquidated: {
        finalEquity: '11662525.96',
        totalReturn: '0.166253',
        liquidationCost: '3995.55',
      },
    },
    analytics: { recoveryDays: 9, turnover: '1.219991' },
    exceptionCount: 1,
  },
  summaryUnavailable: null,
  failure: null,
  validationTier: 'UNVALIDATED',
  strategyName: syntheticStrategyName,
  weightsSource: 'COMPUTED',
  exceptionKinds: ['HALT_DEFERRED'],
}

export const resultDetailRunning: PublicResultDetail = {
  ...resultDetailCompleted,
  status: 'RUNNING',
  progress: {
    stage: 'COMPUTING',
    lastTradingDate: '2024-03-29',
    completedTradingDays: 180,
    totalTradingDays: 380,
  },
  finishedAt: null,
  queueWaitSeconds: 3,
  summary: null,
  weightsSource: null,
  exceptionKinds: null,
}

export const resultDetailCancelledNoSummary: PublicResultDetail = {
  ...resultDetailCompleted,
  status: 'CANCELLED',
  partial: true,
  startedAt: null,
  summary: null,
  summaryUnavailable: 'CANCELLED_BEFORE_START',
  weightsSource: null,
  exceptionKinds: null,
}

export const resultExpiredEnvelope: ResultEnvelope<PublicResultDetail> = {
  success: false,
  data: null,
  error: {
    code: 'RESULT_EXPIRED',
    message: '결과가 더 이상 남아 있지 않습니다. 같은 조건으로 다시 분석할 수 있습니다.',
  },
}

/** 거래일만 세는 날짜 열. 주말을 건너뛴다 */
function tradingDates(count: number, start = '2025-01-01'): string[] {
  const dates: string[] = []
  const cursor = new Date(`${start}T00:00:00Z`)
  while (dates.length < count) {
    const day = cursor.getUTCDay()
    if (day !== 0 && day !== 6) dates.push(cursor.toISOString().slice(0, 10))
    cursor.setUTCDate(cursor.getUTCDate() + 1)
  }
  return dates
}

/**
 * 고점과 저점이 정해진 합성 자산가치 계열.
 *
 * <p>`peakIndex`까지 오르고 `troughIndex`까지 내린 뒤 끝까지 조금씩 회복한다. `drawdown`은 그때까지의
 * 최고 자산가치 대비 비율이라 가장 낮은 값이 정확히 `troughIndex`에 온다. 음영 구간과 긴 계열(1,900점)
 * 테스트에 쓴다.
 */
export function makeEquitySeries({
  points,
  peakIndex,
  troughIndex,
  initialEquity = 10_000_000,
}: {
  points: number
  peakIndex: number
  troughIndex: number
  initialEquity?: number
}): ResultTimeseriesPoint[] {
  if (!(0 <= peakIndex && peakIndex < troughIndex && troughIndex < points)) {
    throw new Error('peakIndex < troughIndex < points 이어야 한다')
  }

  const dates = tradingDates(points)
  const peakEquity = initialEquity * 1.08
  const troughEquity = peakEquity * 0.95
  let runningMax = 0

  return dates.map((date, index) => {
    let equity: number
    if (index <= peakIndex) {
      equity = initialEquity + ((peakEquity - initialEquity) * index) / Math.max(peakIndex, 1)
    } else if (index <= troughIndex) {
      equity = peakEquity - ((peakEquity - troughEquity) * (index - peakIndex)) / (troughIndex - peakIndex)
    } else {
      // 고점을 넘지 않게 회복시켜 최저점이 하나로 남게 한다.
      const remaining = points - 1 - troughIndex
      equity = troughEquity + ((peakEquity - troughEquity) * 0.5 * (index - troughIndex)) / remaining
    }

    runningMax = Math.max(runningMax, equity)
    return {
      date,
      equity: equity.toFixed(2),
      cumulativeReturn: (equity / initialEquity - 1).toFixed(6),
      drawdown: (equity / runningMax - 1).toFixed(6),
    }
  })
}

export const resultTimeseries: ResultTimeseries = {
  partial: false,
  points: makeEquitySeries({ points: 129, peakIndex: 40, troughIndex: 55 }),
}

/*
 * 상세 패널(B07)이 여는 하위 자원. 모양은 004 예시(`docs/specs/backtest-api/examples/backtest-*.json`)에서
 * `backtestId`만 뺀 것이다(contracts "공개 응답에서 버리는 필드"). 종목은 `resultDetailCompleted.config.items`의
 * 합성 종목을 쓴다.
 */

export const resultWeights: ResultWeights = {
  partial: false,
  weightsSource: 'COMPUTED',
  points: [
    {
      date: '2023-07-03',
      target: { '900001.XKRX': '0.400000', '900002.XKRX': '0.300000' },
      actual: { '900001.XKRX': '0.398100', '900002.XKRX': '0.301200' },
      weightError: '0.003100',
    },
    {
      date: '2024-12-02',
      target: { '900001.XKRX': '0.450000', '900003.XKRX': '0.250000' },
      actual: { '900001.XKRX': '0.447300', '900003.XKRX': '0.251900' },
      weightError: '0.004600',
    },
  ],
}

export const resultExceptions: ResultExceptions = {
  partial: false,
  items: [
    {
      date: '2024-02-26',
      instrumentId: '900002.XKRX',
      kind: 'HALT_DEFERRED',
      rule: 'DEFER_TO_NEXT_TRADABLE',
      action: 'Order deferred to the next tradable session.',
    },
  ],
}

export const resultTradePage: ResultTradePage = {
  partial: false,
  page: 0,
  size: 50,
  totalElements: 2,
  totalPages: 1,
  content: [
    {
      tradeId: 'TRD-000001',
      instrumentId: '900001.XKRX',
      side: 'LONG',
      status: 'CLOSED',
      entryDate: '2023-07-03',
      exitDate: '2024-03-12',
      entryPrice: '53700.00',
      exitPrice: '52800.00',
      quantity: '90',
      grossPnl: '-81000.00',
      commission: '1437.75',
      slippageCost: '4792.50',
      realizedPnl: '-87230.25',
      returnRate: '-0.018049',
      reason: 'REBALANCE',
    },
    {
      tradeId: 'TRD-000002',
      instrumentId: '900003.XKRX',
      side: 'LONG',
      status: 'OPEN',
      entryDate: '2024-12-02',
      exitDate: null,
      entryPrice: '60000.00',
      exitPrice: null,
      quantity: '40',
      grossPnl: null,
      commission: '360.00',
      slippageCost: '1200.00',
      realizedPnl: null,
      returnRate: null,
      reason: 'REBALANCE',
    },
  ],
}

export const resultPositions: ResultPositions = {
  partial: false,
  requestedDate: '2025-03-24',
  snapshotDate: '2025-03-21',
  positions: [
    {
      instrumentId: '900001.XKRX',
      quantity: '90',
      marketValue: '4833000.00',
      weight: '0.483452',
      unrealizedPnl: '-12000.00',
    },
  ],
}

/** 001 `GET /sectors`의 합성 목록. 결과의 세 섹터와 같은 대표 ETF를 담는다 */
export const resultSectorCatalog: SectorCatalog = {
  mapVersion: 'SECTOR-ETF-TEST-1',
  isSample: true,
  sectors: [
    {
      code: 'HARBOR',
      name: '항구',
      classification: 'INDUSTRY',
      representativeEtf: {
        instrumentId: '900001.XKRX',
        name: '합성 항구 ETF',
        listedOn: '2015-01-02',
        benchmarkIndex: '합성 항구 지수',
      },
    },
    {
      code: 'LIGHTHOUSE',
      name: '등대',
      classification: 'INDUSTRY',
      representativeEtf: {
        instrumentId: '900002.XKRX',
        name: '합성 등대 ETF',
        listedOn: '2015-01-02',
        benchmarkIndex: '합성 등대 지수',
      },
    },
    {
      code: 'DOCKYARD',
      name: '조선소',
      classification: 'THEME',
      representativeEtf: {
        instrumentId: '900003.XKRX',
        name: '합성 조선소 ETF',
        listedOn: '2015-01-02',
        benchmarkIndex: '합성 조선소 지수',
      },
    },
  ],
}

/** 001 `GET /sectors/{code}/instrument-profile`의 합성 응답. 문구가 그대로 보이는지 확인하려고 지어낸 문장을 쓴다 */
export const resultInstrumentProfile: InstrumentProfile = {
  instrumentId: '900001.XKRX',
  name: '합성 항구 ETF',
  benchmarkIndex: '합성 항구 지수',
  indexMethodologySummary: '합성 항구 지수는 물때표 순서로 구성한다고 가정한 시험용 문장입니다.',
  listedOn: '2015-01-02',
  manager: '합성 운용',
  holdingsOverlap: {
    asOfCurrentComposition: true,
    topHoldings: [
      { name: '갈매기 조선', weight: '0.2431' },
      { name: '밀물 해운', weight: '0.2107' },
    ],
  },
  avgDailyValue3m: '1240000000',
  sourceAttribution: '시험용 출처 표기',
  survivorshipNote: '시험용 생존 편향 안내 문장입니다.',
  datasetId: 'KRX_DAILY_TEST',
  dataDeadline: '2026-09-07T09:00:00Z',
  isSample: true,
}

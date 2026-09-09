import type { Indicator, SimilarMarketPeriod } from '@/types/market'

/** API로 확인된 최신값만 비교 패널의 현재값에 반영하고 과거 예시값은 유지한다. */
export function withCurrentIndicatorValues(
  period: SimilarMarketPeriod,
  indicators: Indicator[],
): SimilarMarketPeriod {
  const indicatorsById = new Map(indicators.map((indicator) => [indicator.id, indicator]))

  return {
    ...period,
    comparisons: period.comparisons.map((comparison) => {
      const indicator = indicatorsById.get(comparison.indicatorId)
      if (!indicator || (indicator.dataStatus !== 'live' && indicator.dataStatus !== 'stale')) {
        return comparison
      }

      return {
        ...comparison,
        currentValue: indicator.value,
        unit: indicator.unit,
      }
    }),
  }
}

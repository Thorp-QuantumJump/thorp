import type { Indicator, IndicatorSeriesPoint } from '@/types/market'

// 실제 과거 시세가 아닌 UI 검증용 연간 기준값이다. 마지막 6개월은 기존 mock을 유지한다.
const historyAnchors: Record<string, number[]> = {
  kospi: [3120, 2380, 2510, 2650, 2580],
  kosdaq: [1020, 760, 880, 820, 790],
  'usd-krw': [1160, 1390, 1280, 1370, 1410],
  'v-kospi-200': [18, 30, 21, 16, 24],
  'ktb-3y': [1.4, 4.2, 3.6, 3.2, 2.8],
  'coincident-cycle': [101.2, 102.1, 100.8, 99.2, 100.3],
  'leading-cycle': [102.7, 100.8, 98.2, 99.4, 100.1],
  'esi-cycle': [108, 94, 87, 101, 92],
  'core-cpi': [1.2, 3.6, 4.1, 3.1, 2.7],
  'exports-usd': [
    64_000_000_000,
    58_000_000_000,
    52_000_000_000,
    57_000_000_000,
    59_000_000_000,
  ],
  m2: [3_450_000, 3_620_000, 3_780_000, 3_930_000, 4_020_000],
}

/** 고정된 기준값 사이를 보간해 60개월 예시 이력을 만들며 실제 관측값을 생성하지 않는다. */
export function withIndicatorHistoryMock(indicator: Indicator): Indicator {
  const anchors = historyAnchors[indicator.id]
  const firstRecent = indicator.series[0]
  if (!anchors || !firstRecent) return indicator

  const [year, month] = indicator.observationDate.split('-').map(Number)
  const historicalMonths = 60 - indicator.series.length
  const values = [...anchors, firstRecent.value]
  const amplitude = (Math.max(...values) - Math.min(...values)) * 0.055
  const history: IndicatorSeriesPoint[] = Array.from({ length: historicalMonths }, (_, index) => {
    const position = index / historicalMonths * (values.length - 1)
    const segment = Math.floor(position)
    const fraction = position - segment
    const value = values[segment] + (values[segment + 1] - values[segment]) * fraction
      + Math.sin(fraction * Math.PI) * Math.sin(index * 1.7) * amplitude
    const date = new Date(Date.UTC(year, month - 60 + index, 1)).toISOString().slice(0, 7)
    return { date, value: Number(value.toFixed(indicator.decimals)) }
  })

  return { ...indicator, series: [...history, ...indicator.series] }
}

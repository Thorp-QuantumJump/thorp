import type { IndicatorDisplay } from '@/types/marketFlow'

/* 첫 화면 시장 흐름 창(MarketFlowPreview)의 값·변화 표시. */

export type Direction = 'up' | 'down' | undefined

/** 퍼센트로 나타낸 지표다. 변화를 비율이 아니라 %p 차이로 적는다. */
const PERCENT_UNITS = new Set(['%', '연%'])

/** 음수 기호는 변화 표시와 같은 빼기표(−)로 적는다. */
function formatNumber(value: number, maximumFractionDigits: number) {
  return value.toLocaleString('ko-KR', { maximumFractionDigits }).replace('-', '−')
}

/** 크기에 맞춰 소수 자리를 정한다. 큰 값에 소수를 붙이면 읽을 것 없는 숫자만 길어진다. */
function digitsFor(value: number) {
  const size = Math.abs(value)
  return size >= 1000 ? 0 : size >= 100 ? 1 : 2
}

/**
 * 값과 단위를 사람이 읽는 말로 옮긴다. '십억원'·'백만달러'처럼 원천이 쓰는 셈 단위를 그대로 적지 않고
 * 조·억 단위로 바꾼다. 계산은 받은 값으로만 하고, 반올림한 값으로 방향을 정하지 않는다.
 */
export function formatFlowValue(value: number, unit: string): string {
  const trimmed = unit.trim()
  if (PERCENT_UNITS.has(trimmed)) return `${formatNumber(value, 2)}%`
  if (trimmed === '원') return `${formatNumber(value, digitsFor(value))}원`
  if (trimmed === '십억원') {
    return Math.abs(value) >= 1000 ? `${formatNumber(value / 1000, 1)}조 원` : `${formatNumber(value * 10, 0)}억 원`
  }
  const dollars =
    trimmed === '미국 달러' || trimmed === '달러' || trimmed === 'USD'
      ? value
      : trimmed === '백만달러' || trimmed === '백만 달러'
        ? value * 1_000_000
        : trimmed === '천달러' || trimmed === '천 달러'
          ? value * 1000
          : null
  if (dollars !== null) {
    return Math.abs(dollars) >= 100_000_000
      ? `${formatNumber(dollars / 100_000_000, 1)}억 달러`
      : `${formatNumber(dollars, digitsFor(dollars))}달러`
  }
  if (trimmed === '달러/배럴') return `${formatNumber(value, 2)}달러/배럴`
  return `${formatNumber(value, digitsFor(value))} ${trimmed}`.trim()
}

/** 값이 있는 마지막 두 달. 둘이 없으면 변화를 말하지 않는다. */
function lastTwo(indicator: IndicatorDisplay) {
  const values = indicator.path.map((point) => point.value).filter((value): value is number => value !== null)
  return values.length < 2 ? null : { previous: values[values.length - 2], current: values[values.length - 1] }
}

/** 직전 달 대비 변화. 퍼센트 지표는 %p 차이, 나머지는 변화율이다. */
export function describeFlowChange(indicator: IndicatorDisplay): { text: string; direction: Direction } {
  const pair = lastTwo(indicator)
  if (!pair) return { text: '비교 전', direction: undefined }
  const difference = pair.current - pair.previous
  const direction: Direction = difference > 0 ? 'up' : difference < 0 ? 'down' : undefined
  const sign = difference > 0 ? '+' : difference < 0 ? '−' : ''
  if (PERCENT_UNITS.has(indicator.unit.trim())) {
    return { text: `${sign}${formatNumber(Math.abs(difference), 2)}%p`, direction }
  }
  if (pair.previous === 0) return { text: '비교 전', direction }
  const rate = (difference / Math.abs(pair.previous)) * 100
  return { text: `${sign}${formatNumber(Math.abs(rate), 1)}%`, direction }
}

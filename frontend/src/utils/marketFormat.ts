import type { DataFrequency, Indicator, MarketRegime } from '@/types/market'

const frequencyLabels: Record<DataFrequency, string> = {
  daily: '거래일',
  monthly: '월간',
  quarterly: '분기',
  event: '이벤트',
}

/** 지표의 원시 숫자를 소수점과 단위 규칙에 맞춰 표시한다. */
export function formatIndicatorValue(indicator: Indicator) {
  const value = indicator.value.toLocaleString('ko-KR', {
    minimumFractionDigits: indicator.decimals,
    maximumFractionDigits: indicator.decimals,
  })

  return `${value}${indicator.unit === '원' ? '' : ' '}${indicator.unit}`
}

/** 변화량에 방향 기호와 비교 기준 단위를 더한다. */
export function formatIndicatorChange(indicator: Indicator) {
  if (indicator.change === null) return '비교값 없음'

  const prefix = indicator.change > 0 ? '+' : ''
  return `${prefix}${indicator.change.toLocaleString('ko-KR')}${indicator.changeUnit ? ` ${indicator.changeUnit}` : ''}`
}

/** ISO 형태의 기준일을 짧은 한국어 화면 표기로 변환한다. */
export function formatDataDate(value: string) {
  return value.replaceAll('-', '.')
}

/** 데이터 수집 주기의 영문 코드를 화면용 한국어로 변환한다. */
export function formatFrequency(frequency: DataFrequency) {
  return frequencyLabels[frequency]
}

/** 비교 패널의 숫자를 부호와 단위가 포함된 문자열로 변환한다. */
export function formatComparisonValue(value: number, unit: string) {
  const formatted = value.toLocaleString('ko-KR', {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  })

  return `${formatted}${unit === '원' ? '' : ' '}${unit}`
}

export const cycleLabels: Record<MarketRegime['cycle'], string> = {
  recovery: '회복 중',
  expansion: '확장',
  slowdown: '경기 둔화',
  contraction: '경기 위축',
}

export const inflationLabels: Record<MarketRegime['inflation'], string> = {
  easing: '물가 부담 완화',
  neutral: '물가 안정',
  pressure: '물가 부담 증가',
}

export const riskLabels: Record<MarketRegime['risk'], string> = {
  riskOn: '긍정적',
  neutral: '중립',
  riskOff: '주의 필요',
}

import type { RegimeCaseSummary } from '@/types/regime'

/** 관측 구간을 처음 달~끝 달로 줄여 적는다. */
export function observationRange(item: Pick<RegimeCaseSummary, 'observationMonths' | 'endMonth'>): string {
  const months = item.observationMonths
  if (months.length === 0) return item.endMonth
  const first = months[0]
  const last = months[months.length - 1]
  return first === last ? first : `${first} ~ ${last}`
}

/** 그래프 범례에 쓰는 짧은 기간이다. 같은 해면 끝 달의 연도를 뺀다(2026.05 ~ 07). 달이 없으면 null이다. */
export function shortRange(months: readonly (string | null)[]): string | null {
  const known = months.filter((month): month is string => month !== null)
  if (known.length === 0) return null
  const first = known[0].replace('-', '.')
  if (known.length === 1) return first
  const last = known[known.length - 1]
  return last.slice(0, 4) === known[0].slice(0, 4) ? `${first} ~ ${last.slice(5)}` : `${first} ~ ${last.replace('-', '.')}`
}

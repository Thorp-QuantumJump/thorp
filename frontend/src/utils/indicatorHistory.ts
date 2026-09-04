import type { IndicatorSeriesPoint } from '@/types/dashboard'

/** 월간·일간 관측값을 기준월 포함 최근 N개월로 자르고 시간순으로 정렬한다. */
export function selectIndicatorPeriod(
  series: IndicatorSeriesPoint[],
  observationDate: string,
  months: number,
) {
  const [year, month] = observationDate.split('-').map(Number)
  const lastMonth = year * 12 + month - 1

  return series
    .filter((point) => {
      const [pointYear, pointMonth] = point.date.split('-').map(Number)
      const pointMonthIndex = pointYear * 12 + pointMonth - 1
      return Number.isFinite(point.value)
        && pointMonthIndex > lastMonth - months
        && pointMonthIndex <= lastMonth
        && point.date <= observationDate
    })
    .sort((left, right) => left.date.localeCompare(right.date))
}

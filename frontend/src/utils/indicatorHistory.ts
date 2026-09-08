import type { IndicatorSeriesPoint } from '@/types/market'

/** ISO 월·일 형식과 윤년을 검사하고, 존재하는 날짜만 월 인덱스로 변환한다. */
function parseIndicatorDate(value: string) {
  if (typeof value !== 'string') return null
  const match = /^(\d{4})-(\d{2})(?:-(\d{2}))?$/.exec(value)
  if (!match) return null

  const year = Number(match[1])
  const month = Number(match[2])
  const day = match[3] === undefined ? null : Number(match[3])
  if (year < 1 || month < 1 || month > 12) return null

  const leapYear = year % 400 === 0 || (year % 4 === 0 && year % 100 !== 0)
  const daysInMonth = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
  if (day !== null && (day < 1 || day > daysInMonth)) return null

  return { monthIndex: year * 12 + month - 1, day, daysInMonth }
}

/** 월간·일간 관측값을 기준월 포함 최근 N개월로 자르고 시간순으로 정렬한다. */
export function selectIndicatorPeriod(
  series: IndicatorSeriesPoint[],
  observationDate: string,
  months: number,
) {
  const observation = parseIndicatorDate(observationDate)
  if (!observation || !Number.isInteger(months) || months < 1) return []

  return series
    .filter((point) => {
      const date = parseIndicatorDate(point.date)
      if (!date || !Number.isFinite(point.value)) return false

      return date.monthIndex > observation.monthIndex - months
        && date.monthIndex <= observation.monthIndex
        && (date.monthIndex < observation.monthIndex
          || (date.day ?? 1) <= (observation.day ?? observation.daysInMonth))
    })
    .sort((left, right) => left.date.localeCompare(right.date))
}

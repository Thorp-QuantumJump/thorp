/**
 * 달력이 쓰는 날짜 셈이다. 화면 없이 단위 검사로 고정할 수 있게 순수 함수만 둔다.
 *
 * <p><b>`Date.prototype.toISOString`을 쓰지 않는다.</b> 그 함수는 UTC로 옮겨 적어서, 한국처럼
 * UTC보다 앞선 표준시에서는 자정 근처의 날짜가 하루 밀린다. 값은 언제나 지역 시간의 연·월·일을
 * 그대로 적은 `YYYY-MM-DD` 문자열이다.
 *
 * <p>`YYYY-MM-DD`와 `YYYY-MM`은 자릿수가 고정이라 **문자열 비교가 곧 날짜 비교**다. 범위 판정에
 * `Date` 객체를 만들지 않는 까닭이다.
 */

/** `YYYY-MM-DD`로 적는다. 달은 0부터 센 값을 받는다. */
export function toIsoDate(year: number, monthIndex: number, day: number): string {
  const month = String(monthIndex + 1).padStart(2, '0')
  return `${year}-${month}-${String(day).padStart(2, '0')}`
}

/** `YYYY-MM`으로 적는다. 달은 0부터 센 값을 받는다. */
export function toIsoMonth(year: number, monthIndex: number): string {
  return `${year}-${String(monthIndex + 1).padStart(2, '0')}`
}

export type YearMonthDay = { year: number; monthIndex: number; day: number }

/**
 * `YYYY-MM-DD`를 읽는다. 모양이 다르거나 달력에 없는 날(2월 30일 등)이면 `null`이다.
 *
 * 존재하지 않는 날짜를 되돌려 주면 달력이 엉뚱한 달을 펼친다. `Date`가 조용히 다음 달로 넘겨
 * 버리므로 되읽어 같은 값인지 확인한다.
 */
export function parseIsoDate(value: string): YearMonthDay | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) return null

  const year = Number(match[1])
  const monthIndex = Number(match[2]) - 1
  const day = Number(match[3])
  if (monthIndex < 0 || monthIndex > 11 || day < 1) return null

  const probe = new Date(year, monthIndex, day)
  if (probe.getFullYear() !== year || probe.getMonth() !== monthIndex || probe.getDate() !== day) {
    return null
  }
  return { year, monthIndex, day }
}

/** `YYYY-MM`을 읽는다. 모양이 다르면 `null`이다. */
export function parseIsoMonth(value: string): { year: number; monthIndex: number } | null {
  const match = /^(\d{4})-(\d{2})$/.exec(value)
  if (!match) return null

  const monthIndex = Number(match[2]) - 1
  if (monthIndex < 0 || monthIndex > 11) return null
  return { year: Number(match[1]), monthIndex }
}

/** 그 달의 날수다. 다음 달 0일이 이번 달 마지막 날이다. */
export function daysInMonth(year: number, monthIndex: number): number {
  return new Date(year, monthIndex + 1, 0).getDate()
}

/** 그 달 1일의 요일이다. 0이 일요일이다. */
export function firstWeekdayOf(year: number, monthIndex: number): number {
  return new Date(year, monthIndex, 1).getDay()
}

/** 달을 앞뒤로 옮긴다. 12월을 넘으면 해가 함께 바뀐다. */
export function shiftMonth(
  year: number,
  monthIndex: number,
  delta: number,
): { year: number; monthIndex: number } {
  const total = year * 12 + monthIndex + delta
  return { year: Math.floor(total / 12), monthIndex: ((total % 12) + 12) % 12 }
}

/** 달력 한 칸이다. 이웃 달에서 끌어온 칸은 `outside`가 참이다. */
export type CalendarCell = {
  iso: string
  day: number
  outside: boolean
}

/**
 * 한 달의 격자를 만든다. **언제나 6주 42칸**이라 달을 넘길 때 격자 높이가 흔들리지 않는다.
 *
 * 앞뒤 빈자리는 이웃 달의 날짜로 채운다. 빈칸으로 두면 달의 시작과 끝이 어디인지 오히려 읽기
 * 어렵고, 월요일부터 시작하는 달에서 첫 줄이 통째로 비어 보인다.
 */
export function buildMonthGrid(year: number, monthIndex: number): CalendarCell[] {
  const lead = firstWeekdayOf(year, monthIndex)
  const length = daysInMonth(year, monthIndex)
  const previous = shiftMonth(year, monthIndex, -1)
  const next = shiftMonth(year, monthIndex, 1)
  const previousLength = daysInMonth(previous.year, previous.monthIndex)

  const cells: CalendarCell[] = []
  for (let i = lead; i > 0; i -= 1) {
    const day = previousLength - i + 1
    cells.push({ iso: toIsoDate(previous.year, previous.monthIndex, day), day, outside: true })
  }
  for (let day = 1; day <= length; day += 1) {
    cells.push({ iso: toIsoDate(year, monthIndex, day), day, outside: false })
  }
  for (let day = 1; cells.length < 42; day += 1) {
    cells.push({ iso: toIsoDate(next.year, next.monthIndex, day), day, outside: true })
  }
  return cells
}

/**
 * 범위 안인지 본다. 경계는 포함이다. 자릿수가 고정된 ISO 문자열이라 사전순 비교가 곧 날짜 비교다.
 *
 * `min`·`max`가 비어 있거나 모양이 다르면 그 방향으로는 제한하지 않는다. 입력칸의
 * `min`/`max` 속성을 그대로 받기 때문에 값이 없을 수 있다.
 */
export function isWithinBounds(iso: string, min?: string, max?: string): boolean {
  if (min && min.length === iso.length && iso < min) return false
  if (max && max.length === iso.length && iso > max) return false
  return true
}

/** 오늘을 지역 시간 기준으로 적는다. */
export function todayIsoDate(now: Date = new Date()): string {
  return toIsoDate(now.getFullYear(), now.getMonth(), now.getDate())
}

/** 일요일부터 시작하는 요일 이름이다. 격자의 열 순서와 같다. */
export const WEEKDAY_LABELS = ['일', '월', '화', '수', '목', '금', '토'] as const

/** 달 이름이다. 달 고르기 판이 쓴다. */
export const MONTH_LABELS = [
  '1월',
  '2월',
  '3월',
  '4월',
  '5월',
  '6월',
  '7월',
  '8월',
  '9월',
  '10월',
  '11월',
  '12월',
] as const

/** 연도 판 한 쪽에 놓는 해의 수다. 3×3 격자라 아홉이다. */
export const YEARS_PER_PAGE = 9

/**
 * 연도 판이 보여 줄 아홉 해의 첫 해다.
 *
 * `floor(year / 9) * 9`로 잡아 **어느 해에서 들어와도 같은 묶음이 나온다.** 들어온 해를 첫
 * 칸에 두면 앞뒤로 넘길 때마다 경계가 밀려, 같은 해를 두 번 거치거나 건너뛰게 된다.
 */
export function yearPageStart(year: number): number {
  return Math.floor(year / YEARS_PER_PAGE) * YEARS_PER_PAGE
}

/** 연도 판에 놓을 아홉 해다. */
export function buildYearPage(start: number): number[] {
  return Array.from({ length: YEARS_PER_PAGE }, (_, index) => start + index)
}

/**
 * 그 해에 고를 수 있는 달이 하나라도 있는가. 연도 칸을 잠글지 판정한다.
 *
 * 경계는 연도 네 자리만 떼어 비교한다. `min`이 `2011-04-06`이면 2011년 자체는 열려 있어야
 * 하고, 달·날 판정은 그 다음 단계가 맡는다.
 */
export function isYearWithinBounds(year: number, min?: string, max?: string): boolean {
  if (min && min.length >= 4 && year < Number(min.slice(0, 4))) return false
  if (max && max.length >= 4 && year > Number(max.slice(0, 4))) return false
  return true
}

/**
 * 그 달에 고를 수 있는 날이 하나라도 있는가. 달 칸을 잠글지 판정한다.
 *
 * 달의 첫날과 마지막 날만 본다. 둘 중 하나라도 범위 안이면 그 달에 고를 날이 있다.
 */
export function isMonthWithinBounds(
  year: number,
  monthIndex: number,
  min?: string,
  max?: string,
): boolean {
  const first = toIsoDate(year, monthIndex, 1)
  const last = toIsoDate(year, monthIndex, daysInMonth(year, monthIndex))
  if (min && min.length === first.length && last < min) return false
  if (max && max.length === first.length && first > max) return false
  return true
}

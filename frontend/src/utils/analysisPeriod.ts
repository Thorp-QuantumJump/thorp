/**
 * 분석 기간을 판정하는 순수 함수(FR-009·FR-011a, data-model §9·§10).
 *
 * <p>날짜는 `YYYY-MM-DD` 문자열 그대로 다룬다. `Date`로 옮기면 시간대에 따라 하루가 밀릴 수 있고,
 * 이 형식은 글자 순서와 날짜 순서가 같아 문자열 비교로 앞뒤를 가릴 수 있다.
 *
 * <p><b>거래일을 세지 않는다.</b> 평가 가능 범위(거래일 기준)는 001이 계산해 넘기고, 이 파일은 받은 범위와
 * 사용자가 고른 기간을 견주기만 한다.
 */

export type AnalysisPeriod = { start: string; end: string }

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/

/** 실제로 있는 날짜의 `YYYY-MM-DD`인가. `2023-02-30`처럼 모양만 맞는 글은 거짓이다. */
export function isIsoDate(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const match = ISO_DATE.exec(value)
  if (!match) return false
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])]
  if (month < 1 || month > 12 || day < 1) return false
  return day <= daysInMonth(year, month)
}

function daysInMonth(year: number, month: number): number {
  // UTC로 다음 달 0일을 만들면 이번 달의 마지막 날이다. 시간대의 영향을 받지 않는다.
  return new Date(Date.UTC(year, month, 0)).getUTCDate()
}

/**
 * 날짜에 달력 기준으로 `years`년을 더한다. 2월 29일에서 평년으로 가면 2월 28일이다.
 *
 * 최소 평가 기간(연)을 견주는 데 쓴다. 365일을 곱하지 않는 까닭은 윤년이 끼면 하루가 모자라, 정확히
 * 1년인 기간을 1년 미만으로 판정하기 때문이다.
 */
export function addYears(date: string, years: number): string {
  const match = ISO_DATE.exec(date)
  if (!match || !Number.isInteger(years)) {
    throw new RangeError(`날짜와 연수를 읽을 수 없습니다: ${date}, ${years}`)
  }
  const year = Number(match[1]) + years
  const month = Number(match[2])
  const day = Math.min(Number(match[3]), daysInMonth(year, month))
  return `${String(year).padStart(4, '0')}-${match[2]}-${String(day).padStart(2, '0')}`
}

/** 기간이 범위 안에 온전히 들어가는가. 경계와 같은 날은 안이다. */
export function isWithin(period: AnalysisPeriod, range: AnalysisPeriod): boolean {
  return period.start >= range.start && period.end <= range.end
}

/**
 * 기간이 최소 평가 기간(연) 이상인가. `0`은 제한 없음이다(FR-011a).
 *
 * 시작일에 N년을 더한 날이 종료일보다 늦으면 모자란 것이다. `2020-01-02`~`2021-01-02`는 정확히 1년이라
 * 통과한다. 서버가 다시 확인할 때도 같은 규약이어야 한다 — 004 요청 계약에 이 필드가 아직 없어 지금은
 * 화면만 막는다(data-model §10).
 */
export function meetsMinimumYears(period: AnalysisPeriod, minYears: number): boolean {
  if (minYears <= 0) return true
  return addYears(period.start, minYears) <= period.end
}

/**
 * 입력한 두 날짜가 분석 기간으로 쓸 수 있는가. 쓸 수 있으면 `null`, 아니면 사용자에게 보일 한 문장.
 *
 * 범위는 001이 넘긴 평가 가능 범위다. **범위 밖 날짜는 잘라 넣지 않고 거절한다** — 잘라 넣으면 사용자가
 * 고르지 않은 날짜로 분석이 나간다(FR-006과 같은 규약).
 */
export function checkPeriodInput(
  start: string,
  end: string,
  range: AnalysisPeriod,
): string | null {
  if (!isIsoDate(start) || !isIsoDate(end)) {
    return '시작일과 종료일을 모두 날짜로 입력해 주세요.'
  }
  if (start > end) return '시작일이 종료일보다 늦습니다.'
  if (!isWithin({ start, end }, range)) {
    return `평가 가능 범위(${range.start} ~ ${range.end}) 밖의 날짜입니다.`
  }
  return null
}

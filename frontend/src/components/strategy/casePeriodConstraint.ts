/**
 * 011 사례 경로에서 003이 더 지키는 분석 기간 제약(011 `contracts/entry-handoff.md` §3).
 *
 * <p>사례의 사후 구간(`d0`~`d63`)을 결과가 덮어야 하므로 분석 기간의 <b>시작일에는 상한</b>이,
 * <b>종료일에는 하한</b>이 생긴다. 상한은 `d0 − 선택 전략의 워밍업 거래일`, 하한은 `d63`(사후 구간이
 * 미완성이면 자료 마감일 `lastAvailable`)이다.
 *
 * <p><b>거래일을 세는 달력은 만들지 않는다.</b> 001 `GET /sectors/evaluation-window`가 준
 * `tradingDaysFromBase`(오름차순 거래일 목록)를 그대로 훑는다. 화면이 영업일을 추정하면 서버 판정과
 * 갈리는 날짜가 생기고, 사용자는 고를 수 있다고 안내받은 기간으로 분석이 거절되는 것을 본다.
 */

export type CasePeriodConstraint = {
  /** 분석 기간 시작일의 상한. 이 날보다 늦게 시작하면 워밍업이 사후 구간을 먹는다. */
  startMax: string
  /** 분석 기간 종료일의 하한. 이 날보다 일찍 끝나면 사후 구간이 잘린다. */
  endMin: string
  /** 화면에 그대로 보이는 고정 문구(진입 계약 §3). */
  note: string
}

/** 제약을 계산할 수 없는 까닭. 값이 없는 것과 "덮을 수 없다"를 가른다. */
export type CasePeriodConstraintProblem = 'calendarTooShort' | 'postWindowUnknown'

export type CasePeriodConstraintInput = {
  /** §1a `GET /regime-links/case-window` 응답의 `postWindow`. */
  postWindow: { d0: string; d63: string | null; lastAvailable?: string | null }
  /** 사례의 관측 종료월 `YYYY-MM`. 안내 문구에 들어간다. */
  endMonth: string
  /** 지금 고른 전략·설정의 워밍업 거래일 수. */
  warmupTradingDays: number
  /** 001이 준 거래일 목록. 오름차순이며 시장 달력이다. */
  tradingDays: readonly string[]
}

/** 고정 문구다. 사례 월을 빼면 사용자는 어느 사례 때문에 좁아졌는지 알 수 없다. */
export function caseConstraintNote(endMonth: string): string {
  const match = /^(\d{4})-(\d{2})$/.exec(endMonth)
  const label = match ? `${match[1]}년 ${Number(match[2])}월` : endMonth
  return `이 사례(${label}) 직후 구간을 포함해야 하므로 기간을 여기까지만 줄일 수 있습니다.`
}

/**
 * 제약을 만든다. 만들 수 없으면 까닭을 돌려준다.
 *
 * <p>`d0`가 거래일 목록에 없을 수 있다(사례가 달력 앞쪽이거나 휴장일 판정이 다를 때). 그때는 `d0`
 * <b>이하</b>의 마지막 거래일에서 세기 시작한다 — 워밍업은 `d0` 전에 끝나 있어야 하므로 그쪽이 안전하다.
 */
export function buildCasePeriodConstraint(
  input: CasePeriodConstraintInput,
): CasePeriodConstraint | CasePeriodConstraintProblem {
  const endMin = input.postWindow.d63 ?? input.postWindow.lastAvailable ?? null
  if (endMin === null) return 'postWindowUnknown'

  // `d0` 이하의 마지막 거래일 자리. 목록이 오름차순이라 뒤에서 찾는다.
  let at = -1
  for (let index = input.tradingDays.length - 1; index >= 0; index -= 1) {
    if (input.tradingDays[index] <= input.postWindow.d0) {
      at = index
      break
    }
  }
  if (at < 0) return 'calendarTooShort'

  const startIndex = at - input.warmupTradingDays
  if (startIndex < 0) return 'calendarTooShort'

  return {
    startMax: input.tradingDays[startIndex],
    endMin,
    note: caseConstraintNote(input.endMonth),
  }
}

/** 공통 평가 가능 범위가 이 제약을 채울 수 있는가(진입 계약 §3의 세 번째 항목). */
export function rangeCoversCase(
  range: { start: string; end: string },
  constraint: CasePeriodConstraint,
): boolean {
  return range.start <= constraint.startMax && range.end >= constraint.endMin
}

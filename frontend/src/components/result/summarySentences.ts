import { formatMoney, formatRatio } from './formatResult'

/**
 * 해석 요약 1문장의 고정 문장 틀(FR-006, research R-07, data-model §3-5).
 *
 * 이 파일은 SC-004의 검수 대상이다. 화면이 문장을 새로 짓지 않는다. 금지 어휘는
 * `summarySentences.test.ts`가 막는다.
 *
 * <p>틀을 고르는 축은 셋이다. 비교 대상이 있는지, 두 결과의 차이가 어느 방향인지, 부분 결과인지다.
 * 차이는 <b>크기를 비교만 하고 빼지 않는다</b> — 두 값을 나란히 적어 사용자가 읽게 한다. 이 화면은
 * 값을 계산하지 않는다.
 *
 * <p>틀의 `{이름}` 자리만 값으로 채운다. 이름 뒤에 조사가 붙지 않도록 이름은 괄호 안에 둔다.
 */

export const SENTENCE_TEMPLATES = {
  /** 비교 대상 없음: 기준 결과의 세 숫자 */
  single:
    '비용을 뺀 뒤 전체 수익률은 {baseReturn}, 운용 중 고점 대비 가장 크게 줄었던 폭은 {baseDrawdown}, 거래비용은 {baseCost}입니다.',
  /** 두 결과의 세 숫자가 모두 같다 */
  identical:
    '같은 조건에서 기준({baseName})과 비교 대상({targetName})의 수익률·가장 크게 줄었던 폭·거래비용이 모두 같습니다.',
  /** 수익률과 가장 크게 줄었던 폭은 같고 거래비용만 다르다 */
  costOnlyMore:
    '같은 조건에서 두 결과의 수익률과 가장 크게 줄었던 폭은 같고, 거래비용은 비교 대상({targetName}) {targetCost}으로 기준({baseName}) {baseCost}보다 많습니다.',
  costOnlyLess:
    '같은 조건에서 두 결과의 수익률과 가장 크게 줄었던 폭은 같고, 거래비용은 비교 대상({targetName}) {targetCost}으로 기준({baseName}) {baseCost}보다 적습니다.',
  /** 비교 대상 기준으로 수익률 방향 × 가장 크게 줄었던 폭의 방향 */
  higherLarger:
    '같은 조건에서 비교 대상({targetName})은 기준({baseName})보다 수익률이 높고({targetReturn}, 기준 {baseReturn}) 가장 크게 줄었던 폭도 컸습니다({targetDrawdown}, 기준 {baseDrawdown}).',
  higherSmaller:
    '같은 조건에서 비교 대상({targetName})은 기준({baseName})보다 수익률이 높고({targetReturn}, 기준 {baseReturn}) 가장 크게 줄었던 폭은 작았습니다({targetDrawdown}, 기준 {baseDrawdown}).',
  higherSame:
    '같은 조건에서 비교 대상({targetName})은 기준({baseName})보다 수익률이 높고({targetReturn}, 기준 {baseReturn}) 가장 크게 줄었던 폭은 같습니다({baseDrawdown}).',
  lowerLarger:
    '같은 조건에서 비교 대상({targetName})은 기준({baseName})보다 수익률이 낮고({targetReturn}, 기준 {baseReturn}) 가장 크게 줄었던 폭은 컸습니다({targetDrawdown}, 기준 {baseDrawdown}).',
  lowerSmaller:
    '같은 조건에서 비교 대상({targetName})은 기준({baseName})보다 수익률이 낮고({targetReturn}, 기준 {baseReturn}) 가장 크게 줄었던 폭도 작았습니다({targetDrawdown}, 기준 {baseDrawdown}).',
  lowerSame:
    '같은 조건에서 비교 대상({targetName})은 기준({baseName})보다 수익률이 낮고({targetReturn}, 기준 {baseReturn}) 가장 크게 줄었던 폭은 같습니다({baseDrawdown}).',
  sameLarger:
    '같은 조건에서 두 결과의 수익률은 같고({baseReturn}) 가장 크게 줄었던 폭은 비교 대상({targetName})이 기준({baseName})보다 컸습니다({targetDrawdown}, 기준 {baseDrawdown}).',
  sameSmaller:
    '같은 조건에서 두 결과의 수익률은 같고({baseReturn}) 가장 크게 줄었던 폭은 비교 대상({targetName})이 기준({baseName})보다 작았습니다({targetDrawdown}, 기준 {baseDrawdown}).',
} as const

/** 부분 결과 접두. 완료 범위를 모르면 날짜 없이 쓴다 */
export const PARTIAL_PREFIX_TEMPLATES = {
  withDate: '{completedThrough}까지 계산한 값입니다. ',
  withoutDate: '완료 범위까지 계산한 값입니다. ',
} as const

export type SentenceTemplateKey = keyof typeof SENTENCE_TEMPLATES

/** 문장 틀에 넣을 한 결과의 값. 응답 문자열 그대로다 */
export type SentenceSubject = {
  name: string
  netTotalReturn: string
  maxDrawdown: string
  totalTradingCost: string
}

export type SentenceInput = {
  base: SentenceSubject
  target?: SentenceSubject | null
  /** 부분 결과면 완료 범위. 날짜를 모르면 `completedThrough: null` */
  partial?: { completedThrough: string | null } | null
}

type Direction = 'higher' | 'lower' | 'same'

/**
 * **화면에 보이는 자릿수로 비교한다.** 보이는 두 값이 같은데 한쪽이 높다고 쓰면 문장과 숫자가 다른
 * 말을 한다. 비율은 백분율 소수 둘째 자리, 금액은 원 단위다(`formatResult.ts`와 같은 자릿수).
 */
function compare(target: string, base: string, displayed: (value: string) => string): Direction {
  const t = Number(displayed(target).replace(/[^\d.-]/g, ''))
  const b = Number(displayed(base).replace(/[^\d.-]/g, ''))
  if (t === b) return 'same'
  return t > b ? 'higher' : 'lower'
}

/** 조건에 맞는 틀의 이름을 고른다. */
export function selectTemplateKey(input: SentenceInput): SentenceTemplateKey {
  const { base, target } = input
  if (!target) return 'single'

  const returnDirection = compare(target.netTotalReturn, base.netTotalReturn, formatRatio)
  // 가장 크게 줄었던 폭은 0 이하의 비율이다. 값이 더 낮으면(더 음수면) 폭이 더 크다.
  const drawdownDirection = compare(target.maxDrawdown, base.maxDrawdown, formatRatio)
  const drawdownWord =
    drawdownDirection === 'same' ? 'Same' : drawdownDirection === 'lower' ? 'Larger' : 'Smaller'

  if (returnDirection === 'same' && drawdownDirection === 'same') {
    const costDirection = compare(target.totalTradingCost, base.totalTradingCost, formatMoney)
    if (costDirection === 'same') return 'identical'
    return costDirection === 'higher' ? 'costOnlyMore' : 'costOnlyLess'
  }

  return `${returnDirection}${drawdownWord}` as SentenceTemplateKey
}

function fill(template: string, values: Record<string, string>): string {
  return template.replace(/\{(\w+)\}/g, (placeholder, key: string) => values[key] ?? placeholder)
}

/**
 * 조건에 맞는 틀을 골라 값 자리만 채운 해석 요약 1문장.
 *
 * 값은 자릿수만 다듬어 넣는다(비율은 %, 금액은 원).
 */
export function selectSummarySentence(input: SentenceInput): string {
  const { base, target, partial } = input
  const values: Record<string, string> = {
    baseName: base.name,
    baseReturn: formatRatio(base.netTotalReturn),
    baseDrawdown: formatRatio(base.maxDrawdown),
    baseCost: formatMoney(base.totalTradingCost),
  }
  if (target) {
    values.targetName = target.name
    values.targetReturn = formatRatio(target.netTotalReturn)
    values.targetDrawdown = formatRatio(target.maxDrawdown)
    values.targetCost = formatMoney(target.totalTradingCost)
  }

  const prefix = partial
    ? partial.completedThrough
      ? fill(PARTIAL_PREFIX_TEMPLATES.withDate, { completedThrough: partial.completedThrough })
      : PARTIAL_PREFIX_TEMPLATES.withoutDate
    : ''

  return prefix + fill(SENTENCE_TEMPLATES[selectTemplateKey(input)], values)
}

import type { IndicatorDisplay } from '@/types/marketFlow'
import type { RegimeCaseDetail, RegimeCaseSummary } from '@/types/regime'

/*
  분야 상세의 지표 그래프에 고른 과거 구간을 겹치기 위한 값(009 FR-019). 010은 같은 지표의 3개월 경로를 달 순서대로
  맞대어 거리를 재므로, 당시 값은 010이 비교한 달(comparisonMonths)과 그래프의 달이 같은 자리에만 놓는다. 달이
  어긋난 자리를 순서만으로 채우면 비교하지 않은 달끼리 맞댄 그림이 된다.
*/

export type PastVerdict = { kind: 'similar' | 'different'; text: string }

/** 그래프의 세 달(t-2·t-1·t)마다 맞댄 당시 달과 값. 010이 그 달을 비교하지 않았으면 둘 다 null이다. */
export interface PastPath {
  months: (string | null)[]
  values: (number | null)[]
}

export interface IndicatorPast {
  path: PastPath | null
  verdict: PastVerdict | null
  /** 010 공통 척도의 중앙값 ± 산포. 척도가 없으면 null이다. */
  band: [number, number] | null
}

/** 문장이 이 지표 이름으로 시작하고 바로 뒤에 조사가 붙는지 본다. '소비자물가'가 '소비자물가지수' 문장에 걸리지 않게 한다. */
function speaksOf(sentence: string, name: string): boolean {
  if (!sentence.startsWith(name)) return false
  const next = sentence.charAt(name.length)
  return next === '' || /[\s은는이가의도]/.test(next)
}

/** 010이 준 비슷한 점·다른 점 문장을 순서대로(비슷한 점 먼저) 늘어놓는다. 쪽마다 최대 두 문장이다. */
function verdictsOf(summary: RegimeCaseSummary): PastVerdict[] {
  return [
    ...summary.similar.map((text) => ({ kind: 'similar' as const, text })),
    ...summary.different.map((text) => ({ kind: 'different' as const, text })),
  ]
}

/** 이 지표를 다루는 첫 문장. 비슷한 점을 먼저 본다. */
export function verdictFor(name: string, summary: RegimeCaseSummary): PastVerdict | null {
  return verdictsOf(summary).find((verdict) => speaksOf(verdict.text, name)) ?? null
}

/** 어느 지표 칸에도 붙지 못한 문장. 버리지 않고 선택 줄 아래에 보인다. */
export function unplacedVerdicts(indicators: readonly IndicatorDisplay[], summary: RegimeCaseSummary): PastVerdict[] {
  const placed = new Set(indicators.flatMap((indicator) => {
    const verdict = verdictFor(indicator.name, summary)
    return verdict ? [verdict.text] : []
  }))
  return verdictsOf(summary).filter((verdict) => !placed.has(verdict.text))
}

function toNumber(value: string | undefined): number | null {
  if (value === undefined || value.trim() === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

/**
 * 그래프 한 칸에 겹칠 당시 경로·문장·보통 범위를 만든다. 상세를 아직 받지 못했으면 문장만 있다.
 *
 * 보통 범위는 `scaleSource`에서 읽는다. 공통 척도는 같은 검색의 모든 사례에 똑같으므로 다른 사례의 상세여도 된다.
 */
export function pastForIndicator(
  indicator: IndicatorDisplay,
  summary: RegimeCaseSummary,
  detail: RegimeCaseDetail | null,
  scaleSource: RegimeCaseDetail | null = detail,
): IndicatorPast {
  return { path: pastPathOf(indicator, detail), verdict: verdictFor(indicator.name, summary), band: bandOf(indicator, scaleSource) }
}

function pastPathOf(indicator: IndicatorDisplay, detail: RegimeCaseDetail | null): PastPath | null {
  const compared = detail?.indicators.find((item) => item.id === indicator.indicatorId)
  if (!compared) return null
  const months: (string | null)[] = []
  const values: (number | null)[] = []
  for (const point of indicator.path) {
    const at = compared.comparisonMonths.indexOf(point.month)
    months.push(at < 0 ? null : compared.caseMonths[at] ?? null)
    values.push(at < 0 ? null : toNumber(compared.caseRaw[at]))
  }
  return values.some((value) => value !== null) ? { months, values } : null
}

/** 그 지표의 공통 척도로 만든 보통 범위. '지금만'을 고른 동안에도 쓸 수 있게 사례와 따로 읽는다. */
export function bandOf(indicator: IndicatorDisplay, scaleSource: RegimeCaseDetail | null): [number, number] | null {
  const scale = scaleSource?.indicators.find((item) => item.id === indicator.indicatorId)?.scale
  const median = toNumber(scale?.median)
  const spread = toNumber(scale?.spread)
  return median === null || spread === null ? null : [median - spread, median + spread]
}

/**
 * 보통 범위 띠의 이름과 척도 창. 지표마다 창 길이가 같고 열두 달 단위면 'n년'으로 줄인다. 지표마다 창이 다르면
 * 기간을 이름에 넣지 않고, 창은 지표별로 적는다 — 한 지표의 창을 분야 전체의 것처럼 적지 않는다.
 */
export function bandLabel(detail: RegimeCaseDetail | null): { name: string; window: string } | null {
  const scaled = (detail?.indicators ?? []).flatMap((item) => (item.scale ? [{ name: item.name, scale: item.scale }] : []))
  if (scaled.length === 0) return null
  const windows = new Set(scaled.map(({ scale }) => `${scale.windowStart}~${scale.windowEnd}`))
  if (windows.size === 1) {
    const { months } = scaled[0].scale
    const span = months % 12 === 0 ? `지난 ${months / 12}년` : `지난 ${months}개월`
    return { name: `${span} 보통 범위`, window: [...windows][0] }
  }
  return { name: '보통 범위', window: scaled.map(({ name, scale }) => `${name} ${scale.windowStart}~${scale.windowEnd}`).join(', ') }
}

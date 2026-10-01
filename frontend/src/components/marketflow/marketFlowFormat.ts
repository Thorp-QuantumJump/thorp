import type { FieldStatus, IndicatorDisplay } from '@/types/marketFlow'

/**
 * 표시만 담당한다. 반올림한 값으로 경로나 변화 방향을 계산하지 않는다.
 *
 * 소수 첫째 자리까지 보인다(S15P21E205-447). 원천 값은 자릿수가 제각각이라(증가율 4.346167 등) 그대로 두면
 * 읽을 수 없다. 정밀 값은 응답에 그대로 남는다.
 */
export function formatObservation(value: number | null, unit?: string): string {
  if (value === null) return '자료 없음'
  if (unit && ['USD', '달러', '미국 달러'].includes(unit) && Math.abs(value) >= 100_000_000) {
    const rounded = Math.round(Math.abs(value) / 10_000_000)
    const hundredMillions = Math.floor(rounded / 10)
    const remainder = rounded % 10
    return `약 ${value < 0 ? '-' : ''}${hundredMillions.toLocaleString('ko-KR')}억${remainder ? ` ${remainder}천만` : ''}`
  }
  const text = value.toLocaleString('ko-KR', { maximumFractionDigits: 1 })
  // -0.04 처럼 0으로 반올림된 음수는 "-0"이 아니라 "0"이다.
  return text === '-0' ? '0' : text
}

/** 서버 요약의 문장은 유지하고 숫자의 표시 정밀도만 줄인다. */
export function formatNarrative(text: string | null): string | null {
  return text?.replace(/-?\d[\d,]*\.\d+/g, (value) => formatObservation(Number(value.replaceAll(',', '')))) ?? null
}

export function formatUpdatedAt(value: string | null): string {
  if (!value) return '확인할 수 없음'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(parsed)
}

/**
 * 3개월 경로의 세로축 범위를 정한다.
 *
 * **0을 언제나 포함하지 않는다.** 환율 1,351원과 1,378원을 0부터 그리면 27원 변동이
 * 평평한 선이 되고, 국고채 3.02%와 3.18%, 실업률 2.8%와 2.9%도 마찬가지다. 수준을
 * 나타내는 지표에 0은 읽을 것이 없는 기준선이다.
 *
 * 그렇다고 언제나 자료 범위에 맞추면 반대 잘못을 저지른다. 소매판매액지수가 -0.4에서
 * 0.5로 갈 때 0은 **부호가 뒤집힌 자리**라 반드시 보여야 한다.
 *
 * 그래서 **0을 포함해도 변화가 읽히면 포함한다**. 0까지 늘린 축에서 세 값의 변화가 여전히
 * 높이의 30% 이상을 쓰면 0을 넣고, 그보다 눌리면 뺀다.
 *
 * 기준을 '자료 폭의 몇 배'가 아니라 '변화가 쓰는 비율'로 적은 까닭이 있다. 앞의 방식으로
 * 쓰면 전산업생산(0.8~1.2)·수출(3.2~4.8)·건설기성액(-1.2~-0.8)이 경계에 정확히 앉아,
 * `1.2 - 0.8`이 `0.4`가 아니라 `0.399...`가 되는 부동소수점 오차로 결과가 뒤집힌다.
 *
 * 단위로는 가를 수 없다. 실업률과 국고채는 `%`지만 수준이고 소매판매액지수는 `%`지만
 * 변화율이다. 계약에도 둘을 구분하는 값이 없으므로 자료의 생김새로 정한다.
 */
/** 0을 넣고도 변화를 읽을 수 있는 최소 비율이다. */
const MIN_SHARE_WITH_ZERO = 0.28

export function buildAxisDomain(values: readonly number[]): {
  domain: [number, number]
  includesZero: boolean
} {
  if (values.length === 0) return { domain: [0, 1], includesZero: true }

  const min = Math.min(...values)
  const max = Math.max(...values)
  const spread = max - min

  // 세 값이 모두 같다. 자료 폭이 없으니 값 주위로 좁은 띠를 두고 평평하게 그린다.
  if (spread === 0) {
    const pad = Math.abs(min) * 0.1 || 1
    return { domain: [min - pad, max + pad], includesZero: min === 0 }
  }

  // 0이 자료 사이에 있으면 고민할 것이 없다. 부호가 뒤집힌 자리다.
  if (min <= 0 && 0 <= max) return { domain: [min, max], includesZero: true }

  // 0까지 늘렸을 때 변화가 축에서 차지할 비율이다.
  const distanceToZero = min > 0 ? min : -max
  if (spread / (spread + distanceToZero) >= MIN_SHARE_WITH_ZERO) {
    return { domain: min > 0 ? [0, max] : [min, 0], includesZero: true }
  }

  // 0을 빼고 자료에 맞춘다. 점이 가장자리에 붙지 않게 조금 띄운다.
  const pad = spread * 0.15
  return { domain: [min - pad, max + pad], includesZero: false }
}

/**
 * 최근 오르내림. 값이 있는 마지막 두 달을 견준다. 선 색(상승 빨강·하락 파랑)에만 쓰고 좋고 나쁨을 뜻하지 않는다 —
 * 실업률이 오르는 것도 빨강이다. 비교할 두 달이 없거나 같으면 방향을 달지 않는다.
 */
export function recentDirection(path: IndicatorDisplay['path']): 'up' | 'down' | undefined {
  const values = path.flatMap(({ value }) => (value === null ? [] : [value]))
  if (values.length < 2) return undefined
  const difference = values[values.length - 1] - values[values.length - 2]
  return difference > 0 ? 'up' : difference < 0 ? 'down' : undefined
}

/**
 * 지표 카드의 출처 줄. `출처 {출처}`와 `작성기관 {기관}`을 따로 적는다(FR-015). 출처 이름이 작성기관을 되풀이하면
 * (`OECD Composite Leading Indicators` · `OECD`) 두 번 적지 않고 `출처·작성기관 OECD` 하나로 합친다.
 */
export function sourceParts(agency: string, source: string): string[] {
  const a = agency.trim()
  const b = source.trim()
  if (!a && !b) return ['출처 확인할 수 없음']
  if (!a) return [`출처 ${b}`]
  if (!b) return [`작성기관 ${a}`]
  const la = a.toLowerCase()
  const lb = b.toLowerCase()
  if (la === lb || lb.includes(la) || la.includes(lb)) return [`출처·작성기관 ${a}`]
  return [`출처 ${b}`, `작성기관 ${a}`]
}

/** 세로축 범위 설명. 0을 뺀 축이면 범위를 밝힌다 — 밝히지 않으면 작은 변화가 큰 변화처럼 읽힌다. */
export function describeAxis(indicator: IndicatorDisplay): string {
  const values = indicator.path.flatMap(({ value }) => (value === null ? [] : [value]))
  const axis = buildAxisDomain(values)
  return axis.includesZero
    ? '세로축이 0을 포함합니다.'
    : `세로축은 ${formatObservation(axis.domain[0], indicator.unit)}~${formatObservation(axis.domain[1], indicator.unit)}${indicator.unit}입니다. 0을 빼고 값에 맞춰 그렸으니 기울기가 실제보다 커 보일 수 있습니다.`
}

/** 분야 자료 공급 상태의 표 글자. */
export const fieldStateLabels: Record<FieldStatus, string> = {
  READY: '자료 확인',
  EXAMPLE: '예시 자료',
  EMPTY: '표시할 자료 없음',
  COLLECTION_FAILED: '최근 수집 실패',
}

/** 목록 한 줄에 다는 상태 표. 정상 공급이면 없고, 수집 실패·자료 없음과 예시 자료만 단다. */
export function stateLineLabels(status: FieldStatus, isExampleData = false): string[] {
  return [
    status === 'READY' ? null : fieldStateLabels[status],
    isExampleData && status !== 'EXAMPLE' ? fieldStateLabels.EXAMPLE : null,
  ].filter((label): label is string => label !== null)
}

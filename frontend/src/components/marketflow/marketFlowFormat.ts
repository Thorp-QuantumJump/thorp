import type { IndicatorDisplay } from '@/types/marketFlow'

/** 표시만 담당한다. 반올림한 값으로 경로나 변화 방향을 계산하지 않는다. */
export function formatObservation(value: number | null): string {
  return value === null ? '자료 없음' : value.toLocaleString('ko-KR', { maximumFractionDigits: 10 })
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

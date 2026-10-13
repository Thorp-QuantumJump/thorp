import type { MedalKind } from './medalShapes'

/**
 * 손에 쥐는 물건(레벨 표장·배지 메달·단계 완주 메달)의 재질(013 design-notes §3.3·§4, 목업 `b-` 그라디언트).
 *
 * <p>빛은 늘 왼쪽 위에서 온다. 표장은 초록 금속, 메달 테두리는 은록 금속, 메달 면은 갈래별 에나멜이다. 금·은·동
 * 서열과 빨강·파랑은 쓰지 않는다(§8).
 *
 * <p><b>식별자는 그림마다 따로다.</b> 같은 그림이 한 화면에 여러 번 그려지므로 부르는 쪽이 `useId`로 만든 앞머리를
 * 넘기고, 여기서 `{앞머리}-{재질}`로 붙인다. <b>의미 토큰과 같은 색은 값으로 쓰지 않는다.</b> 브랜드 초록 두 단은
 * `materials.module.css`의 클래스가 토큰으로 칠한다.
 */

/**
 * 재질 이름. 목업의 그라디언트 식별자와는 이름이 다르다(목업 `b-metal2`는 `metalPlain`, `b-hl`은 `highlight`, `b-pin`은
 * `bevel`).
 *
 * - `gmetal`·`gtext`·`face`: 레벨 표장의 초록 금속, 숫자, 안쪽 면
 * - `metal`: 메달 테두리의 은록 금속. 왼쪽 위에 따뜻한 빛이 한 번 들어온다
 * - `metalPlain`: 같은 은록 금속에서 따뜻한 빛을 뺀 것. 읽은 글 메달이 금·황동으로 읽히지 않게 쓴다
 * - `glass`: 단계 완주 메달 색유리의 광택
 * - `highlight`: 받은 메달 면 위쪽의 흰 빛
 * - `bevel`: 받은 메달 면 테두리의 빛(위)과 그늘(아래)
 * - `sweep`·`shadow`·`glow`: 지나가는 흰 띠, 받은 물건 밑 그림자, 초록 빛 점
 * - `en-*`: 갈래별 에나멜 면
 */
export type Material =
  | 'gmetal'
  | 'gtext'
  | 'face'
  | 'metal'
  | 'metalPlain'
  | 'glass'
  | 'bevel'
  | 'highlight'
  | 'sweep'
  | 'shadow'
  | 'glow'
  | `en-${Exclude<MedalKind, 'level' | 'stage'>}`

/** 그라디언트의 한 단. 의미 토큰과 같은 색은 값 대신 토큰 이름으로 둔다(`materials.module.css`가 칠한다). */
export type GradientStop =
  { offset: number; color: string } | { offset: number; token: 'brandSoft' | 'brand' }

/**
 * 레벨 숫자의 초록 금속 글자색(목업 `b-gtext`). 위의 밝은 민트에서 아래의 짙은 초록으로 간다. 레벨 표장의 숫자(SVG)와
 * 완료 카드의 넘어가는 숫자(HTML 글자, `cssLinearGradient`)가 같은 단을 쓴다.
 */
export const GTEXT_STOPS: readonly GradientStop[] = [
  { offset: 0, color: '#EFFFF5' },
  { offset: 0.38, color: '#9DF5C2' },
  { offset: 0.7, token: 'brandSoft' },
  { offset: 1, color: '#0B8F45' },
]

const TOKEN_COLORS = {
  brandSoft: 'var(--thorp-brand-soft)',
  brand: 'var(--thorp-brand-primary)',
} as const

/** 그라디언트 단을 CSS `linear-gradient`로 옮긴다. HTML 글자에 같은 재질을 칠할 때 쓴다. */
export function cssLinearGradient(angle: string, stops: readonly GradientStop[]): string {
  const parts = stops.map((stop) => {
    const color = 'token' in stop ? TOKEN_COLORS[stop.token] : stop.color
    return `${color} ${Math.round(stop.offset * 100)}%`
  })
  return `linear-gradient(${angle}, ${parts.join(', ')})`
}

/** 그림 안에서 재질을 가리키는 `url(#…)`. */
export function paint(prefix: string, material: Material): string {
  return `url(#${prefix}-${material})`
}

/** SVG `id`로 쓸 수 있게 `useId` 값을 다듬는다. */
export function svgId(raw: string): string {
  return raw.replace(/[^a-zA-Z0-9_-]/g, '')
}

export const ENAMEL: Record<
  Exclude<MedalKind, 'level' | 'stage' | 'special'>,
  [string, string, string]
> = {
  // 읽은 글: 노란 기운을 뺀 세이지(금·황동으로 읽히지 않게)
  read: ['#D6EDD5', '#9BCFA0', '#5D9468'],
  first: ['#D2EEAE', '#A6D86B', '#6E9A3F'],
  all: ['#A9E6E8', '#5CC6CC', '#358A8F'],
  terms: ['#EBD6F8', '#D2A6F0', '#9469B3'],
}

/** 갈래별 에나멜의 가운데 색. 리본 빛처럼 면 밖의 꾸밈에 쓴다. */
export function enamelColor(kind: MedalKind): string | null {
  return kind in ENAMEL ? ENAMEL[kind as keyof typeof ENAMEL][1] : null
}

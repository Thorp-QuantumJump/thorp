import type { MedalKind } from './medalShapes'

/**
 * 손에 쥐는 물건(레벨 표장·배지 메달·단계 완주 메달)의 재질(013 design-notes §3.3·§4, 목업 `b-` 그라디언트).
 *
 * <p>빛은 늘 왼쪽 위에서 온다. 표장은 초록 금속, 메달 테두리는 은록 금속, 메달 면은 갈래별 에나멜이다. 금·은·동
 * 서열과 빨강·파랑은 쓰지 않는다(§8).
 *
 * <p><b>식별자는 그림마다 따로다.</b> 같은 그림이 한 화면에 여러 번 그려지므로 부르는 쪽이 `useId`로 만든 앞머리를
 * 넘기고, 여기서 `{앞머리}-{재질}`로 붙인다. <b>의미 토큰과 같은 색은 값으로 쓰지 않는다.</b> 브랜드 초록 두 단은
 * `materials.module.css`의 클래스가 토큰으로 칠한다(디자인 전환 `classic`을 함께 따른다).
 */

export type Material =
  | 'gmetal'
  | 'gtext'
  | 'face'
  | 'metal'
  | 'metal2'
  | 'glass'
  | 'pin'
  | 'hl'
  | 'sweep'
  | 'shadow'
  | 'glow'
  | `en-${Exclude<MedalKind, 'level' | 'stage'>}`

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

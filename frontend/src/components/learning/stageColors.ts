/**
 * 단계 장식 색(013 design-notes §4 표).
 *
 * <p>그림·띠의 옅은 빛·도장 잉크 고리·스테인드글라스에만 쓰고 상태를 나타내지 않는다. 빨강·파랑은 값의 오르내림을
 * 뜻하므로 넣지 않는다. 표에 없는 단계 번호(단계를 더하는 spec 개정 전)는 흐린 글자색 토큰으로 그린다.
 *
 * <p>돌려주는 값은 CSS 색 값이라 `var()`·`color-mix()`가 들어갈 수 있다. SVG 표현 속성(`fill="…"`)에 바로 넣지 말고
 * CSS나 `style`(사용자 정의 속성 포함)로 쓴다.
 */
const STAGE_COLORS: Record<number, string> = {
  1: '#5FD3A6',
  2: '#B7DD6E',
  3: '#D4A2EA',
  4: '#5CC6CC',
  5: '#C9C67C',
  6: '#DDA3DA',
}

const NEUTRAL = 'var(--thorp-muted)'

export function stageColor(stageNumber: number): string {
  return STAGE_COLORS[stageNumber] ?? NEUTRAL
}

/**
 * 단계 색의 hex 값. 스테인드글라스처럼 단계 색을 밝히고 어둡게 섞어 여러 조각을 만드는 그림이 쓴다. 표에 없는
 * 단계면 `null`이고, 그 그림은 단계 색 없이 그린다.
 */
export function stageHex(stageNumber: number): string | null {
  return STAGE_COLORS[stageNumber] ?? null
}

/**
 * 준비 중 단계의 풍경과 머리 글자처럼 단계 색을 흐린 글자색 쪽으로 낮춘 색. 단계 색 45%다(목업 `low`). 이 비율을 쓰는
 * 곳은 이 함수 하나다.
 */
export function mutedStageColor(stageNumber: number): string {
  return `color-mix(in srgb, ${stageColor(stageNumber)} 45%, ${NEUTRAL})`
}

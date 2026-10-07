/**
 * 단계 장식 색(013 design-notes §4 표).
 *
 * <p>그림·띠의 옅은 빛·도장 잉크 고리·스테인드글라스에만 쓰고 상태를 나타내지 않는다. 빨강·파랑은 값의 오르내림을
 * 뜻하므로 넣지 않는다. 표에 없는 단계 번호(단계를 더하는 spec 개정 전)는 회색으로 그린다.
 */
const STAGE_COLORS: Record<number, string> = {
  1: '#5FD3A6',
  2: '#B7DD6E',
  3: '#D4A2EA',
  4: '#5CC6CC',
  5: '#C9C67C',
  6: '#DDA3DA',
}

const NEUTRAL = '#8B928E'

export function stageColor(stageNumber: number): string {
  return STAGE_COLORS[stageNumber] ?? NEUTRAL
}

function channels(hex: string): [number, number, number] {
  const value = hex.replace('#', '')
  return [0, 2, 4].map((i) => parseInt(value.slice(i, i + 2), 16)) as [number, number, number]
}

/** 준비 중 단계의 풍경처럼 단계 색을 회색 쪽으로 낮춘 색. `weight`는 단계 색의 몫이다. */
export function mutedStageColor(stageNumber: number, weight = 0.45): string {
  const a = channels(stageColor(stageNumber))
  const b = channels(NEUTRAL)
  return `#${a
    .map((v, i) => Math.round(v * weight + b[i] * (1 - weight)).toString(16).padStart(2, '0'))
    .join('')}`
}

import type { DiagramSeries } from '@/types/learning'

/** 계열마다 선 모양을 달리해 색이 없어도 구분되게 한다. 빨강·파랑은 쓰지 않는다(design-notes §4). */
export const SERIES_STYLES = [
  { className: 'diagramSeries0', dash: undefined },
  { className: 'diagramSeries1', dash: '6 4' },
  { className: 'diagramSeries2', dash: '2 4' },
] as const

/**
 * 그릴 수 있는 수치인가. 서버가 저장할 때 검사하지만(data-model §5-3), 모양이 어긋난 판이 와도 빈 그림이나
 * 잘못된 그림을 그리지 않고 설명 문장만 보이려고 화면에서 한 번 더 본다(FR-026).
 */
export function isDrawable(categories: readonly string[], series: readonly DiagramSeries[]): boolean {
  return (
    categories.length >= 2 &&
    series.length >= 1 &&
    series.length <= SERIES_STYLES.length &&
    series.every(
      (item) =>
        item.values.length === categories.length && item.values.every((value) => Number.isFinite(value)),
    )
  )
}

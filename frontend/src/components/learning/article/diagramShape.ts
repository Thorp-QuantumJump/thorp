import type { DiagramKind, DiagramSeries } from '@/types/learning'

/** 계열마다 선 모양을 달리해 색이 없어도 구분되게 한다. 빨강·파랑은 쓰지 않는다(design-notes §4). */
export const SERIES_STYLES = [
  { className: 'diagramSeries0', dash: undefined },
  { className: 'diagramSeries1', dash: '6 4' },
  { className: 'diagramSeries2', dash: '2 4' },
] as const

/**
 * 그릴 수 있는 수치인가. 서버가 저장할 때 검사하지만(data-model §5-3), 모양이 어긋난 판이 와도 빈 그림이나
 * 잘못된 그림을 그리지 않고 설명 문장만 보이려고 화면에서 한 번 더 본다(FR-026). 이 화면이 모르는 도식
 * 종류(`kind`가 null)도 그리지 않는다. 서버가 종류를 더해도 글 전체가 실패하지 않고 설명 문장이 남는다.
 */
export function isDrawable(
  kind: DiagramKind | null,
  categories: readonly string[],
  series: readonly DiagramSeries[],
): boolean {
  return (
    kind !== null &&
    categories.length >= 2 &&
    series.length >= 1 &&
    series.length <= SERIES_STYLES.length &&
    series.every(
      (item) =>
        item.values.length === categories.length && item.values.every((value) => Number.isFinite(value)),
    )
  )
}

/** 한글·한자·전각 글자는 글자 크기만큼, 그 밖의 글자는 그보다 좁게 잡는다. 겹침을 피하려는 어림값이다. */
const WIDE_CHARACTER = /[ᄀ-ᇿ⺀-鿿가-힯豈-﫿＀-￯]/u

/** 항목 이름의 대략 폭(viewBox 단위). */
export function estimateLabelWidth(text: string, fontSize: number): number {
  let units = 0
  for (const character of text) units += WIDE_CHARACTER.test(character) ? 1 : 0.62
  return units * fontSize
}

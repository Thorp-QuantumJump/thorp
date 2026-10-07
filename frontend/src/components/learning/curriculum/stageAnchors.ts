import type { RoadPointKind } from './articleProgress'

/**
 * 커리큘럼 화면의 DOM 표지 규약(013 T137·T139·T203).
 *
 * <p>단계 자리, 그 자리에서 초점을 받는 제목, 짚을 띠, 길이 지나는 점을 이 모듈만 안다. 부품은 아래 함수가 돌려주는
 * 속성을 펼쳐 붙이고, 지도는 `readRoadPoints`로 길 점을 읽고, 화면은 `moveToStage`로 단계 자리에 옮긴다. 표지 이름을
 * 바꿀 때는 이 파일과, 띠를 짚는 모습을 그리는 `StageBand.module.css`의 `[data-flash]` 규칙만 고친다.
 */

/**
 * 단계 자리. 커리큘럼 보기의 단계 구역과 목록 보기의 단계 묶음(표의 `tbody`, 좁은 화면 목록의 항목)에 붙인다. 두
 * 모양을 함께 그리는 목록 보기에서는 같은 번호의 자리가 둘이고, 옮길 때 화면에 보이는 쪽을 고른다.
 */
export function stageAnchorProps(stageNumber: number) {
  return { 'data-stage-anchor': stageNumber }
}

/** 단계 자리로 옮겨 오면 초점을 받는 제목. `tabIndex={-1}`과 함께 붙인다. */
export const stageHeadingProps = { 'data-stage-heading': '' } as const

/** 공개된 단계의 띠. 단계 자리로 옮겨 오면 잠시 짚는다. 준비 중 띠에는 움직임이 없으므로 붙이지 않는다. */
export const stageBandProps = { 'data-stage-band': '' } as const

/** 길이 지나는 점. `gap`이면 이 점 앞에서 길을 끊고 끝 표시를 둔다(공개 경계). */
export function roadPointProps(kind: RoadPointKind, gap = false) {
  return gap ? { 'data-road': kind, 'data-gap': '' } : { 'data-road': kind }
}

export type RoadPointElement = { element: HTMLElement; kind: RoadPointKind; gap: boolean }

const ROAD_POINT_KINDS: readonly RoadPointKind[] = ['read', 'unread', 'neutral', 'preparing', 'start']

/** 지도 안의 길 점을 문서 순서대로 읽는다. 모르는 종류는 중립 길로 본다. */
export function readRoadPoints(map: HTMLElement): RoadPointElement[] {
  return Array.from(map.querySelectorAll<HTMLElement>('[data-road]')).map((element) => {
    const raw = element.getAttribute('data-road')
    return {
      element,
      kind: ROAD_POINT_KINDS.find((kind) => kind === raw) ?? 'neutral',
      gap: element.hasAttribute('data-gap'),
    }
  })
}

/** 띠를 짚는 시간(ms). 목업의 `.band.flash`와 같다. */
const STAGE_FLASH_MS = 600

/**
 * 단계 자리로 옮기고 그 제목에 초점을 둔다. 좁은 화면 목록처럼 같은 자리가 둘이면 화면에 보이는 쪽을 고른다.
 *
 * <p>긴 화면을 부드럽게 굴리면 움직임 상한(1초)을 넘기므로 바로 옮긴다(design-notes §5). 움직임 줄이기 설정이
 * 아니면 커리큘럼 보기의 그 띠를 600ms 동안 단계 색으로 짚는다(목업 `flash`).
 */
export function moveToStage(stageNumber: number): void {
  const candidates = Array.from(document.querySelectorAll<HTMLElement>(`[data-stage-anchor="${stageNumber}"]`))
  const target = candidates.find((el) => el.getClientRects().length > 0) ?? candidates[0]
  if (!target) return
  target.scrollIntoView?.({ block: 'start' })
  target.querySelector<HTMLElement>('[data-stage-heading]')?.focus({ preventScroll: true })
  const motion = window.matchMedia?.('(prefers-reduced-motion: no-preference)').matches ?? false
  const band = target.querySelector<HTMLElement>('[data-stage-band]')
  if (band && motion) {
    band.setAttribute('data-flash', '')
    window.setTimeout(() => band.removeAttribute('data-flash'), STAGE_FLASH_MS)
  }
}

/**
 * 플로팅 버튼의 자리(spec 015 FR-002). 넓은 화면에서 끌어 놓으면 가까운 쪽 여백에 붙고 세로 위치는 화면
 * 안에서 자유다. 브라우저 `localStorage`에 기억한다 — 대화는 남기지 않고 버튼 자리만 남는다(FR-014).
 */
export type FabSide = 'left' | 'right'

export type FabPosition = {
  side: FabSide
  /** 화면 위에서 버튼 위쪽까지의 px */
  y: number
}

export const FAB_POSITION_KEY = 'thorp.assistant.fabPosition'
export const FAB_SIZE = 56
export const FAB_MARGIN = 16
/** 이만큼 못 움직였으면 끌기가 아니라 누른 것이다 */
export const DRAG_THRESHOLD_PX = 4

export function clampFabY(y: number, viewportHeight: number): number {
  const max = Math.max(FAB_MARGIN, viewportHeight - FAB_SIZE - FAB_MARGIN)
  return Math.min(max, Math.max(FAB_MARGIN, Math.round(y)))
}

export function defaultFabPosition(viewportHeight: number): FabPosition {
  return { side: 'right', y: clampFabY(viewportHeight - FAB_SIZE - 24, viewportHeight) }
}

/** 놓은 자리가 화면 가운데보다 왼쪽이면 왼쪽 여백, 아니면 오른쪽 여백이다. */
export function snapSide(clientX: number, viewportWidth: number): FabSide {
  return clientX < viewportWidth / 2 ? 'left' : 'right'
}

function storage(): Storage | null {
  try {
    return window.localStorage
  } catch {
    return null
  }
}

export function loadFabPosition(viewportHeight: number): FabPosition {
  const fallback = defaultFabPosition(viewportHeight)
  try {
    const raw = storage()?.getItem(FAB_POSITION_KEY)
    if (!raw) return fallback
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return fallback
    const { side, y } = parsed as { side?: unknown; y?: unknown }
    if ((side !== 'left' && side !== 'right') || typeof y !== 'number' || !Number.isFinite(y)) {
      return fallback
    }
    return { side, y: clampFabY(y, viewportHeight) }
  } catch {
    return fallback
  }
}

export function saveFabPosition(position: FabPosition): void {
  try {
    storage()?.setItem(FAB_POSITION_KEY, JSON.stringify(position))
  } catch {
    // 저장소가 막혀 있으면 이번 세션 동안만 기억한다.
  }
}

/**
 * 배우기 목록에서 고른 보기(커리큘럼·목록)를 탭 동안 기억한다(013 FR-023).
 *
 * <p><b>학습 기록이 아니라 화면 상태다.</b> 서버에 보내지 않고 그 탭의 `sessionStorage`에만 둔다. 새 탭은
 * 커리큘럼 보기로 시작하고, 새로 고침·같은 탭 안의 이동·로그인 왕복은 버틴다. 저장 방식은 002
 * `diagnosis/selectionStorage.ts`와 같다(내용 버전, 모든 접근을 try/catch로 감쌈).
 */

/** 014의 `thorp.auth.returnTarget`과 같은 이름 규칙이다. */
export const LEARNING_VIEW_STORAGE_KEY = 'thorp.learning.view'

/** 내용 형식의 번호다. 형식을 바꾸면 올린다. */
export const LEARNING_VIEW_PAYLOAD_VERSION = 1

export type LearningView = 'curriculum' | 'list'

/** 새 탭과 읽을 수 없는 값의 보기다(FR-023 "기본은 커리큘럼 보기"). */
export const DEFAULT_LEARNING_VIEW: LearningView = 'curriculum'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/**
 * 기억한 보기를 읽는다. 없거나 읽을 수 없거나 저장소가 막혀 있으면 커리큘럼 보기다.
 *
 * 사용자가 고친 값이나 다른 판의 값을 그대로 믿지 않는다. 알려진 두 값이 아니면 기본 보기로 시작한다.
 * 화면 상태라 잃어도 사용자가 다시 누르면 되므로 따로 알리지 않는다.
 */
export function readStoredView(): LearningView {
  let raw: string | null
  try {
    raw = window.sessionStorage.getItem(LEARNING_VIEW_STORAGE_KEY)
  } catch {
    return DEFAULT_LEARNING_VIEW
  }
  if (raw === null) return DEFAULT_LEARNING_VIEW

  let payload: unknown
  try {
    payload = JSON.parse(raw)
  } catch {
    return DEFAULT_LEARNING_VIEW
  }
  if (!isRecord(payload) || payload.version !== LEARNING_VIEW_PAYLOAD_VERSION) {
    return DEFAULT_LEARNING_VIEW
  }
  return payload.view === 'list' || payload.view === 'curriculum'
    ? payload.view
    : DEFAULT_LEARNING_VIEW
}

/** 고른 보기를 기억한다. 저장소가 막혀 있으면 이 화면 안에서만 바뀌고 오류를 내지 않는다. */
export function writeStoredView(view: LearningView): void {
  try {
    window.sessionStorage.setItem(
      LEARNING_VIEW_STORAGE_KEY,
      JSON.stringify({ version: LEARNING_VIEW_PAYLOAD_VERSION, view }),
    )
  } catch {
    // 저장소가 막힌 환경에서는 기억하지 못할 뿐 화면은 그대로 동작한다.
  }
}

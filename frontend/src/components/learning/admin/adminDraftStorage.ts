import type { AdminLearningDraft } from '@/types/learningAdmin'

/**
 * 관리 화면에서 저장하지 않은 편집 내용을 탭 세션 동안 보관한다(013 research R12).
 *
 * <p><b>저장 요청이 401로 끝나도 지우지 않는다.</b> 편집 중에 세션이 끝나면 작성하던 내용을 말없이
 * 버리지 않는다는 것이 이 보관의 목적이다(spec Edge Cases, 원칙 VI). 지우는 시점은 저장에
 * 성공했을 때 하나뿐이다.
 *
 * <p><b>읽기·쓰기 실패를 오류로 다루지 않는다.</b> 저장소를 막아 둔 브라우저에서도 편집은 되어야
 * 한다. 보관은 편의이지 편집의 전제가 아니다(002 `diagnosis/selectionStorage.ts`와 같은 태도).
 *
 * <p>일반 사용자 배우기 화면은 저장소에 아무것도 쓰지 않는다(013 FR-012).
 */

/** 014 `thorp.auth.returnTarget`·002 `thorp.diagnosis.sectorSelection`과 같은 이름 규칙이다. */
export const ADMIN_DRAFT_KEY_PREFIX = 'thorp.learning.adminDraft.'

/** 페이로드 형식의 번호다. 형식을 바꾸면 올린다. */
export const ADMIN_DRAFT_PAYLOAD_VERSION = 1

export type UnsentDraft = {
  version: number
  /** ISO 8601 시각. 복원할지 묻는 자리에 보인다 */
  savedAt: string
  draft: AdminLearningDraft
  /** 보관할 때 화면이 마지막으로 읽은 초안 번호다. 복원해도 이 값으로 저장을 다시 보내지 않는다 */
  draftVersion: number
}

export function adminDraftKey(articleId: string): string {
  return `${ADMIN_DRAFT_KEY_PREFIX}${articleId}`
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** 저장하지 않은 편집 내용을 보관한다. 실패해도 편집을 막지 않는다. */
export function saveUnsentDraft(
  articleId: string,
  draft: AdminLearningDraft,
  draftVersion: number,
): void {
  const payload: UnsentDraft = {
    version: ADMIN_DRAFT_PAYLOAD_VERSION,
    savedAt: new Date().toISOString(),
    draft,
    draftVersion,
  }
  try {
    window.sessionStorage.setItem(adminDraftKey(articleId), JSON.stringify(payload))
  } catch {
    // 저장소를 쓸 수 없는 브라우저다. 편집은 그대로 이어진다.
  }
}

/**
 * 보관본을 읽는다.
 *
 * <p><b>형식이 다르면 고쳐서 쓰지 않고 없는 것으로 다룬다.</b> 반쯤 읽은 초안을 복원하면 운영자가
 * 무엇이 빠졌는지 모르는 채로 저장하게 된다. 다만 내용의 길이·값 범위는 보지 않는다. 그 판정은
 * 서버가 하고(계약 §5), 쓰다 만 값도 편집기에 돌아와야 한다.
 */
export function loadUnsentDraft(articleId: string): UnsentDraft | null {
  let raw: string | null = null
  try {
    raw = window.sessionStorage.getItem(adminDraftKey(articleId))
  } catch {
    return null
  }
  if (!raw) return null

  try {
    const parsed: unknown = JSON.parse(raw)
    if (!isRecord(parsed) || parsed.version !== ADMIN_DRAFT_PAYLOAD_VERSION) return null
    if (typeof parsed.savedAt !== 'string' || typeof parsed.draftVersion !== 'number') return null
    if (!isRecord(parsed.draft)) return null
    const draft = parsed.draft
    if (typeof draft.title !== 'string' || typeof draft.question !== 'string') return null
    if (typeof draft.readingOrder !== 'number') return null
    if (!Array.isArray(draft.blocks) || !Array.isArray(draft.sources) || !Array.isArray(draft.terms))
      return null

    return {
      version: ADMIN_DRAFT_PAYLOAD_VERSION,
      savedAt: parsed.savedAt,
      draft: draft as unknown as AdminLearningDraft,
      draftVersion: parsed.draftVersion,
    }
  } catch {
    return null
  }
}

/** 저장에 성공했을 때만 부른다. 401 뒤에는 부르지 않는다(R12). */
export function clearUnsentDraft(articleId: string): void {
  try {
    window.sessionStorage.removeItem(adminDraftKey(articleId))
  } catch {
    // 지우지 못해도 다음 저장 성공에서 다시 시도한다.
  }
}

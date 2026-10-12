import { readDraftShape, readText, ShapeError } from '@/api/learningAdminShape'
import type { RenamedQuestion, TransferredTerm } from '@/types/learningAdmin'
import type { EditableDraft } from './editableDraft'

/**
 * 관리 화면에서 저장하지 않은 편집 내용을 탭 세션 동안 보관한다(013 research R12).
 *
 * <p><b>저장 요청이 401로 끝나도 지우지 않는다.</b> 편집 중에 세션이 끝나면 작성하던 내용을 말없이
 * 버리지 않는다는 것이 이 보관의 목적이다(spec Edge Cases, 원칙 VI). 지우는 것은 편집 화면 하나이고, 그때는
 * 저장에 성공했을 때, 운영자가 '버리기'를 골랐을 때, 이전 내용 버전 보관본을 버렸다고 알릴 때뿐이다.
 *
 * <p><b>읽기·쓰기 실패를 오류로 다루지 않는다.</b> 저장소를 막아 둔 브라우저에서도 편집은 되어야
 * 한다. 보관은 편의이지 편집의 전제가 아니다(002 `diagnosis/selectionStorage.ts`와 같은 태도).
 *
 * <p><b>(2판) 내용 버전 2다.</b> 편집기 초안(형식·배치·확인 문제, 글자 조각 필드는 편집 문법 문자열)을
 * 보관한다(data-model §15). 버전 1 보관본은 형식과 배치가 없어 2판 초안으로 옮길 수 없다. 읽기는 그 사실만
 * 돌려주고 지우지 않는다. 편집 화면이 복원하지 않는다고 알린 뒤 지운다. 읽는 쪽(단계 공개 뒤 보관본 고치기 등)이
 * 몰래 지우면 운영자가 그 사실을 끝내 모른다.
 *
 * <p>일반 사용자 배우기 화면은 저장소에 아무것도 쓰지 않는다(013 FR-012).
 */

/** 014 `thorp.auth.returnTarget`·002 `thorp.diagnosis.sectorSelection`과 같은 이름 규칙이다. */
export const ADMIN_DRAFT_KEY_PREFIX = 'thorp.learning.adminDraft.'

/** 페이로드 형식의 번호다. 형식을 바꾸면 올린다. */
export const ADMIN_DRAFT_PAYLOAD_VERSION = 2

export type UnsentDraft = {
  version: number
  /** ISO 8601 시각. 복원할지 묻는 자리에 보인다 */
  savedAt: string
  draft: EditableDraft
  /**
   * 보관한 초안이 기반한 번호다. 복원하면 저장·게시가 이 번호를 싣는다(R12).
   *
   * 서버의 지금 번호로 바꿔 싣지 않는다. 그러면 보관한 뒤 다른 곳에서 저장한 초안을 409 없이 덮는다.
   */
  draftVersion: number
}

export type UnsentDraftLookup =
  | { status: 'none' }
  | { status: 'found'; unsent: UnsentDraft }
  /** 이전 내용 버전의 보관본이 있다. 복원할 수 없으며, 편집 화면이 알린 뒤 지운다 */
  | { status: 'outdated' }

export function adminDraftKey(articleId: string): string {
  return `${ADMIN_DRAFT_KEY_PREFIX}${articleId}`
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function write(articleId: string, payload: UnsentDraft): void {
  try {
    window.sessionStorage.setItem(adminDraftKey(articleId), JSON.stringify(payload))
  } catch {
    // 저장소를 쓸 수 없는 브라우저다. 편집은 그대로 이어진다.
  }
}

/** 저장하지 않은 편집 내용을 보관한다. 실패해도 편집을 막지 않는다. */
export function saveUnsentDraft(
  articleId: string,
  draft: EditableDraft,
  draftVersion: number,
): void {
  write(articleId, {
    version: ADMIN_DRAFT_PAYLOAD_VERSION,
    savedAt: new Date().toISOString(),
    draft,
    draftVersion,
  })
}

/**
 * 보관한 초안의 모양을 확인한다. 모양은 서버 응답·원고와 같은 `api/learningAdminShape.ts`가 정한다.
 *
 * <p><b>형식이 다르면 고쳐서 쓰지 않고 없는 것으로 다룬다.</b> 반쯤 읽은 초안을 복원하면 운영자가
 * 무엇이 빠졌는지 모르는 채로 저장하게 된다. 다만 내용의 길이·값 범위는 보지 않는다. 그 판정은
 * 서버가 하고(계약 §5), 쓰다 만 값도 편집기에 돌아와야 한다.
 */
function readStoredDraft(value: unknown): EditableDraft | null {
  try {
    return readDraftShape(value, 'draft', { rich: readText, strict: false })
  } catch (error) {
    if (error instanceof ShapeError) return null
    throw error
  }
}

/** 보관본을 읽기만 한다. 이전 내용 버전이면 그 사실만 돌려주고 지우지 않는다. */
export function readUnsentDraft(articleId: string): UnsentDraftLookup {
  let raw: string | null = null
  try {
    raw = window.sessionStorage.getItem(adminDraftKey(articleId))
  } catch {
    return { status: 'none' }
  }
  if (!raw) return { status: 'none' }

  try {
    const parsed: unknown = JSON.parse(raw)
    if (!isRecord(parsed)) return { status: 'none' }
    if (typeof parsed.version === 'number' && parsed.version < ADMIN_DRAFT_PAYLOAD_VERSION) {
      return { status: 'outdated' }
    }
    if (parsed.version !== ADMIN_DRAFT_PAYLOAD_VERSION) return { status: 'none' }
    if (typeof parsed.savedAt !== 'string' || typeof parsed.draftVersion !== 'number') {
      return { status: 'none' }
    }
    const draft = readStoredDraft(parsed.draft)
    if (!draft) return { status: 'none' }

    return {
      status: 'found',
      unsent: {
        version: ADMIN_DRAFT_PAYLOAD_VERSION,
        savedAt: parsed.savedAt,
        draft,
        draftVersion: parsed.draftVersion,
      },
    }
  } catch {
    return { status: 'none' }
  }
}

/**
 * 저장에 성공했을 때, 운영자가 '버리기'를 골랐을 때, 이전 내용 버전 보관본을 알린 뒤에만 부른다. 401 뒤에는
 * 부르지 않는다(R12).
 */
export function clearUnsentDraft(articleId: string): void {
  try {
    window.sessionStorage.removeItem(adminDraftKey(articleId))
  } catch {
    // 지우지 못해도 다음 저장 성공에서 다시 시도한다.
  }
}

/**
 * 게시가 초안에 한 일을 편집 내용에도 한다(계약 §6·§11, data-model §6-3·§7-2).
 *
 * <p>게시는 초안 번호를 올리지 않고 초안의 문제 식별자를 바꾸고(`renamedQuestions`) 넘겨받기를 마친 용어의
 * `transferFrom`을 비운다(`transferredTerms` 가운데 `toArticleId`가 이 글인 것). 게시 전에 들고 있던 편집
 * 내용을 그대로 저장하면 옛 식별자는 400, 옛 `transferFrom`은 409다. 그래서 같은 두 가지를 여기서 고친다.
 * 다른 것은 바꾸지 않는다.
 */
export function applyPublishedChanges(
  draft: EditableDraft,
  articleId: string,
  renamedQuestions: readonly RenamedQuestion[],
  transferredTerms: readonly TransferredTerm[],
): EditableDraft {
  const renamed = new Map(renamedQuestions.map((rename) => [rename.from, rename.to]))
  const quiz = draft.quiz.map((question) =>
    question.questionId !== null && renamed.has(question.questionId)
      ? { ...question, questionId: renamed.get(question.questionId) ?? question.questionId }
      : question,
  )
  if (draft.format === 'V1') return { ...draft, quiz }

  const received = new Set(
    transferredTerms.filter((term) => term.toArticleId === articleId).map((term) => term.termId),
  )
  const terms = draft.terms.map((term) =>
    received.has(term.termId) ? { ...term, transferFrom: null } : term,
  )
  return { ...draft, quiz, terms }
}

/**
 * 이 탭에 보관한 그 글의 편집 내용을 게시 결과대로 고친다. 보관본의 초안 번호와 보관 시각은 그대로다 —
 * 게시는 초안 번호를 올리지 않는다.
 */
export function applyPublishToUnsentDraft(
  articleId: string,
  renamedQuestions: readonly RenamedQuestion[],
  transferredTerms: readonly TransferredTerm[],
): void {
  if (renamedQuestions.length === 0 && transferredTerms.length === 0) return
  const lookup = readUnsentDraft(articleId)
  if (lookup.status !== 'found') return
  write(articleId, {
    ...lookup.unsent,
    draft: applyPublishedChanges(
      lookup.unsent.draft,
      articleId,
      renamedQuestions,
      transferredTerms,
    ),
  })
}

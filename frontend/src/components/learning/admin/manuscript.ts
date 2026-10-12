import { readDraftShape, readText, ShapeError } from '@/api/learningAdminShape'
import type { DraftQuizQuestion } from '@/types/learningAdmin'
import { toServerDraft, type EditableDraft } from './editableDraft'

/**
 * 원고 파일을 읽고 쓴다(013 research R26, `docs/learning-content/README.md`).
 *
 * <p>원고는 편집기 상태를 그대로 쓴 JSON이다. 모양은 초안 저장 요청에서 `expectedDraftVersion`을 뺀 것이고,
 * 글자 조각 필드만 편집 문법 문자열이다. 다른 글의 원고를 잘못 넣지 않도록 `articleId`를 함께 쓴다. 모양은
 * 서버 응답 해석과 같은 `api/learningAdminShape.ts`가 정하고, 원고는 엄격하게 읽는다.
 *
 * <p><b>틀린 원고는 위치와 함께 통째로 거절한다.</b> 일부만 받아들이면 편집 상태가 원고와 서버 사이 어딘가가
 * 되어 무엇이 들어갔는지 알 수 없다. 모르는 키도, 빠진 키도 거절한다 — 오타 난 키(`qiuz`)를 버리거나 빠진
 * `quiz`를 빈 목록으로 채우면 그 내용이 저장에서 말없이 사라진다(원칙 VI). 길이·개수 판정은 서버가 저장에서 한다.
 *
 * <p><b>지금 초안에 없는 문제·보기 식별자는 `null`로 바꾼다.</b> 저장 API는 지어 낸 식별자를 400으로
 * 거절하므로, 그런 식별자는 새 문제·보기로 저장하게 한다(data-model §6-1). 공개된 글의 원고는 내보낸 그대로
 * 가져오면 식별자가 같아 사용자 기록이 이어진다.
 */

/** 원고 파일 하나. 편집기 초안에 `articleId`를 더한 것이다. */
export type ManuscriptFile = EditableDraft & { articleId: string }

export type ManuscriptIssue = {
  /** 원고 안 위치. 예: `blocks[3].content`, `quiz[0].choices[1]` */
  where: string
  /** 편집 문법 오류일 때만 있다. 그 칸 안의 줄·열이다 */
  line?: number
  column?: number
  message: string
}

/**
 * 가져온 원고의 문제·보기 식별자 집계. 저장하면 `null`인 문제·보기가 새 식별자를 받는다. 공개된 글이면 새
 * 문제는 이미 답한 사용자에게도 답하지 않은 문제로 보이므로(data-model §6-3) 화면이 이 수를 알린다.
 */
export type ManuscriptIdSummary = {
  /** 식별자가 `null`인 문제 수(원고에서 `null`이었거나 지금 초안에 없어 바뀐 것) */
  newQuestions: number
  /** 식별자가 `null`인 보기 수 */
  newChoices: number
  /** 원고에 적혀 있었지만 지금 초안에 없어 `null`로 바꾼 식별자 수 */
  replaced: number
}

export type ManuscriptReadResult =
  | { ok: true; draft: EditableDraft; ids: ManuscriptIdSummary }
  | { ok: false; issues: ManuscriptIssue[] }

/** 지금 서버 초안이 가진 식별자. 원고의 식별자 가운데 여기 없는 것은 `null`이 된다. */
export type KnownQuizIds = { questionIds: ReadonlySet<string>; choiceIds: ReadonlySet<string> }

export function knownQuizIds(quiz: readonly DraftQuizQuestion[]): KnownQuizIds {
  return {
    questionIds: new Set(quiz.flatMap((question) => question.questionId ?? [])),
    choiceIds: new Set(
      quiz.flatMap((question) => question.choices.flatMap((choice) => choice.choiceId ?? [])),
    ),
  }
}

/** 지금 편집 상태를 원고 JSON으로 쓴다. 저장소에서 읽기 쉽게 두 칸 들여쓰고 끝에 줄바꿈을 둔다. */
export function writeManuscript(articleId: string, draft: EditableDraft): string {
  const file: ManuscriptFile = { articleId, ...draft }
  return `${JSON.stringify(file, null, 2)}\n`
}

/** 맨 위(`''`) 자리는 사람이 읽도록 '원고'라고 부른다. */
function placeOf(where: string): string {
  return where === '' ? '원고' : where
}

/**
 * 원고 JSON을 편집기 초안으로 읽는다.
 *
 * <p>순서대로 JSON 해석, 모양, 편집 문법을 본다. 어느 단계에서든 걸리면 편집 상태를 바꾸지 않도록 초안을
 * 돌려주지 않는다.
 */
export function readManuscript(
  source: string,
  articleId: string,
  known: KnownQuizIds,
): ManuscriptReadResult {
  let parsed: unknown
  try {
    parsed = JSON.parse(source)
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error)
    return {
      ok: false,
      issues: [{ where: '원고 파일', message: `JSON으로 읽지 못했습니다. ${reason}` }],
    }
  }

  let replaced = 0
  let draft: EditableDraft
  try {
    // 객체가 아니면 아래 모양 해석이 '객체여야 합니다'로 거절한다.
    if (typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)) {
      const file = parsed as Record<string, unknown>
      if (!('articleId' in file)) throw new ShapeError('articleId', '키가 없습니다.')
      const fileId = readText(file.articleId, 'articleId')
      if (fileId !== articleId) {
        throw new ShapeError(
          'articleId',
          `다른 글(${fileId})의 원고입니다. 이 글은 ${articleId}입니다.`,
        )
      }
    }
    draft = readDraftShape(
      parsed,
      '',
      {
        rich: readText,
        strict: true,
        mapQuizId: (id, kind) => {
          if (id === null) return null
          const ids = kind === 'question' ? known.questionIds : known.choiceIds
          if (ids.has(id)) return id
          replaced += 1
          return null
        },
      },
      ['articleId'],
    )
  } catch (error) {
    if (error instanceof ShapeError) {
      return { ok: false, issues: [{ where: placeOf(error.where), message: error.message }] }
    }
    throw error
  }

  const syntax = toServerDraft(draft)
  if (!syntax.ok) return { ok: false, issues: syntax.issues }
  return {
    ok: true,
    draft,
    ids: {
      newQuestions: draft.quiz.filter((question) => question.questionId === null).length,
      newChoices: draft.quiz.reduce(
        (count, question) =>
          count + question.choices.filter((choice) => choice.choiceId === null).length,
        0,
      ),
      replaced,
    },
  }
}

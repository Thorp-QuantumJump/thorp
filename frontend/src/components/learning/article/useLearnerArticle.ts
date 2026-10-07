import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import {
  answerQuestion,
  completeArticle,
  fetchLearnerArticle,
  retryAnswer,
  type LearningAnswerResult,
  type LearningMeResult,
} from '@/api/learningMe'
import type {
  AnswerResult,
  CompletionResult,
  Gained,
  LearnerArticleRecord,
  RetryResult,
  StageProgress,
} from '@/types/learningMe'
import { gainedNotice, type GainedEntry } from './completionText'
import type { QuizSubmitOutcome } from './QuizPanel'

/** 글 기록 조회. 받기 전에는 완료 카드·확인 문제 자리를 비운다(FR-020). */
export type RecordLoad =
  | { status: 'loading' }
  | { status: 'loaded'; record: LearnerArticleRecord }
  | { status: 'failed' }
  /** 서버가 기록할 수 없는 글이라고 했다. 기록 자리를 그리지 않는다 */
  | { status: 'notRecordable' }

/**
 * 완료 요청. 실패하면 완료 카드와 확인 문제 대신 한 줄을 둔다(FR-021). `failed`는 같은 요청을 다시 보낼 수 있고,
 * `stale`은 다른 곳에서 글이 바뀌어 글을 다시 불러와야 한다(게시 취소, 기록할 수 없는 글이 됨).
 */
export type CompletionSend =
  | { status: 'idle' }
  | { status: 'sending' }
  | { status: 'failed' }
  | { status: 'stale' }
  | { status: 'done'; completion: CompletionResult }

/** 조용한 알림 하나. 같은 문장이 다시 와도 다시 읽히도록 번호를 붙인다. */
export type Announcement = { id: number; text: string }

type Snapshot = {
  /** 이 상태가 속한 글과 로그인 상태. 바뀌면 처음부터다 */
  key: string
  load: RecordLoad
  completion: CompletionSend
  /** 이 화면에서 받은 응답의 `gained`, 받은 순서대로 */
  gained: GainedEntry[]
  announcement: Announcement | null
  /** 글 기록을 받아 바꾼 횟수. 확인 문제를 새 기록으로 다시 그릴 때 쓴다 */
  revision: number
}

function initial(key: string): Snapshot {
  return {
    key,
    load: { status: 'loading' },
    completion: { status: 'idle' },
    gained: [],
    announcement: null,
    revision: 0,
  }
}

function hasNews(gained: Gained): boolean {
  return gained.exp.length > 0 || gained.badges.length > 0 || gained.levelAfter > gained.levelBefore
}

/** 받은 `gained`를 쌓고, 새로 일어난 일이 있으면 그것만 알린다. */
function withGained(
  previous: Snapshot,
  gained: Gained,
  stageProgress: StageProgress | null,
): Snapshot {
  if (!hasNews(gained)) return previous
  const entry: GainedEntry = { gained, receivedAt: new Date().toISOString(), stageProgress }
  const text = gainedNotice([entry])
  return {
    ...previous,
    gained: [...previous.gained, entry],
    announcement: text
      ? { id: (previous.announcement?.id ?? 0) + 1, text }
      : previous.announcement,
  }
}

interface LearnerArticleOptions {
  /** 기록 요청이 로그인 만료(401)로 거부되었을 때 부른다. 만료 화면이 '기록되지 않았습니다'를 보일지 정한다 */
  onUnauthenticated?: () => void
}

/**
 * 로그인 사용자의 글 하나 기록(013 FR-020~FR-022, learning-me-api §2·§4·§5·§6).
 *
 * <p><b>받은 것은 화면 상태에만 둔다.</b> 브라우저 저장소에 쓰지 않고, 글이나 로그인 상태(`sessionKey`)가 바뀌면
 * 버리고 처음부터 받는다. 확인 중(`checking`)과 `error`는 바뀐 것이 아니므로 이 값이 그대로이고 다시 요청하지
 * 않는다(research R33).
 *
 * <p><b>조용히 재시도하지 않는다.</b> 실패는 실패로 보이고, 다시 시도는 사용자가 누를 때 한다(계약 §0). 다시 불러올
 * 때는 받아 둔 기록과 이번에 받은 경험치를 지우지 않고, 새 기록이 오면 바꿔 끼운다.
 */
export function useLearnerArticle(
  articleId: string,
  enabled: boolean,
  sessionKey: string,
  { onUnauthenticated }: LearnerArticleOptions = {},
) {
  const key = `${articleId}|${sessionKey}`
  const [snapshot, setSnapshot] = useState<Snapshot>(() => initial(key))
  const [attempt, setAttempt] = useState(0)
  const keyRef = useRef(key)
  const unauthenticatedRef = useRef(onUnauthenticated)
  useLayoutEffect(() => {
    keyRef.current = key
    unauthenticatedRef.current = onUnauthenticated
  })

  // 글이나 로그인 상태가 바뀌면 렌더 중에 비운다. 효과로 미루면 앞 사용자의 기록이 한 번 그려진다.
  const current = snapshot.key === key ? snapshot : initial(key)
  if (snapshot.key !== key) setSnapshot(current)

  /** 같은 글·같은 로그인 상태일 때만 바꾼다. 늦게 온 앞 사용자의 응답을 버린다. */
  const update = useCallback((forKey: string, change: (previous: Snapshot) => Snapshot) => {
    setSnapshot((previous) => (previous.key === forKey ? change(previous) : previous))
  }, [])

  const noteUnauthenticated = useCallback((result: { status: string }) => {
    if (result.status === 'unauthenticated') unauthenticatedRef.current?.()
  }, [])

  useEffect(() => {
    if (!enabled) return
    const forKey = key
    const controller = new AbortController()
    // 받아 둔 기록은 새 기록이 올 때까지 그대로 둔다. 처음 받거나 실패 뒤 다시 받을 때만 자리를 비운다.
    update(forKey, (previous) =>
      previous.load.status === 'loaded' ? previous : { ...previous, load: { status: 'loading' } },
    )
    void fetchLearnerArticle(articleId, controller.signal).then((result) => {
      if (controller.signal.aborted) return
      noteUnauthenticated(result)
      update(forKey, (previous) => {
        if (result.status === 'ok') {
          return {
            ...previous,
            load: { status: 'loaded', record: result.data },
            revision: previous.revision + 1,
          }
        }
        // 다시 받다가 실패했으면 받아 둔 기록을 지우지 않는다. 그 자리의 안내가 다시 시도를 그대로 준다.
        if (previous.load.status === 'loaded') return previous
        return {
          ...previous,
          load: result.status === 'notRecordable' ? { status: 'notRecordable' } : { status: 'failed' },
        }
      })
    })
    return () => controller.abort()
  }, [articleId, enabled, key, attempt, update, noteUnauthenticated])

  /** 글 기록을 다시 받는다. 다른 곳에서 글이 바뀐 완료 실패(`stale`)는 이때 처음으로 되돌린다. */
  const reloadRecord = useCallback(() => {
    update(keyRef.current, (previous) =>
      previous.completion.status === 'stale'
        ? { ...previous, completion: { status: 'idle' } }
        : previous,
    )
    setAttempt((count) => count + 1)
  }, [update])

  const complete = useCallback(() => {
    const forKey = keyRef.current
    update(forKey, (previous) => ({ ...previous, completion: { status: 'sending' } }))
    void completeArticle(articleId).then((result: LearningMeResult<CompletionResult>) => {
      noteUnauthenticated(result)
      update(forKey, (previous) => {
        if (result.status === 'ok') {
          return withGained(
            { ...previous, completion: { status: 'done', completion: result.data } },
            result.data.gained,
            result.data.stageProgress,
          )
        }
        const stale = result.status === 'stale' || result.status === 'notRecordable'
        return { ...previous, completion: { status: stale ? 'stale' : 'failed' } }
      })
    })
  }, [articleId, update, noteUnauthenticated])

  const toOutcome = useCallback(
    (result: LearningAnswerResult<AnswerResult | RetryResult>): QuizSubmitOutcome => {
      noteUnauthenticated(result)
      switch (result.status) {
        case 'ok':
          return {
            status: 'saved',
            choiceId: result.data.choiceId,
            correct: result.data.correct,
            alreadyAnswered: 'alreadyAnswered' in result.data && result.data.alreadyAnswered,
          }
        case 'questionNotFound':
          return { status: 'questionNotFound' }
        case 'stale':
        case 'notRecordable':
          return { status: 'stale' }
        default:
          return { status: 'failed' }
      }
    },
    [noteUnauthenticated],
  )

  const answer = useCallback(
    async (questionId: string, choiceId: string): Promise<QuizSubmitOutcome> => {
      const forKey = keyRef.current
      const result = await answerQuestion(articleId, questionId, choiceId)
      if (result.status === 'ok') {
        const saved = result.data
        update(forKey, (previous) => {
          if (previous.load.status !== 'loaded') return previous
          const record = previous.load.record
          // 서버가 돌려준 첫 답으로 둔다. 다른 탭에서 먼저 답했으면 고른 것과 다르다(계약 §5).
          const answers = [
            ...record.answers.filter((item) => item.questionId !== saved.questionId),
            {
              questionId: saved.questionId,
              choiceId: saved.choiceId,
              correct: saved.correct,
              answeredAt: new Date().toISOString(),
            },
          ]
          return withGained(
            {
              ...previous,
              load: { status: 'loaded', record: { ...record, answers, quiz: saved.quiz } },
            },
            saved.gained,
            null,
          )
        })
      }
      return toOutcome(result)
    },
    [articleId, update, toOutcome],
  )

  const retry = useCallback(
    async (questionId: string, choiceId: string): Promise<QuizSubmitOutcome> => {
      const forKey = keyRef.current
      const result = await retryAnswer(articleId, questionId, choiceId)
      if (result.status === 'ok') {
        const saved = result.data
        update(forKey, (previous) => {
          if (previous.load.status !== 'loaded') return previous
          const record = previous.load.record
          return withGained(
            { ...previous, load: { status: 'loaded', record: { ...record, quiz: saved.quiz } } },
            saved.gained,
            null,
          )
        })
      }
      return toOutcome(result)
    },
    [articleId, update, toOutcome],
  )

  return {
    load: current.load,
    completion: current.completion,
    gained: current.gained,
    announcement: current.announcement,
    revision: current.revision,
    reloadRecord,
    complete,
    answer,
    retry,
  }
}

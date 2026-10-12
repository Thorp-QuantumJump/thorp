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
  ContinueReading,
  Gained,
  LearnerArticleRecord,
  RetryResult,
  StageProgress,
} from '@/types/learningMe'
import { articleSessionKey } from './articleMarkers'
import { gainedNotice, type GainedEntry } from './completionText'
import type { QuizSubmitOutcome } from './QuizPanel'

/** 글 기록 조회. 받기 전에는 완료 카드·확인 문제 자리를 비운다(FR-020). */
type RecordLoad =
  | { status: 'loading' }
  | { status: 'loaded'; record: LearnerArticleRecord }
  | { status: 'failed' }
  /** 다른 곳에서 글이 바뀌었다(게시 취소). 다시 받아도 같으므로 글을 다시 불러오게 한다 */
  | { status: 'changed' }
  /** 서버가 기록할 수 없는 글이라고 했다. 기록 자리를 그리지 않는다 */
  | { status: 'notRecordable' }

/** 완료 요청. `changed`는 다른 곳에서 글이 바뀌어 같은 요청을 다시 보내도 같은 실패라는 뜻이다. */
type CompletionSend =
  | { status: 'idle' }
  | { status: 'sending' }
  | { status: 'failed' }
  | { status: 'changed' }
  | { status: 'done'; completion: CompletionResult }

/**
 * 글 끝의 기록 자리에 그릴 것(FR-020, FR-021, 계약 §0). 글 끝은 이 값만 보고 그린다.
 *
 * - `none`: 기록을 받는 중이거나 아직 완료 전이거나 기록할 수 없는 글이다. 아무것도 그리지 않고, '안 읽음'처럼 보이는
 *   것을 두지 않는다
 * - `card`: 완료한 글. 완료 카드와 확인 문제를 그린다. `fresh`는 이 화면에서 방금 완료했는가다
 * - `loadFailed`: 기록을 받지 못했다. 다시 받을 수 있다
 * - `notRecorded`: 완료가 기록되지 않았다. 같은 요청을 다시 보낼 수 있다
 * - `changed`: 다른 곳에서 글이 바뀌었다. 글을 다시 불러와야 한다
 */
export type RecordEndView =
  | { kind: 'none' }
  | {
      kind: 'card'
      record: LearnerArticleRecord
      /** 완료 시각. 완료로 기록됐는데 서버가 시각을 보내지 않았으면 `null`이다 */
      completedAt: string | null
      stageProgress: StageProgress
      continueReading: ContinueReading
      /** 이 화면에서 방금 완료했는가. 다시 연 완료 글이나 이미 완료한 글의 완료 응답이면 `false`다 */
      fresh: boolean
    }
  | { kind: 'loadFailed' }
  | { kind: 'notRecorded' }
  | { kind: 'changed' }

/** 조용한 알림 하나. 같은 문장이 다시 와도 다시 읽히도록 번호를 붙인다. */
export type Announcement = { id: number; text: string }

type Snapshot = {
  /** 이 상태가 속한 글과 로그인 상태(`articleSessionKey`). 바뀌면 처음부터다 */
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

/**
 * 다른 곳에서 글이나 기록이 바뀌어 같은 요청을 다시 보내도 같은 실패인가. 통로의 `stale`과, 기록할 수 없는 글이 된
 * 경우(`notRecordable`)가 그렇다. 완료와 확인 문제가 같은 판정을 쓴다.
 */
function changedElsewhere(status: string): boolean {
  return status === 'stale' || status === 'notRecordable'
}

function endViewOf(load: RecordLoad, completion: CompletionSend): RecordEndView {
  switch (load.status) {
    case 'failed':
      return { kind: 'loadFailed' }
    case 'changed':
      return { kind: 'changed' }
    case 'loading':
    case 'notRecordable':
      return { kind: 'none' }
  }
  const record = load.record
  if (completion.status === 'done') {
    const { completedAt, stageProgress, continueReading, alreadyCompleted } = completion.completion
    return {
      kind: 'card',
      record,
      completedAt,
      stageProgress,
      continueReading,
      fresh: !alreadyCompleted,
    }
  }
  if (record.state === 'COMPLETED') {
    const { completedAt, stageProgress, continueReading } = record
    return { kind: 'card', record, completedAt, stageProgress, continueReading, fresh: false }
  }
  if (completion.status === 'failed') return { kind: 'notRecorded' }
  if (completion.status === 'changed') return { kind: 'changed' }
  return { kind: 'none' }
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
 *
 * <p>글 끝에 무엇을 그릴지는 `endView`로 정해 돌려준다. 글 끝이 응답 모양을 파고들지 않게 하려는 것이다.
 */
export function useLearnerArticle(
  articleId: string,
  enabled: boolean,
  sessionKey: string,
  { onUnauthenticated }: LearnerArticleOptions = {},
) {
  const key = articleSessionKey(articleId, sessionKey)
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
        const load: RecordLoad =
          result.status === 'notRecordable'
            ? { status: 'notRecordable' }
            : result.status === 'stale'
              ? { status: 'changed' }
              : { status: 'failed' }
        return { ...previous, load }
      })
    })
    return () => controller.abort()
  }, [articleId, enabled, key, attempt, update, noteUnauthenticated])

  /** 글 기록을 다시 받는다. 다른 곳에서 글이 바뀐 완료 실패는 이때 처음으로 되돌린다. */
  const reloadRecord = useCallback(() => {
    update(keyRef.current, (previous) => ({
      ...previous,
      load: previous.load.status === 'changed' ? { status: 'loading' } : previous.load,
      completion: previous.completion.status === 'changed' ? { status: 'idle' } : previous.completion,
    }))
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
        const status = changedElsewhere(result.status) ? 'changed' : 'failed'
        return { ...previous, completion: { status } }
      })
    })
  }, [articleId, update, noteUnauthenticated])

  /**
   * 첫 답·다시 풀기를 보내고, 성공하면 받은 기록을 고친 뒤 받은 것을 쌓는다. 두 행동이 고치는 기록만 다르다.
   */
  const submit = useCallback(
    async <T extends AnswerResult | RetryResult>(
      send: () => Promise<LearningAnswerResult<T>>,
      applyTo: (record: LearnerArticleRecord, saved: T) => LearnerArticleRecord,
    ): Promise<QuizSubmitOutcome> => {
      const forKey = keyRef.current
      const result = await send()
      noteUnauthenticated(result)
      if (result.status !== 'ok') {
        if (result.status === 'questionNotFound') return { status: 'questionNotFound' }
        return { status: changedElsewhere(result.status) ? 'stale' : 'failed' }
      }
      const saved = result.data
      update(forKey, (previous) =>
        previous.load.status === 'loaded'
          ? withGained(
              {
                ...previous,
                load: { status: 'loaded', record: applyTo(previous.load.record, saved) },
              },
              saved.gained,
              null,
            )
          : previous,
      )
      return {
        status: 'saved',
        choiceId: saved.choiceId,
        correct: saved.correct,
        alreadyAnswered: 'alreadyAnswered' in saved && saved.alreadyAnswered,
      }
    },
    [update, noteUnauthenticated],
  )

  const answer = useCallback(
    (questionId: string, choiceId: string) =>
      submit(
        () => answerQuestion(articleId, questionId, choiceId),
        (record, saved) => ({
          ...record,
          // 서버가 돌려준 첫 답으로 둔다. 다른 탭에서 먼저 답했으면 고른 것과 다르다(계약 §5).
          answers: [
            ...record.answers.filter((item) => item.questionId !== saved.questionId),
            {
              questionId: saved.questionId,
              choiceId: saved.choiceId,
              correct: saved.correct,
              answeredAt: new Date().toISOString(),
            },
          ],
          quiz: saved.quiz,
        }),
      ),
    [articleId, submit],
  )

  const retry = useCallback(
    (questionId: string, choiceId: string) =>
      submit(
        () => retryAnswer(articleId, questionId, choiceId),
        (record, saved) => ({ ...record, quiz: saved.quiz }),
      ),
    [articleId, submit],
  )

  return {
    /** 글 기록을 받았으면 그 기록이다. 진행 막대와 '이어서 읽기' 줄이 쓴다 */
    record: current.load.status === 'loaded' ? current.load.record : null,
    /** 완료가 기록되었는가(이번 화면에서 기록했거나 이미 완료한 글) */
    completed:
      current.completion.status === 'done' ||
      (current.load.status === 'loaded' && current.load.record.state === 'COMPLETED'),
    /** 완료 요청을 보낼 수 있는가. 기록을 받았고 아직 완료하지 않았으며 보내는 중이 아니다 */
    canComplete:
      current.load.status === 'loaded' &&
      current.load.record.state !== 'COMPLETED' &&
      current.completion.status === 'idle',
    endView: endViewOf(current.load, current.completion),
    gained: current.gained,
    announcement: current.announcement,
    revision: current.revision,
    reloadRecord,
    complete,
    answer,
    retry,
  }
}

import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useLoginReturn } from '@/auth/useLoginReturn'
import type { CurrentLearningArticle, LearningReturnState } from '@/types/learning'
import type { LearnerOverview } from '@/types/learningMe'
import { GuestSample } from '../me/GuestSample'
import { nextGoalSentence } from '../me/meCopy'
import { QuietNotice } from '../me/QuietNotice'
import { useLearnerOverview } from '../me/useLearnerOverview'
import { CompletionCard } from './CompletionCard'
import { hasGainedNews, settledGainedNotice, type GainedEntry } from './completionText'
import { QuizPanel } from './QuizPanel'
import type { Announcement, useLearnerArticle } from './useLearnerArticle'
import { useRestoreFocusAfterConceal } from './useRestoreFocusAfterConceal'
import { useSettledAuth } from './useSettledAuth'
import styles from './ArticleEnd.module.css'

/** 로그인 사용자의 글 기록과 그 동작(`useLearnerArticle`). */
export type LearnerEnd = ReturnType<typeof useLearnerArticle> & {
  /** 다른 곳에서 글이나 문제가 바뀌었을 때 글과 기록을 다시 불러온다. 화면은 그대로 둔다 */
  reloadArticle: () => void
  /** 글을 다시 불러온 횟수. 확인 문제를 새 글로 다시 그릴 때 쓴다 */
  articleRevision: number
}

/**
 * 글 끝 자리가 따르는 로그인 상태(research R33 표).
 *
 * - `guest`: 비로그인. 확인 문제를 기록 없이 풀고 로그인 안내를 본다
 * - `record`: 로그인. 완료 카드와 저장하는 확인 문제다
 * - `expired`: 로그인이 만료되었다. 비로그인처럼 확인 문제를 기록 없이 풀고, 로그인 안내 자리에 014 FR-012의 재로그인
 *   안내를 둔다. 기록 요청이 만료로 거부되었으면(`rejected`) 그 자리에 '기록되지 않았습니다'와 '다시 시도'를
 *   함께 둔다(spec Edge Cases). 거부된 요청이 없으면 이 문장을 두지 않는다. 이미 기록된 것을 기록되지 않았다고
 *   말하지 않기 위해서다(원칙 VI)
 * - `none`: 처음 확인하는 중이거나 로그아웃을 시작했다. 기록 자리를 그리지 않는다(014 FR-009)
 */
export type ArticleEndMode =
  | { kind: 'guest' }
  | { kind: 'record'; learner: LearnerEnd }
  | { kind: 'expired'; rejected: boolean; onRetry: () => void }
  | { kind: 'none' }

interface ArticleEndProps {
  article: Pick<CurrentLearningArticle, 'quiz' | 'next' | 'hasUnreleasedStages' | 'blocks' | 'stage'>
  mode: ArticleEndMode
  /**
   * 로그인 상태를 다시 확인하는 중인가(research R33). 그동안 기록 자리(확인 문제·완료 카드·로그인 안내)를 내리지
   * 않고 자리를 둔 채 보이지 않게만 한다. 내리면 고른 답과 받은 기록이 탭을 오갈 때마다 사라진다.
   */
  concealed: boolean
  /** 다음 글·목록으로 옮겨 가도 처음 떠난 화면을 잃지 않게 받은 돌아갈 자리를 싣는다(term-help-ui §3) */
  learningReturn?: LearningReturnState
}

/**
 * 새 기준 글의 글 끝 자리(013 FR-021, design-notes §3.4·§3.5).
 *
 * <p><b>비로그인</b>은 위에서부터 확인 문제, 다음 글 연결, 비로그인 견본(`GuestSample`)이다. 견본은 배우기 목록의
 * 기록 자리와 같은 부품으로 '로그인하면 학습 기록이 남습니다.' 한 줄과 로그인 연결, 숫자·상태 모양이 없는 견본
 * 그림이다(design-notes §3.5). <b>로그인</b>은 완료하면 완료 카드(완료 날짜·단계 진행·받은 것·익힐 용어·확인 문제·
 * 다음 글)가 놓이고, 완료 전에는 다음 글 연결만 있다. 기록을 받지 못했거나 완료가 기록되지 않았으면 그 자리에 실패
 * 한 줄과 '다시 시도'를 둔다. 글 읽기와 다음 글은 막지 않는다.
 *
 * <p>실패 줄은 `role="alert"`라 화면 낭독기가 바로 읽는다. 완료는 사용자가 누르지 않아도 나가므로 알리지 않으면
 * 실패를 알 길이 없다(FR-019, R13).
 *
 * <p>다음 글이 없으면 '배우기 목록으로'를 두고, 공개되지 않은 단계가 있으면 '다음 단계는 준비 중입니다'를
 * 덧붙인다. 로그인 연결(견본과 만료 안내)은 014의 `useLoginReturn`으로 지금 글 주소를 적어 두고 떠난다(research R34).
 */
export function ArticleEnd({ article, mode, concealed, learningReturn }: ArticleEndProps) {
  const rememberReturn = useLoginReturn()
  const endRef = useRef<HTMLDivElement>(null)
  useRestoreFocusAfterConceal(endRef, concealed)
  const concealable = concealed ? styles.concealed : undefined
  const unrecorded = mode.kind === 'guest' || mode.kind === 'expired'

  // 완료 카드의 다음 목표(레벨 길·다음 레벨까지·다음 배지)는 '나의 학습' 전체에서 읽는다(T204). 이 화면에서 완료·답으로
  // 경험치·배지를 받을 때마다 한 번 받는다. 다시 연 완료 글에서는 부르지 않는다. 그 요청도 정산을 하는데(계약 §1) 카드에는
  // 그 결과를 그릴 자리가 없어, 운영자 변경으로 생긴 배지가 알림 없이 지나가기 때문이다. 받지 못해도 카드의 글자는 그대로다.
  const { settled } = useSettledAuth()
  const learner = mode.kind === 'record' ? mode.learner : null
  const gainedCount = learner?.gained.length ?? 0
  const overview = useLearnerOverview({
    auth: settled,
    enabled: learner !== null && hasCompletionCard(learner) && gainedCount > 0,
    refreshKey: learner ? `${learner.articleRevision}:${gainedCount}` : '',
  })
  // 새 경험치를 반영한 응답일 때만 그린다. 다시 받다가 실패했으면 앞 응답으로 다음 목표를 그리지 않는다.
  const learnerOverview =
    overview.upToDate && overview.state.status === 'loaded' ? overview.state.overview : null
  const followUp = useOverviewFollowUp(
    learnerOverview,
    gainedCount,
    `${settled.kind === 'authenticated' ? settled.userId : settled.kind}|${learner?.articleRevision ?? ''}`,
  )
  const overviewNotice = useMemo(() => overviewNoticeOf(learnerOverview), [learnerOverview])

  const card = learner
    ? completionCard(article, learner, learningReturn, learnerOverview, followUp)
    : null

  return (
    <div ref={endRef} className={styles.end}>
      {/* 새 배지·레벨 알림 영역. 완료 카드보다 먼저, 비어 있는 채로 둔다. 미리 있어야 바뀐 글자가 읽힌다(FR-030). */}
      {mode.kind === 'record' ? (
        <LiveAnnouncement
          announcement={mode.learner.announcement}
          overviewNotice={overviewNotice}
          ready={overview.finished}
        />
      ) : null}

      {mode.kind === 'expired' && mode.rejected ? (
        <div className={concealable}>
          <p className={styles.lineText} role="alert">
            <span>기록되지 않았습니다.</span>
            <button type="button" className={styles.inlineButton} onClick={mode.onRetry}>
              다시 시도
            </button>
          </p>
        </div>
      ) : null}

      {/* 숨길 때도 같은 자리에 같은 부품을 둔다. 감싸는 요소를 바꾸면 확인 문제가 다시 그려져 답을 잃는다. */}
      {unrecorded ? (
        <div className={concealable}>
          <QuizPanel questions={article.quiz} mode="guest" />
        </div>
      ) : null}

      {mode.kind === 'record' ? (
        <div className={concealable}>{card ?? <RecordFailureLine learner={mode.learner} />}</div>
      ) : null}

      {/* 완료 카드가 있으면 다음 글 자리는 카드 안에 있다. */}
      {card ? null : (
        <nav className={styles.next} aria-label="다음 읽을 글">
          {article.next ? (
            <Link
              className={styles.nextLink}
              to={`/learn/${encodeURIComponent(article.next.articleId)}`}
              state={learningReturn ? { learningReturn } : undefined}
            >
              <span className={styles.nextLabel}>다음 글</span>
              <span className={styles.nextTitle}>
                {article.next.number ? `${article.next.number} ` : null}
                {article.next.title}
              </span>
            </Link>
          ) : (
            <>
              <Link
                className={styles.listLink}
                to="/learn"
                state={learningReturn ? { learningReturn } : undefined}
              >
                배우기 목록으로
              </Link>
              {article.hasUnreleasedStages ? (
                <p className={styles.preparing}>다음 단계는 준비 중입니다.</p>
              ) : null}
            </>
          )}
        </nav>
      )}

      {mode.kind === 'guest' ? (
        <div className={concealable}>
          <GuestSample place="end" />
        </div>
      ) : null}

      {mode.kind === 'expired' ? (
        <p className={concealed ? `${styles.relogin} ${styles.concealed}` : styles.relogin}>
          <span>로그인이 만료되었습니다. 다시 로그인하면 이 글로 돌아옵니다.</span>
          <Link className={styles.reloginLink} to="/login" onClick={rememberReturn}>
            다시 로그인
          </Link>
        </p>
      ) : null}
    </div>
  )
}

/** 다음 목표를 받기를 기다리는 가장 긴 시간. 넘으면 다음 목표 없이 알린다. */
const SUFFIX_WAIT_MS = 4000

/**
 * 레벨 길의 점이 옮겨 가는 움직임을 주는 시간 창. 받은 것이 생긴 뒤 이 안에 '나의 학습'이 와야 카드의 다른 움직임과 한
 * 장면으로 950ms 안에 끝난다(design-notes §5). 늦게 오면 점을 옮기지 않고 최종 모습만 보인다.
 */
const GOAL_MOTION_WINDOW_MS = 250

type FollowUp = {
  /** 이 기록이 속한 사용자와 글 판. 바뀌면 처음부터다 */
  key: string
  /** '나의 학습' 요청의 정산으로 이 화면에서 새로 생긴 것. 완료 카드의 받은 것에 잇는다 */
  extra: GainedEntry[]
  /** 마지막으로 반영한 응답 */
  seen: LearnerOverview | null
  /** 그 응답이 움직임 창 안에 왔는가 */
  quick: boolean
}

/**
 * 완료 뒤 다시 받은 '나의 학습'을 완료 카드에 잇는다(T204).
 *
 * <p>그 요청도 정산을 하므로(계약 §1) 운영자의 게시 취소나 단계 이동으로 채워진 배지·단계 완주가 그 응답의 `gained`로
 * 올 수 있다. 버리면 알림 없이 지나가므로(FR-030) 받은 것에 잇는다. 같은 응답은 한 번만 잇는다.
 */
function useOverviewFollowUp(overview: LearnerOverview | null, gainedCount: number, key: string) {
  const [state, setState] = useState<FollowUp>({ key, extra: [], seen: null, quick: true })
  const gainedAt = useRef(0)
  const current = state.key === key ? state : { key, extra: [], seen: null, quick: true }
  if (state.key !== key) setState(current)

  // 받은 것이 생긴 때를 적어 둔다. 그 뒤 '나의 학습'이 오기까지 걸린 시간으로 움직임을 줄지 정한다.
  useEffect(() => {
    gainedAt.current = Date.now()
  }, [gainedCount])

  useEffect(() => {
    if (!overview) return
    const quick = Date.now() - gainedAt.current <= GOAL_MOTION_WINDOW_MS
    setState((previous) => {
      if (previous.key !== key || previous.seen === overview) return previous
      const extra = hasGainedNews(overview.gained)
        ? [
            ...previous.extra,
            { gained: overview.gained, receivedAt: new Date().toISOString(), stageProgress: null },
          ]
        : previous.extra
      return { ...previous, seen: overview, quick, extra }
    })
  }, [overview, key])

  return current
}

/** 완료 뒤 다시 받은 '나의 학습' 응답 하나가 알릴 것. 같은 응답이면 같은 값이다. */
type OverviewNotice = {
  /** 그 요청의 정산으로 새로 생긴 배지·레벨의 문장(계약 §4). 없으면 빈 문자열이다 */
  news: string
  /** 다음 목표 문장(T208). 없으면 빈 문자열이다 */
  goal: string
}

function overviewNoticeOf(overview: LearnerOverview | null): OverviewNotice | null {
  if (!overview) return null
  return { news: settledGainedNotice(overview.gained), goal: nextGoalSentence(overview) }
}

/**
 * 새 배지·레벨의 조용한 알림(FR-030, 목업의 `#live`). 처음부터 비어 있는 영역(`QuietNotice`)에 방금 받은 것의 문장만
 * 넣는다. 앞서 알린 것을 다시 읽지 않고, 초점을 옮기지 않는다.
 *
 * <p>글 기록 응답의 알림(`announcement`) 끝에는 완료 뒤 다시 받은 '나의 학습'의 정산으로 새로 생긴 것과 다음 목표('다음
 * 레벨까지 240, 다음 배지(읽은 글 5편)까지 1편입니다.', T208)를 덧붙인다. 그 응답을 받아야 알므로 요청이 끝날 때까지
 * (`ready`) 기다리고, 받지 못했거나 4초를 넘기면 덧붙이지 않는다.
 *
 * <p>'나의 학습'의 정산으로 새로 생긴 배지·레벨은 덧붙일 알림이 없어도 알린다(계약 §4). 경험치만 받은 완료라 글 기록
 * 응답의 알림이 없었거나, 응답이 기다림을 넘겨 왔을 때다. 그때는 그것과 다음 목표를 따로 한 번 알린다. 한 알림과 한
 * 응답의 새 배지는 한 번만 읽힌다.
 */
function LiveAnnouncement({
  announcement,
  overviewNotice,
  ready,
}: {
  announcement: Announcement | null
  overviewNotice: OverviewNotice | null
  ready: boolean
}) {
  const [message, setMessage] = useState('')
  const [waited, setWaited] = useState<number | null>(null)
  const latest = useRef({ overviewNotice, ready })
  latest.current = { overviewNotice, ready }
  // 덧붙일 것을 기다리는 알림과, 이미 읽힌 '나의 학습' 응답.
  const pending = useRef<number | null>(null)
  const told = useRef<OverviewNotice | null>(null)

  // 새 알림이 오면 비우고, 다음 목표를 기다리는 시간의 상한을 둔다.
  useEffect(() => {
    setMessage('')
    setWaited(null)
    pending.current = announcement?.id ?? null
    if (!announcement) return
    const timer = setTimeout(() => setWaited(announcement.id), SUFFIX_WAIT_MS)
    return () => clearTimeout(timer)
  }, [announcement])

  const settledNow = ready || waited === announcement?.id
  useEffect(() => {
    if (!announcement || !settledNow || pending.current !== announcement.id) return
    const { overviewNotice: notice, ready: done } = latest.current
    pending.current = null
    const extra = done && notice ? [notice.news, notice.goal] : []
    if (done && notice) told.current = notice
    setMessage([announcement.text, ...extra].filter(Boolean).join(' '))
  }, [announcement, settledNow])

  // 덧붙일 알림 없이 온 '나의 학습'의 새 배지·레벨은 따로 알린다.
  useEffect(() => {
    if (!overviewNotice?.news || told.current === overviewNotice || pending.current !== null) return
    told.current = overviewNotice
    setMessage([overviewNotice.news, overviewNotice.goal].filter(Boolean).join(' '))
  }, [overviewNotice])

  return <QuietNotice text={message} />
}

/** 완료 카드가 놓이는가. 기록을 받았고 완료한 글이다. */
function hasCompletionCard(learner: LearnerEnd): boolean {
  if (learner.load.status !== 'loaded') return false
  return learner.completion.status === 'done' || learner.load.record.state === 'COMPLETED'
}

/** 완료한 글이면 완료 카드를 짓는다. 기록을 받기 전이거나 아직 완료하지 않았으면 `null`이다. */
function completionCard(
  article: ArticleEndProps['article'],
  learner: LearnerEnd,
  learningReturn: LearningReturnState | undefined,
  overview: LearnerOverview | null,
  followUp: FollowUp,
) {
  if (learner.load.status !== 'loaded' || !hasCompletionCard(learner)) return null
  const record = learner.load.record
  const done = learner.completion.status === 'done' ? learner.completion.completion : null

  const terms = article.blocks.flatMap((block) => (block.type === 'term' ? [block.label] : []))
  return (
    <CompletionCard
      completedAt={done?.completedAt ?? record.completedAt ?? ''}
      stageKey={article.stage.stageKey}
      stageProgress={done?.stageProgress ?? record.stageProgress}
      continueReading={done?.continueReading ?? record.continueReading}
      gained={[...learner.gained, ...followUp.extra]}
      terms={terms}
      mastered={record.quiz.mastered}
      next={article.next}
      learningReturn={learningReturn}
      overview={overview}
      // 이 화면에서 방금 완료했으면 마지막 도장이 찍히는 움직임을 준다. 다시 연 완료 글은 최종 모습 그대로다.
      freshCompletion={done !== null && !done.alreadyCompleted}
      animateGoal={followUp.quick}
      quiz={
        <QuizPanel
          // 글이나 기록을 다시 받으면 새 문제·새 첫 답으로 처음부터 그린다. 기록 중·실패 줄도 함께 비운다.
          key={`${learner.articleRevision}:${learner.revision}`}
          questions={article.quiz}
          mode="record"
          answers={record.answers}
          summary={record.quiz}
          hasTerms={terms.length > 0}
          onAnswer={learner.answer}
          onRetry={learner.retry}
          onReload={learner.reloadArticle}
        />
      }
    />
  )
}

/**
 * 완료 카드 대신 놓이는 한 줄. 기록을 받지 못했으면 '학습 기록을 불러오지 못했습니다', 완료가 기록되지 않았으면
 * '기록되지 않았습니다', 다른 곳에서 글이 바뀌었으면 글을 다시 불러오라는 문장이다(FR-020, FR-021, 계약 §0).
 * 기록을 받는 중이거나 완료 전이면 아무것도 그리지 않는다. 그동안 '안 읽음'처럼 보이는 것을 두지 않는다.
 */
function RecordFailureLine({ learner }: { learner: LearnerEnd }) {
  if (learner.load.status === 'failed') {
    return (
      <p className={styles.lineText} role="alert">
        <span>학습 기록을 불러오지 못했습니다.</span>
        <button type="button" className={styles.inlineButton} onClick={learner.reloadRecord}>
          다시 시도
        </button>
      </p>
    )
  }
  if (learner.load.status !== 'loaded') return null
  if (learner.completion.status === 'failed') {
    return (
      <p className={styles.lineText} role="alert">
        <span>기록되지 않았습니다.</span>
        <button type="button" className={styles.inlineButton} onClick={learner.complete}>
          다시 시도
        </button>
      </p>
    )
  }
  if (learner.completion.status === 'stale') {
    return (
      <p className={styles.lineText} role="alert">
        <span>이 글이 바뀌었습니다. 글을 다시 불러와 주세요.</span>
        <button type="button" className={styles.inlineButton} onClick={learner.reloadArticle}>
          다시 불러오기
        </button>
      </p>
    )
  }
  return null
}

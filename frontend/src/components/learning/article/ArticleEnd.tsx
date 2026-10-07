import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useLoginReturn } from '@/auth/useLoginReturn'
import type { CurrentLearningArticle, LearningReturnState } from '@/types/learning'
import { GuestSample } from '../me/GuestSample'
import { CompletionCard } from './CompletionCard'
import { QuizPanel } from './QuizPanel'
import type { Announcement, useLearnerArticle } from './useLearnerArticle'
import { useRestoreFocusAfterConceal } from './useRestoreFocusAfterConceal'
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
  article: Pick<CurrentLearningArticle, 'quiz' | 'next' | 'hasUnreleasedStages' | 'blocks'>
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
  const carried = learningReturn ? { learningReturn } : undefined
  const unrecorded = mode.kind === 'guest' || mode.kind === 'expired'
  const view = mode.kind === 'record' ? mode.learner.endView : null

  return (
    <div ref={endRef} className={styles.end}>
      {/*
        새 배지·레벨 알림 영역. 완료 카드보다 먼저, 비어 있는 채로 둔다. 미리 있어야 바뀐 글자가 읽힌다(FR-030).
        확인하는 동안에는 기록 자리와 함께 보조 기술에서 빠진다(R33).
      */}
      {mode.kind === 'record' ? (
        <div className={concealable}>
          <LiveAnnouncement announcement={mode.learner.announcement} held={concealed} />
        </div>
      ) : null}

      {mode.kind === 'expired' && mode.rejected ? (
        <div className={concealable}>
          <FailureLine message="기록되지 않았습니다." action="다시 시도" onAction={mode.onRetry} />
        </div>
      ) : null}

      {/* 숨길 때도 같은 자리에 같은 부품을 둔다. 감싸는 요소를 바꾸면 확인 문제가 다시 그려져 답을 잃는다. */}
      {unrecorded ? (
        <div className={concealable}>
          <QuizPanel questions={article.quiz} mode="guest" />
        </div>
      ) : null}

      {mode.kind === 'record' && view ? (
        <div className={concealable}>
          <RecordPlace article={article} learner={mode.learner} learningReturn={learningReturn} />
        </div>
      ) : null}

      {/* 완료 카드가 있으면 다음 글 자리는 카드 안에 있다. */}
      {view?.kind === 'card' ? null : (
        <nav className={styles.next} aria-label="다음 읽을 글">
          {article.next ? (
            <Link
              className={styles.nextLink}
              to={`/learn/${encodeURIComponent(article.next.articleId)}`}
              state={carried}
            >
              <span className={styles.nextLabel}>다음 글</span>
              <span className={styles.nextTitle}>
                {article.next.number ? `${article.next.number} ` : null}
                {article.next.title}
              </span>
            </Link>
          ) : (
            <>
              <Link className={styles.listLink} to="/learn" state={carried}>
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

/**
 * 새 배지·레벨의 조용한 알림(FR-030, 목업의 `#live`). 처음부터 비어 있는 영역에 방금 받은 것의 문장만 넣는다.
 * 앞서 알린 것을 다시 읽지 않고, 초점을 옮기지 않는다. 카드가 그려진 뒤에 글자를 넣도록 잠깐 기다린다.
 *
 * <p>로그인 상태를 다시 확인하는 동안(`held`) 온 알림은 확인이 끝나 다시 보일 때 한 번만 넣는다. 이미 알린 번호는
 * 다시 넣지 않으므로 탭을 오갈 때마다 같은 알림이 되풀이되지 않는다.
 */
function LiveAnnouncement({
  announcement,
  held,
}: {
  announcement: Announcement | null
  held: boolean
}) {
  const [text, setText] = useState('')
  const announcedRef = useRef<number | null>(null)
  useEffect(() => {
    if (held || !announcement || announcement.id === announcedRef.current) return
    setText('')
    // 글자를 넣는 순간에 알린 것으로 적는다. 기다리는 사이 확인이 시작되면 타이머만 지워지고 다음에 다시 넣는다.
    const timer = setTimeout(() => {
      announcedRef.current = announcement.id
      setText(announcement.text)
    }, 250)
    return () => clearTimeout(timer)
  }, [announcement, held])
  return (
    <p className="sr-only" role="status" aria-live="polite" aria-atomic="true">
      {text}
    </p>
  )
}

/** 실패 한 줄과 그 행동. 화면 낭독기가 바로 읽도록 `role="alert"`다(FR-019, R13). */
function FailureLine({
  message,
  action,
  onAction,
}: {
  message: string
  action: string
  onAction: () => void
}) {
  return (
    <p className={styles.lineText} role="alert">
      <span>{message}</span>
      <button type="button" className={styles.inlineButton} onClick={onAction}>
        {action}
      </button>
    </p>
  )
}

/**
 * 로그인 사용자의 기록 자리. 기록 훅이 정한 `endView`대로 완료 카드나 실패 한 줄을 그리고, 받는 중이거나 완료
 * 전이면 아무것도 그리지 않는다. 그동안 '안 읽음'처럼 보이는 것을 두지 않는다(FR-020, FR-021, 계약 §0).
 */
function RecordPlace({
  article,
  learner,
  learningReturn,
}: {
  article: ArticleEndProps['article']
  learner: LearnerEnd
  learningReturn: LearningReturnState | undefined
}) {
  const view = learner.endView
  switch (view.kind) {
    case 'none':
      return null
    case 'loadFailed':
      return (
        <FailureLine
          message="학습 기록을 불러오지 못했습니다."
          action="다시 시도"
          onAction={learner.reloadRecord}
        />
      )
    case 'notRecorded':
      return (
        <FailureLine message="기록되지 않았습니다." action="다시 시도" onAction={learner.complete} />
      )
    case 'changed':
      return (
        <FailureLine
          message="이 글이 바뀌었습니다. 글을 다시 불러와 주세요."
          action="다시 불러오기"
          onAction={learner.reloadArticle}
        />
      )
  }

  const terms = article.blocks.flatMap((block) => (block.type === 'term' ? [block.label] : []))
  return (
    <CompletionCard
      completedAt={view.completedAt}
      stageProgress={view.stageProgress}
      continueReading={view.continueReading}
      gained={learner.gained}
      terms={terms}
      mastered={view.record.quiz.mastered}
      next={article.next}
      learningReturn={learningReturn}
      quiz={
        <QuizPanel
          // 글이나 기록을 다시 받으면 새 문제·새 첫 답으로 처음부터 그린다. 기록 중·실패 줄도 함께 비운다.
          key={`${learner.articleRevision}:${learner.revision}`}
          questions={article.quiz}
          mode="record"
          answers={view.record.answers}
          summary={view.record.quiz}
          hasTerms={terms.length > 0}
          onAnswer={learner.answer}
          onRetry={learner.retry}
          onReload={learner.reloadArticle}
        />
      }
    />
  )
}

import { ChevronRight } from 'lucide-react'
import { useId, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { ArticleNext, LearningReturnState } from '@/types/learning'
import type { ContinueReading, LearningResumeState, StageProgress } from '@/types/learningMe'
import {
  expLabel,
  formatCompletedDate,
  gainedNotice,
  reachedLevel,
  type GainedEntry,
} from './completionText'
import styles from './CompletionCard.module.css'

interface CompletionCardProps {
  /**
   * 완료 시각(ISO 8601). 서울 날짜로 보인다. 완료로 기록됐는데 시각이 없으면 `null`이고, 빈 날짜 대신 '완료'만
   * 보인다(원칙 VI)
   */
  completedAt: string | null
  stageProgress: StageProgress
  /** 읽는 순서상 다음 공개된 글이 없을 때 '이어서 읽기'와 '모두 읽었습니다'를 가른다(FR-021) */
  continueReading: ContinueReading
  /** 이 글 화면에서 받은 것, 받은 순서대로. 다시 연 완료 글이면 비어 있다 */
  gained: readonly GainedEntry[]
  /** 이 글의 용어 이름. 용어가 없는 글이면 비어 있고 그 줄을 그리지 않는다 */
  terms: readonly string[]
  /** 이 글이 익힘인가. 그러면 용어마다 '익힘'을 붙인다 */
  mastered: boolean
  /** 확인 문제(로그인 모드 `QuizPanel`) */
  quiz: ReactNode
  next: ArticleNext | null
  learningReturn?: LearningReturnState
}

/**
 * 글 끝 완료 카드(013 FR-021·FR-030, design-notes §3.4, 목업 '글 끝'의 `doneCard`).
 *
 * <p>구역은 위에서부터 머리(완료 날짜와 단계 진행), 이번에 얻은 것(새 배지·새 레벨·받은 경험치와 상태 문장),
 * 익힐 용어와 확인 문제, 다음 글 자리다. 그림 장면(단계 도장 줄과 완주 메달, 레벨 표장과 길, 다음 배지 메달)과
 * 움직임, 다음 레벨·다음 배지까지의 글자는 B13(T193·T204)이 더한다. 이 카드는 글자만으로 완결하고 빈 그림 자리를
 * 두지 않는다.
 *
 * <p><b>알림은 글 끝의 상태 영역이 한다.</b> 이 카드의 상태 문장은 보이는 글자일 뿐 알림 영역이 아니다. 알림은
 * 글 끝(`ArticleEnd`)이 카드보다 먼저 둔 `role="status"` 영역에 방금 받은 것만 넣는다(FR-030, quickstart §13).
 * 폭죽·소리·화면을 덮는 축하는 쓰지 않는다.
 */
export function CompletionCard({
  completedAt,
  stageProgress,
  continueReading,
  gained,
  terms,
  mastered,
  quiz,
  next,
  learningReturn,
}: CompletionCardProps) {
  const titleId = useId()
  const xpId = useId()
  const { stageNumber, completedCount, publishedCount, stageCompleted } = stageProgress

  const badges = gained.flatMap((entry) =>
    entry.gained.badges.map((badge) => ({ ...badge, receivedAt: entry.receivedAt })),
  )
  const level = reachedLevel(gained)
  const exp = gained.flatMap((entry) => entry.gained.exp)
  const expTotal = gained.reduce((sum, entry) => sum + entry.gained.expTotal, 0)
  const status = gainedNotice(gained)
  const hasNews = badges.length > 0 || level !== null || exp.length > 0

  return (
    <section className={styles.card} aria-labelledby={titleId}>
      <div className={`${styles.section} ${styles.head}`}>
        <h2 id={titleId} className={styles.date}>
          {completedAt ? `완료 · ${formatCompletedDate(completedAt)}` : '완료'}
        </h2>
        <p className={styles.progress}>
          {stageCompleted
            ? `${stageNumber}단계 · 완주 · ${publishedCount}편`
            : `${stageNumber}단계 · ${publishedCount}편 중 ${completedCount}편 읽음`}
        </p>
      </div>

      {hasNews ? (
        <div className={styles.section}>
          <div className={styles.achievements}>
            {badges.length > 0 ? (
              <div className={styles.column}>
                <span className={styles.label}>새 배지</span>
                {badges.map((badge) => (
                  <div key={badge.badgeKey} className={styles.gainedItem}>
                    <b className={styles.big}>{badge.name}</b>
                    <ReceivedRow receivedAt={badge.receivedAt} />
                  </div>
                ))}
              </div>
            ) : null}
            {level ? (
              <div className={styles.column}>
                <span className={styles.label}>레벨</span>
                <div className={styles.gainedItem}>
                  <b className={styles.big}>레벨 {level.gained.levelAfter}</b>
                  <ReceivedRow receivedAt={level.receivedAt} />
                </div>
              </div>
            ) : null}
            {exp.length > 0 ? (
              <div className={`${styles.column} ${styles.expColumn}`}>
                <span id={xpId} className={styles.label}>
                  이번에 받은 경험치
                </span>
                <ul className={styles.exp} aria-labelledby={xpId}>
                  {exp.map((item, index) => (
                    <li key={index}>
                      <span>{expLabel(item)}</span>
                      <span className={styles.points}>+{item.points}</span>
                    </li>
                  ))}
                  <li className={styles.sum}>
                    <span>합계</span>
                    <span className={styles.points}>+{expTotal}</span>
                  </li>
                </ul>
              </div>
            ) : null}
          </div>
          {status ? (
            <p className={styles.status}>
              <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true" focusable="false">
                <circle cx="7" cy="7" r="6" className={styles.statusHalo} />
                <circle cx="7" cy="7" r="3" className={styles.statusDot} />
              </svg>
              <span>{status}</span>
            </p>
          ) : null}
        </div>
      ) : null}

      <div className={styles.section}>
        {terms.length > 0 ? (
          <div className={styles.terms}>
            <span>이 글에서 익힐 용어</span>
            <ul className={styles.termList}>
              {terms.map((term) => (
                <li key={term} className={styles.term}>
                  {term}
                  {mastered ? <em className={styles.mastered}>· 익힘</em> : null}
                </li>
              ))}
            </ul>
          </div>
        ) : null}
        {quiz}
      </div>

      <div className={styles.section}>
        <NextPlace next={next} continueReading={continueReading} learningReturn={learningReturn} />
      </div>
    </section>
  )
}

/** '받음 날짜'와 '새' 알약(목업 `.chip.got`·`.pill-new`). 화면 낭독기는 '새로 받음'으로 읽는다. */
function ReceivedRow({ receivedAt }: { receivedAt: string }) {
  return (
    <div className={styles.received}>
      <span className={styles.chip}>받음 {formatCompletedDate(receivedAt)}</span>
      <span className={styles.newPill}>
        <span aria-hidden="true">새</span>
        <span className="sr-only">새로 받음</span>
      </span>
    </div>
  )
}

function NextPlace({
  next,
  continueReading,
  learningReturn,
}: {
  next: ArticleNext | null
  continueReading: ContinueReading
  learningReturn?: LearningReturnState
}) {
  const carried = learningReturn ? { learningReturn } : undefined

  if (next) {
    return (
      <NextCard
        label="다음 글"
        number={next.number}
        title={next.title}
        to={`/learn/${encodeURIComponent(next.articleId)}`}
        state={carried}
      />
    )
  }

  // 이 글 뒤에 공개된 글이 없다. 완료하지 않은 글이 남았으면 '이어서 읽기', 모두 읽었으면 그 문장이다(FR-021).
  // '배우기 목록으로'는 비로그인 글 끝의 연결이라 여기에 두지 않는다.
  if (continueReading.kind === 'ALL_DONE' || continueReading.articleId === null) {
    return (
      <p className={styles.allDone}>
        지금 공개된 글을 모두 읽었습니다.
        {continueReading.moreStagesPreparing ? ' 다음 단계는 준비 중입니다.' : null}
      </p>
    )
  }

  // 읽는 중 글이면 기록된 위치에서 연다. 위치는 싣지 않고 글 화면이 그 글의 기록에서 읽는다(data-model §15).
  const resume: LearningResumeState | undefined =
    continueReading.kind === 'RESUME' ? { learningResume: true } : undefined
  const state = resume || carried ? { ...resume, ...carried } : undefined
  return (
    <NextCard
      label="이어서 읽기"
      number={continueReading.number}
      title={continueReading.title ?? ''}
      to={`/learn/${encodeURIComponent(continueReading.articleId)}`}
      state={state}
    />
  )
}

/** 다음 글·이어서 읽기 카드(목업 `.nextc`). 누르는 곳 전체가 한 연결이다. */
function NextCard({
  label,
  number,
  title,
  to,
  state,
}: {
  label: string
  number: string | null
  title: string
  to: string
  state: object | undefined
}) {
  return (
    <Link className={styles.nextLink} to={to} state={state}>
      <span className={styles.nextText}>
        <span className={styles.nextLabel}>{label}</span>
        <span className={styles.nextTitle}>
          {number ? <span className={styles.nextNumber}>{number}</span> : null}
          {title}
        </span>
      </span>
      <ChevronRight className={styles.nextArrow} size={20} aria-hidden="true" />
    </Link>
  )
}

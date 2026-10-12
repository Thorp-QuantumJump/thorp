import { ChevronRight } from 'lucide-react'
import { useId, type CSSProperties, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { ArticleNext, LearningReturnState } from '@/types/learning'
import type {
  ContinueReading,
  LearnerOverview,
  LearningResumeState,
  StageProgress,
} from '@/types/learningMe'
import { stageProgressLabel } from '../curriculum/articleProgress'
import { earnedStageNumbers, nextBadge, nextGoalSentence } from '../me/meCopy'
import { stageColor } from '../stageColors'
import {
  expLabel,
  formatCompletedDate,
  gainedNotice,
  newBadgesOf,
  reachedLevel,
  type GainedEntry,
} from './completionText'
import { BadgeHero, LevelGoal, LevelHero, NextBadgeLine, StageStampPath } from './CompletionScene'
import styles from './CompletionCard.module.css'

interface CompletionCardProps {
  /** 완료 시각(ISO 8601). 서울 날짜로 보인다 */
  completedAt: string
  /** 이 글이 놓인 단계의 키. 새 단계 완주 배지가 이 단계의 것인지 배지 키(`stage-{stageKey}`)로 가른다 */
  stageKey: string
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
  /**
   * 완료 뒤 다시 받은 '나의 학습'(T204). 레벨 길, 다음 레벨까지 남은 경험치, 다음 배지는 이 값에서 읽는다. 받지
   * 못했으면 `null`이고 그 그림과 글자만 빠진다. B12가 둔 글자는 그대로다
   */
  overview?: LearnerOverview | null
  /** 이 화면에서 방금 완료했는가. 그러면 단계 도장 줄의 마지막 칸이 '방금 찍힌 도장'이고 움직임이 있다 */
  freshCompletion?: boolean
  /**
   * 레벨 길의 점이 옮겨 가는 움직임을 주는가. '나의 학습'이 늦게 와서 카드의 다른 움직임이 이미 끝났으면 주지 않는다.
   * 완료 카드의 움직임은 한 장면으로 950ms 안에 끝나야 한다(design-notes §5)
   */
  animateGoal?: boolean
}

/**
 * 글 끝 완료 카드(013 FR-021·FR-030, design-notes §3.4, 목업 '글 끝'의 `doneCard`).
 *
 * <p>구역은 위에서부터 머리(완료 날짜, 단계 도장 줄과 단계 완주 메달, 단계 진행), 이번에 얻은 것(새 배지의 메달,
 * 새 레벨의 표장, 레벨 길, 받은 경험치와 상태 문장), 익힐 용어와 확인 문제, 다음 글 자리다. 카드 뒤에는 그 단계 색의
 * 옅은 빛을 깐다(목업 `.done-card::before`, T209).
 *
 * <p><b>다음 목표는 완료 뒤 다시 받은 '나의 학습'에서 읽는다(T204).</b> 완료 응답(계약 §4)에는 레벨 문턱·경험치 합계·
 * 다음 배지가 없기 때문이다. 받으면 레벨 칸 아래에 '경험치 360 · 다음 레벨까지 240'과 레벨 길을, 새 배지 칸 아래에
 * '다음 배지 · 읽은 글 5편 · 4/5'를 두고 상태 문장 끝에 '다음 레벨까지 240, 다음 배지(읽은 글 5편)까지 1편입니다.'를
 * 덧붙인다(T208). 받지 못하면 이 그림과 글자만 빼고 나머지 글자는 그대로다. 서두르게 하거나 놓치면 잃는다는 문장은
 * 쓰지 않는다. 그림은 모두 `aria-hidden`이고 움직임은 줄이기 설정이 아닐 때만 950ms 안에 끝난다.
 *
 * <p><b>알림은 글 끝의 상태 영역이 한다.</b> 이 카드의 상태 문장은 보이는 글자일 뿐 알림 영역이 아니다. 알림은
 * 글 끝(`ArticleEnd`)이 카드보다 먼저 둔 `role="status"` 영역에 방금 받은 것만 넣는다(FR-030, quickstart §13).
 * 폭죽·소리·화면을 덮는 축하는 쓰지 않는다.
 */
export function CompletionCard({
  completedAt,
  stageKey,
  stageProgress,
  continueReading,
  gained,
  terms,
  mastered,
  quiz,
  next,
  learningReturn,
  overview = null,
  freshCompletion = false,
  animateGoal = true,
}: CompletionCardProps) {
  const titleId = useId()
  const xpId = useId()
  const goalId = useId()
  const { stageNumber, completedCount, publishedCount, stageCompleted } = stageProgress
  const progressText = `${stageNumber}단계 · ${stageProgressLabel({
    completed: stageCompleted,
    completedCount,
    publishedCount,
  })}`

  // 레벨이 오른 응답의 레벨 갈래 배지는 레벨 칸이 말하므로 새 배지에 넣지 않는다. 단계 완주 배지를 앞에 둔다. 이 카드의
  // 핵심 장면이 단계 완주 메달에 색유리가 차는 순간이기 때문이다(design-notes §3.4).
  const received = gained.flatMap((entry) =>
    newBadgesOf(entry).map((badge) => ({ ...badge, receivedAt: entry.receivedAt })),
  )
  const badges = [
    ...received.filter((badge) => badge.family === 'STAGE'),
    ...received.filter((badge) => badge.family !== 'STAGE'),
  ]
  const level = reachedLevel(gained)
  const exp = gained.flatMap((entry) => entry.gained.exp)
  const expTotal = gained.reduce((sum, entry) => sum + entry.gained.expTotal, 0)
  const goal = overview ? nextGoalSentence(overview) : ''
  const status = [gainedNotice(gained), goal].filter(Boolean).join(' ')
  const hasNews = badges.length > 0 || level !== null || exp.length > 0
  const upcoming = overview ? nextBadge(overview.badges) : null
  // 레벨 길의 점이 옮겨 가기 전 자리: 지금 경험치에서 이 화면에서 받은 경험치를 뺀다.
  const expBefore = overview ? Math.max(0, overview.level.exp - expTotal) : 0
  const levelGoal = overview ? (
    <LevelGoal level={overview.level} expBefore={expBefore} moving={animateGoal && expTotal > 0} />
  ) : null
  // 단계 완주 메달은 배지를 그린다. 배지를 받은 뒤 그 단계에 새 글이 게시되어도 메달은 받은 모습 그대로다(US6 시나리오 8).
  const stageBadgeEarned =
    stageCompleted || (overview !== null && earnedStageNumbers(overview.badges).has(stageNumber))
  // 레벨 칸이나 새 배지 칸이 없으면 다음 목표를 따로 한 칸에 둔다.
  const goalColumn =
    overview !== null && (level === null || (badges.length === 0 && upcoming !== null))
  const glow = { ['--stage' as string]: stageColor(stageNumber) } as CSSProperties

  return (
    <section className={styles.card} style={glow} aria-labelledby={titleId}>
      <div className={`${styles.section} ${styles.head}`}>
        <h2 id={titleId} className={styles.date}>
          완료 · {formatCompletedDate(completedAt)}
        </h2>
        <div className={styles.headScene}>
          <StageStampPath
            stageProgress={stageProgress}
            freshStamp={freshCompletion}
            badgeEarned={stageBadgeEarned}
          />
          <p className={styles.progress}>{progressText}</p>
        </div>
      </div>

      {hasNews ? (
        <div className={styles.section}>
          <div className={styles.achievements}>
            {badges.length > 0 ? (
              <div className={styles.column}>
                <span className={styles.label}>새 배지</span>
                {badges.map((badge, index) => (
                  <div key={badge.badgeKey} className={styles.gainedItem}>
                    {/* 메달 그림은 첫 배지(단계 완주가 있으면 그것)에만 둔다. 이 카드에서 빛나는 곳은 두 곳까지다(design-notes §2). */}
                    {index === 0 ? (
                      <BadgeHero
                        badge={badge}
                        overviewBadges={overview?.badges ?? null}
                        articleStage={{ stageKey, number: stageNumber }}
                      />
                    ) : null}
                    <b className={styles.big}>{badge.name}</b>
                    <ReceivedRow receivedAt={badge.receivedAt} />
                  </div>
                ))}
                {upcoming ? <NextBadgeLine badge={upcoming} /> : null}
              </div>
            ) : null}
            {level ? (
              <div className={styles.column}>
                <span className={styles.label}>레벨</span>
                <div className={styles.gainedItem}>
                  <LevelHero
                    levelBefore={level.gained.levelBefore}
                    levelAfter={level.gained.levelAfter}
                  />
                  <b className={styles.big}>레벨 {level.gained.levelAfter}</b>
                  <ReceivedRow receivedAt={level.receivedAt} />
                </div>
                {levelGoal}
              </div>
            ) : null}
            {goalColumn ? (
              <div className={styles.column} role="group" aria-labelledby={goalId}>
                <span id={goalId} className={styles.label}>
                  다음 목표
                </span>
                {level === null ? levelGoal : null}
                {badges.length === 0 && upcoming ? <NextBadgeLine badge={upcoming} /> : null}
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

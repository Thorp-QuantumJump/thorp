import { useId, useRef, type Ref } from 'react'
import { Link } from 'react-router-dom'
import type { LearnerOverview } from '@/types/learningMe'
import { settledGainedNotice } from '../article/completionText'
import { useRestoreFocusAfterConceal } from '../article/useRestoreFocusAfterConceal'
import { LEARN_ME_PATH } from '../learningPaths'
import { ContinueCard } from './ContinueCard'
import { LevelEmblem } from './LevelEmblem'
import { LevelPathMini } from './LevelPath'
import { newBadgeLine, nextLevelReachable, unreachableLevelSentence } from './meCopy'
import { NewPill } from './NewPill'
import { QuietNotice } from './QuietNotice'
import type { OverviewState } from './useLearnerOverview'
import styles from './SummaryPanel.module.css'

interface SummaryPanelProps {
  state: OverviewState
  /** 로그인 상태를 다시 확인하는 중이라 자리를 둔 채 숨긴다(research R33) */
  concealed: boolean
  onRetry: () => void
  /** 글로 옮겨 가도 처음 떠난 화면을 잃지 않게 싣는 navigation state */
  linkState?: unknown
  /** 패널의 '이어서 읽기' 연결. 떠 있는 단추가 이 연결이 화면에 보이는지 본다 */
  continueRef?: Ref<HTMLAnchorElement>
}

/**
 * 배우기 목록 위의 '나의 학습' 요약 패널(013 FR-020·FR-030, design-notes §3.1, 목업 `journal`).
 *
 * <p>레벨 표장과 레벨, 다음 레벨까지 남은 경험치, 읽은 글 수, 익힌 용어 수(분모 없음), '이어서 읽기', '모두 보기'를
 * 둔다. 레벨에 닿은 지 7일 안이면 레벨 옆에 '새'를, 받은 지 7일 안의 배지(레벨 갈래 제외)가 있으면 '새 배지: …' 한
 * 줄과 '새'를 둔다. 이번 조회에서 새로 생긴 배지·레벨(`gained`)은 초점을 옮기지 않는 조용한 알림으로 한 번 읽힌다.
 *
 * <p>기록을 받지 못했으면 '학습 기록을 불러오지 못했습니다'와 '다시 시도'만 한 번 보인다. '안 읽음'·0편·레벨 1로 채우지
 * 않는다(FR-020). 받는 중이면 아무것도 그리지 않는다. 공개된 글이 0편이면 '이어서 읽기' 칸을 두지 않는다(계약 §1).
 *
 * <p>로그인 상태를 다시 확인하는 동안에는 자리를 둔 채 숨기고, 확인이 끝나면 숨기기 전에 패널 안에 있던 초점을 되돌린다
 * (research R33, FR-019).
 */
export function SummaryPanel({
  state,
  concealed,
  onRetry,
  linkState,
  continueRef,
}: SummaryPanelProps) {
  const titleId = useId()
  const panelRef = useRef<HTMLElement>(null)
  useRestoreFocusAfterConceal(panelRef, concealed)
  if (state.status === 'off' || state.status === 'loading') return null

  const hide = concealed ? styles.concealed : ''
  if (state.status === 'failed') {
    return (
      <section
        ref={panelRef}
        className={`${styles.panel} ${styles.failed} ${hide}`}
        aria-labelledby={titleId}
        data-summary-panel="failed"
      >
        <h2 id={titleId} className={styles.srOnly}>
          나의 학습
        </h2>
        <p className={styles.failure}>
          <span>학습 기록을 불러오지 못했습니다.</span>
          <button type="button" className={styles.retry} onClick={onRetry}>
            다시 시도
          </button>
        </p>
      </section>
    )
  }

  const { overview } = state
  return (
    <section
      ref={panelRef}
      className={`${styles.panel} ${hide}`}
      aria-labelledby={titleId}
      data-summary-panel="loaded"
    >
      <div className={styles.top}>
        <h2 id={titleId} className={styles.title}>
          나의 학습
        </h2>
        <Link className={styles.more} to={LEARN_ME_PATH} state={linkState}>
          모두 보기
        </Link>
      </div>
      <div className={styles.grid}>
        <LevelCell overview={overview} />
        <div className={styles.cell}>
          <span className={styles.caption}>읽은 글</span>
          <span className={styles.number}>
            {overview.counts.completedArticles}
            <small>편</small>
          </span>
        </div>
        <div className={styles.cell}>
          <span className={styles.caption}>익힌 용어</span>
          <span className={styles.number}>
            {overview.counts.masteredTerms}
            <small>개</small>
          </span>
        </div>
        {overview.counts.publishedArticles > 0 ? (
          <div className={`${styles.cell} ${styles.resumeCell}`}>
            <ContinueCard
              continueReading={overview.continueReading}
              publishedArticles={overview.counts.publishedArticles}
              linkState={linkState}
              linkRef={continueRef}
            />
          </div>
        ) : null}
      </div>
      <NewBadgeRow overview={overview} />
      <QuietNotice text={settledGainedNotice(overview.gained)} />
    </section>
  )
}

function LevelCell({ overview }: { overview: LearnerOverview }) {
  const { level } = overview
  // 다음 레벨이 지금 공개된 단계로 닿을 수 없으면 작은 레벨 길 끝이 점선이고 FR-029 문장을 덧붙인다(FR-031).
  const unreachable = nextLevelReachable(level) ? null : unreachableLevelSentence(level)
  return (
    <div className={`${styles.cell} ${styles.levelCell}`}>
      <span className={styles.emblem} aria-hidden="true">
        <LevelEmblem level={level.current} size={64} />
      </span>
      <div className={styles.levelText}>
        <p className={styles.level}>
          레벨 {level.current}
          {level.isNew ? <NewPill spoken="새로 닿은 레벨" /> : null}
        </p>
        <LevelPathMini level={level} />
        <p className={styles.sub}>
          다음 레벨까지 <b>{level.remainingToNext.toLocaleString('ko-KR')}</b>
        </p>
        {unreachable ? <p className={styles.note}>{unreachable}</p> : null}
      </div>
    </div>
  )
}

function NewBadgeRow({ overview }: { overview: LearnerOverview }) {
  const line = newBadgeLine(overview.badges)
  if (!line) return null
  return (
    <p className={styles.newBadge}>
      <span>{line}</span>
      <NewPill />
    </p>
  )
}

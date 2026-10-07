import type { ReactNode } from 'react'
import type { StageView } from '@/types/learning'
import { stageColor } from '../stageColors'
import { ArticleStamp, type ArticleStampState } from '../me/ArticleStamp'
import type { RoadKind } from './articleProgress'
import { preparingReason, stageMinutes } from './curriculumCopy'
import { StageLandscape } from './StageLandscape'
import { PreparingChip } from './StatusChips'
import styles from './StageBand.module.css'

interface StageBandProps {
  stage: StageView
  headingId: string
  /** 길이 시작되는 첫 띠인가. 길은 첫 띠의 표식에서 출발한다 */
  first?: boolean
  /**
   * 단계 표식의 모양. 상태를 모르면 중립 원판이다. 나의 학습(B13)이 '읽는 중인 단계'(실선 칸)와 '완주한
   * 단계'(체크)를 넘긴다
   */
  markerState?: Extract<ArticleStampState, 'neutral' | 'unread' | 'done'>
  /** 이 띠로 들어오는 길의 종류. 상태를 모르면 중립 길이다 */
  road?: RoadKind
  /**
   * 띠의 진행 자리. 나의 학습(B13)이 글 도장 줄과 단계 완주 메달을 넣는다. 없으면 글 수와 예상 읽기 시간을 보인다
   * (비로그인, FR-023)
   */
  progress?: ReactNode
}

/**
 * 커리큘럼 보기의 단계 '지역 띠'(013 design-notes §3.1).
 *
 * <p>띠에는 단계 번호·이름·수준·한 줄 설명·진행이 있고 오른쪽에 그 단계의 풍경이 있다. 공개하지 않은 단계(`PREPARING`)와
 * 공개했지만 글이 0편인 단계(`REPREPARING`)는 띠 테두리와 풍경이 모두 점선인 '설계 도면'이고, '준비 중'과 사유
 * 문장만 있다. 새 글의 제목·편수·날짜는 보이지 않는다(FR-031, SC-017). 자물쇠나 잠금·예고 어휘를 쓰지 않는다.
 *
 * <p>제목(`h3`)은 공개 안내의 '○단계 보기'와 글 머리의 단계 연결이 초점을 옮길 자리라 `tabIndex=-1`이다.
 */
export function StageBand({ stage, headingId, first = false, markerState = 'neutral', road = 'n', progress }: StageBandProps) {
  const reason = preparingReason(stage.status)
  const style = { ['--stage' as string]: stageColor(stage.number) }

  if (reason) {
    return (
      <>
        <span className={`${styles.marker} ${styles.pendingMarker}`} aria-hidden="true">
          <Marker state="preparing" />
          <span className={styles.markerNumber}>{stage.number}</span>
          <i className={styles.centerAnchor} data-road="d" />
        </span>
        <div className={`${styles.band} ${styles.pending}`} style={style}>
          <div className={styles.art} aria-hidden="true">
            <StageLandscape stageNumber={stage.number} preparing />
          </div>
          <div className={styles.text}>
            <h3 id={headingId} className={styles.heading} tabIndex={-1}>
              <span className={styles.kick}>
                {stage.number}단계 · {stage.levelLabel}
              </span>
              <span className={styles.tagRow}>
                <span className={styles.name}>{stage.name}</span>
                <PreparingChip />
              </span>
            </h3>
            {stage.status === 'PREPARING' ? (
              <span className={styles.srOnly}>아직 공개되지 않은 단계입니다.</span>
            ) : null}
            <p className={styles.summary}>{stage.summary}</p>
            <p className={styles.reason}>{reason}</p>
          </div>
        </div>
      </>
    )
  }

  return (
    <div className={styles.band} style={style}>
      <span className={styles.marker} aria-hidden="true">
        <Marker state={markerState} />
        {markerState === 'done' ? null : <span className={styles.markerNumber}>{stage.number}</span>}
      </span>
      <div className={styles.art} aria-hidden="true">
        <StageLandscape stageNumber={stage.number} />
      </div>
      <div className={styles.text}>
        <h3 id={headingId} className={styles.heading} tabIndex={-1}>
          <span className={styles.kick}>
            {stage.number}단계 · {stage.levelLabel}
          </span>
          <span className={styles.name}>{stage.name}</span>
        </h3>
        <p className={styles.summary}>{stage.summary}</p>
        {progress ?? (
          <p className={styles.progress}>
            {stage.articles.length}편 · 약 {stageMinutes(stage)}분
          </p>
        )}
      </div>
      <i className={styles.anchor} data-road={first ? 'start' : road} />
    </div>
  )
}

/** 단계 표식. 글 도장과 같은 문법(중립 원판·실선 칸·점선 칸·체크)을 넓은 화면 44px, 좁은 화면 36px로 그린다. */
function Marker({ state }: { state: ArticleStampState }) {
  // 원판을 깔아 표식 아래로 지나는 길을 가린다.
  return (
    <>
      <ArticleStamp className={styles.wide} state={state} size={44} disc checkOnly />
      <ArticleStamp className={styles.narrow} state={state} size={36} disc checkOnly />
    </>
  )
}

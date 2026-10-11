import { Link } from 'react-router-dom'
import type { StageView } from '@/types/learning'
import type { StageCount } from '../curriculum/articleProgress'
import { StageLandscape } from '../curriculum/StageLandscape'
import { stageLocation } from '../learningPaths'
import { stageColor } from '../stageColors'
import styles from './StageStations.module.css'

interface StageStationsProps {
  /** 여섯 단계 전부(커리큘럼 응답). 이름과 공개 여부를 쓴다 */
  stages: readonly StageView[]
  /** 공개된 단계의 완료 수('나의 학습' 응답) */
  counts: ReadonlyMap<number, StageCount>
  linkState?: unknown
}

/**
 * '나의 학습' 화면의 단계별 진행(013 FR-020, design-notes §3.3, 목업 `stationsBlock`). 여섯 정거장이다.
 *
 * <p>공개된 단계는 '4편 중 2편 읽음'(다 읽었으면 '완주 · 4편')과 그 단계의 커리큘럼 자리로 가는 연결이다. 정거장 원은 글
 * 도장과 같은 문법(실선 회색 칸)이고 바깥 호의 마디가 글 수, 초록 마디가 읽은 편수다. 공개하지 않았거나 글이 0편인
 * 단계는 점선 칸과 '준비 중'이다. 그림은 보조 기술에 읽히지 않고 글자가 원본이다.
 */
export function StageStations({ stages, counts, linkState }: StageStationsProps) {
  return (
    <ol className={styles.stations}>
      {stages.map((stage, index) => {
        const count = stage.status === 'OPEN' ? counts.get(stage.number) : undefined
        const before = stages[index - 1]
        const dashed = index > 0 && (!count || !before || !counts.get(before.number))
        const ring = <StationRing stageNumber={stage.number} count={count} />
        return (
          <li
            key={stage.stageKey}
            className={[styles.station, dashed ? styles.dashed : null].filter(Boolean).join(' ')}
          >
            {count ? (
              <Link className={styles.node} to={stageLocation(stage.number)} state={linkState}>
                <span className="sr-only">{stage.number}단계 커리큘럼에서 보기</span>
                {ring}
              </Link>
            ) : (
              <span className={`${styles.node} ${styles.pendingNode}`}>{ring}</span>
            )}
            <span className={styles.icon} aria-hidden="true">
              <StageLandscape stageNumber={stage.number} preparing={!count} mini />
            </span>
            <span className={styles.kicker}>{stage.number}단계</span>
            <b className={styles.name}>{stage.name}</b>
            {count ? (
              <span className={styles.progress}>
                {count.completed
                  ? `완주 · ${count.publishedCount}편`
                  : `${count.publishedCount}편 중 ${count.completedCount}편 읽음`}
              </span>
            ) : (
              <span className={styles.preparing}>준비 중</span>
            )}
          </li>
        )
      })}
    </ol>
  )
}

function StationRing({ stageNumber, count }: { stageNumber: number; count?: StageCount }) {
  const segments = count ? Math.max(1, count.publishedCount) : 0
  const gap = 5.5
  const length = segments > 0 ? 100 / segments - gap : 0
  return (
    <>
      <svg className={styles.ring} viewBox="0 0 64 64" aria-hidden="true" focusable="false">
        {count ? (
          <>
            <circle className={styles.plate} cx="32" cy="32" r="24" strokeWidth="1.5" />
            <circle
              className={styles.inner}
              style={{ stroke: stageColor(stageNumber) }}
              cx="32"
              cy="32"
              r="20"
              strokeWidth="1"
            />
            {Array.from({ length: segments }, (_, k) => (
              <circle
                key={k}
                className={k < count.completedCount ? styles.segmentRead : styles.segment}
                cx="32"
                cy="32"
                r="29.5"
                strokeWidth="3"
                strokeLinecap="round"
                pathLength={100}
                strokeDasharray={`${round(length)} ${round(100 - length)}`}
                strokeDashoffset={round(-((100 / segments) * k + gap / 2))}
                transform="rotate(-90 32 32)"
              />
            ))}
          </>
        ) : (
          <>
            <circle
              className={styles.platePending}
              cx="32"
              cy="32"
              r="24"
              strokeWidth="1.5"
              strokeDasharray="4 3"
            />
            <circle
              className={styles.innerPending}
              cx="32"
              cy="32"
              r="20"
              strokeWidth="1"
              strokeDasharray="3 3"
            />
          </>
        )}
      </svg>
      <span className={styles.number} aria-hidden="true">
        {stageNumber}
      </span>
    </>
  )
}

function round(value: number): number {
  return Math.round(value * 100) / 100
}

import type { ReactNode } from 'react'
import type { ArticleProgress } from './articleProgress'
import { progressLabel } from './articleProgress'
import styles from './StatusChips.module.css'

/**
 * 커리큘럼 보기·목록 보기가 함께 쓰는 작은 표시들(design-notes §4 상태 문법).
 *
 * <p>상태마다 모양이 다르다. 완료는 초록 채움과 체크, 읽는 중은 실선 윤곽과 진행 호, 안 읽음은 실선 윤곽, 준비 중은
 * 점선 윤곽, 이전 판은 테두리 없는 채운 꼬리표와 종이 아이콘이다. 색만으로 가르지 않고 글자가 늘 함께 있다. 색은
 * `StatusChips.module.css`가 의미 토큰으로 정한다.
 */

export function ProgressChip({ progress }: { progress: ArticleProgress }) {
  const label = progressLabel(progress)
  if (progress.state === 'done') {
    return (
      <span className={`${styles.chip} ${styles.done}`}>
        <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true" focusable="false">
          <path d="M2.4 6.3 L5 8.8 L9.6 3.6" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        {label}
      </span>
    )
  }
  if (progress.state === 'reading') {
    return (
      <span className={`${styles.chip} ${styles.outlined}`}>
        <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true" focusable="false">
          <circle className={styles.arcTrack} cx="6" cy="6" r="4.6" strokeWidth="1.4" />
          <circle className={styles.arc} cx="6" cy="6" r="4.6" strokeWidth="2" pathLength={100} strokeDasharray={`${progress.percent} 100`} transform="rotate(-90 6 6)" strokeLinecap="round" />
        </svg>
        {label}
      </span>
    )
  }
  return <span className={`${styles.chip} ${styles.outlined} ${styles.quiet}`}>{label}</span>
}

/** '준비 중' 꼬리표. 점선 테두리로 '아직 하지 않음'(실선)과 모양이 다르다(FR-031 표시 규칙). */
export function PreparingChip() {
  return <span className={`${styles.chip} ${styles.preparing}`}>준비 중</span>
}

/** '이전 판' 꼬리표. 실선·점선과 겹치지 않게 테두리 없이 채운다. */
export function PreviousEditionChip() {
  return (
    <span className={`${styles.chip} ${styles.previous}`}>
      <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true" focusable="false">
        <path d="M2.75 1.25 H7.25 L9.75 3.75 V10.75 H2.75 Z M7 1.25 V4 H9.75" fill="none" stroke="currentColor" strokeWidth="1.1" strokeLinejoin="round" />
      </svg>
      이전 판
    </span>
  )
}

interface TermCountProps {
  termCount: number
  progress?: ArticleProgress
  /**
   * '용어' 낱말을 앞에 붙인다. 글 카드처럼 꼬리표 줄에 놓이면 붙이고('용어 2개'), 목록 보기 표처럼 열 머리 칸이 뜻을
   * 알리는 자리에서는 뺀다('2개')
   */
  withLabel?: boolean
}

/**
 * 글의 용어 수. 상태를 알면 익힌 수와 전체 수('용어 1/2', 화면 낭독은 '용어 2개 중 1개 익힘'), 모르면 그 글의 용어
 * 개수('용어 2개')다. 용어가 없는 글에는 아무것도 보이지 않는다(FR-023).
 */
export function TermCount({ termCount, progress, withLabel = true }: TermCountProps) {
  if (termCount <= 0) return null
  const prefix = withLabel ? '용어 ' : ''
  if (!progress) {
    return (
      <span className={styles.meta}>
        {prefix}
        {termCount}개
      </span>
    )
  }
  return (
    <span className={styles.meta}>
      <span aria-hidden="true">
        {prefix}
        {progress.masteredTermCount}/{termCount}
      </span>
      <span className={styles.srOnly}>
        {prefix}
        {termCount}개 중 {progress.masteredTermCount}개 익힘
      </span>
    </span>
  )
}

export function ReadingMinutes({ minutes }: { minutes: number }) {
  return <span className={styles.meta}>약 {minutes}분</span>
}

/** 꼬리표와 읽기 시간·용어 수를 한 줄에 늘어놓는 자리. */
export function ChipRow({ children, className }: { children: ReactNode; className?: string }) {
  return <span className={className ? `${styles.chips} ${className}` : styles.chips}>{children}</span>
}

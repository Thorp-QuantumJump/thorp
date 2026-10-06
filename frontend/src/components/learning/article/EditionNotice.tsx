import { Link } from 'react-router-dom'
import type { Edition, LearningReturnState } from '@/types/learning'
import styles from './EditionNotice.module.css'

interface EditionNoticeProps {
  edition: Edition
  /** 글 머리('이전 판' 표시)인가, 글 끝(기록하지 않는다는 한 줄과 목록 연결)인가 */
  place: 'head' | 'end'
  /** 목록으로 옮겨 가도 처음 떠난 화면을 잃지 않게 받은 돌아갈 자리를 싣는다(term-help-ui §3) */
  learningReturn?: LearningReturnState
}

/**
 * 이전 판 글의 머리 표시와 글 끝 안내(013 FR-031, design-notes §7).
 *
 * <p>첫 단계 공개 뒤 공개되지 않은 단계에 남은 기존 글(`PREVIOUS`)에만 붙는다. 첫 단계 공개 전 글
 * (`PRE_RELEASE`)은 지금 모습 그대로라 아무것도 붙이지 않고, 새 기준 글에도 붙이지 않는다.
 */
export function EditionNotice({ edition, place, learningReturn }: EditionNoticeProps) {
  if (edition !== 'PREVIOUS') return null

  if (place === 'head') {
    return <span className={styles.badge}>이전 판</span>
  }

  return (
    <section className={styles.end} aria-label="이전 판 안내">
      <p>이 글은 새 기준으로 다시 쓰기 전의 판입니다. 그래서 읽은 기록과 확인 문제를 남기지 않습니다.</p>
      <Link
        className={styles.listLink}
        to="/learn"
        state={learningReturn ? { learningReturn } : undefined}
      >
        배우기 목록으로
      </Link>
    </section>
  )
}

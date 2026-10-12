import styles from './RecordLoadFailure.module.css'

/**
 * '학습 기록을 불러오지 못했습니다'와 '다시 시도' 한 줄(013 FR-020, 계약 §0). 받지 못한 기록을 '안 읽음'·0편·레벨 1로
 * 채우지 않고 이 한 줄만 둔다. 요약 패널, '나의 학습' 화면, 회원정보의 '학습 기록' 구역이 함께 쓴다. 조용히 다시 부르지
 * 않고 사용자가 누를 때만 다시 부른다. 놓이는 자리의 여백과 글자 크기는 감싸는 쪽이 정한다.
 */
export function RecordLoadFailure({ onRetry }: { onRetry: () => void }) {
  return (
    <p className={styles.failure}>
      <span>학습 기록을 불러오지 못했습니다.</span>
      <button type="button" className={styles.retry} onClick={onRetry}>
        다시 시도
      </button>
    </p>
  )
}

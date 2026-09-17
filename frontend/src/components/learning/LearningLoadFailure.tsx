import styles from './LearningLoadFailure.module.css'

interface LearningLoadFailureProps {
  /** 서버가 보낸 안내 문장. 없으면 고정 문장을 쓴다 */
  message?: string
  onRetry: () => void
}

const FALLBACK_MESSAGE = '배우기 글을 불러오지 못했습니다.'

/**
 * 배우기 조회가 실패했음을 알린다(013 FR-015, SC-007).
 *
 * <p><b>빈 목록 안내와 다른 모양이다.</b> 게시된 글이 없는 것과 받지 못한 것은 다른 사실이라 이쪽만
 * `role="alert"`와 다시 시도 버튼을 가진다.
 *
 * <p><b>스스로 다시 부르지 않는다.</b> 다시 시도는 사용자가 누를 때만 한다.
 */
export function LearningLoadFailure({ message, onRetry }: LearningLoadFailureProps) {
  return (
    <div className={styles.failure}>
      <p role="alert">{message || FALLBACK_MESSAGE}</p>
      <button type="button" onClick={onRetry}>
        다시 시도
      </button>
    </div>
  )
}

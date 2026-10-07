import styles from './ResumeLine.module.css'

interface ResumeLineProps {
  /** 기록된 가장 먼 비율(1~99) */
  percent: number
  /** 기록된 위치로 옮긴다 */
  onResume: () => void
}

/**
 * 읽는 중 글을 목록·도움말·주소로 열었을 때 맨 위의 한 줄(013 FR-020, quickstart 10-2).
 *
 * <p>글은 맨 위에서 열리고, 이 줄의 '이어서 읽기'를 누르면 기록된 위치로 간다. 완료한 글에는 없다.
 */
export function ResumeLine({ percent, onResume }: ResumeLineProps) {
  return (
    <p className={styles.line}>
      <span>{percent}%까지 읽었습니다</span>
      <span aria-hidden="true">·</span>
      <button type="button" className={styles.button} onClick={onResume}>
        이어서 읽기
      </button>
    </p>
  )
}

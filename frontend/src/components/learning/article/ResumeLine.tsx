import { ArticleStamp } from '../me/ArticleStamp'
import styles from './ResumeLine.module.css'

interface ResumeLineProps {
  /** 기록된 가장 먼 비율(1~99) */
  percent: number
  /** 이 글의 단계. 앞에 두는 작은 글 도장의 안쪽 고리 색이다(꾸밈) */
  stageNumber: number
  /** 기록된 위치로 옮긴다 */
  onResume: () => void
}

/**
 * 읽는 중 글을 목록·도움말·주소로 열었을 때 맨 위의 한 줄(013 FR-020, quickstart 10-2).
 *
 * <p>글은 맨 위에서 열리고, 이 줄의 '이어서 읽기'를 누르면 기록된 위치로 간다. 완료한 글에는 없다.
 *
 * <p>앞에 그 글 단계 색의 '읽는 중' 글 도장을 작게 둔다. 진행 호가 기록된 비율만큼 찬다(목업 `refBlock`의 `pebMini`,
 * T209). 그림이라 보조 기술에 읽히지 않고 움직임이 없다. 글자는 이 줄이 원본이다.
 */
export function ResumeLine({ percent, stageNumber, onResume }: ResumeLineProps) {
  return (
    <p className={styles.line}>
      <span className={styles.stamp} aria-hidden="true">
        <ArticleStamp state="reading" size={28} percent={percent} stageNumber={stageNumber} disc />
      </span>
      <span>{percent}%까지 읽었습니다</span>
      <span aria-hidden="true">·</span>
      <button type="button" className={styles.button} onClick={onResume}>
        이어서 읽기
      </button>
    </p>
  )
}

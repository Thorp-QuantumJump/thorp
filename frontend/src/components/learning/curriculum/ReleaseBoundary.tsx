import type { StageView } from '@/types/learning'
import { boundarySentence } from './curriculumCopy'
import { roadPointProps } from './stageAnchors'
import styles from './ReleaseBoundary.module.css'

/**
 * 공개된 단계가 끝나는 자리의 한 줄(013 FR-031, design-notes §7 '공개 경계').
 *
 * <p>예: '지금 공개된 단계는 1·2단계입니다. 3~6단계는 준비 중입니다.' 단계의 공개 상태(`status`)로 만들고, 글이 0편인
 * 공개 단계(`REPREPARING`)도 공개된 단계로 센다. 공개하지 않은 단계가 없으면 그리지 않는다. 공개 날짜나 다음 공개를
 * 예고하지 않고 움직임도 없다.
 *
 * <p>커리큘럼 보기의 길은 이 줄 앞에서 끊기고 끝 표시를 둔다. 줄 아래에서 준비 중 단계로 가는 점선 길이 새로
 * 시작한다(끊는 길 점).
 */
export function ReleaseBoundary({ stages }: { stages: readonly StageView[] }) {
  const sentence = boundarySentence(stages)
  if (!sentence) return null
  return (
    <p className={styles.boundary}>
      <i className={styles.anchor} {...roadPointProps('preparing', true)} />
      {sentence}
    </p>
  )
}

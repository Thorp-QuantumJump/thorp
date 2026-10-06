import type { Run } from '@/types/learning'
import { RichText } from './RichText'
import styles from './ArticleBody.module.css'

interface MisconceptionProps {
  wrong: readonly Run[]
  correction: readonly Run[]
  blockIndex: number
}

/**
 * '자주 하는 오해' 상자(013 data-model §5-3).
 *
 * <p>틀린 생각 칸에 '오해' 표시를, 상자에 '가상의 예' 표시를 화면이 붙인다(FR-005, FR-017). 틀린 생각을
 * 빨간색으로 칠하지 않는다 — 빨강·파랑은 값의 오르내림을 뜻한다(design-notes §4). 표시는 글자로 한다.
 */
export function Misconception({ wrong, correction, blockIndex }: MisconceptionProps) {
  return (
    <aside className={styles.box} aria-label="자주 하는 오해">
      <p className={styles.boxHead}>
        <span className={styles.boxTitle}>자주 하는 오해</span>
        <span className={styles.tag}>가상의 예</span>
      </p>
      <p className={styles.wrong}>
        <span className={styles.wrongTag}>오해</span>
        <span>
          <RichText runs={wrong} blockIndex={blockIndex} field="wrong" />
        </span>
      </p>
      <p className={styles.correction}>
        <RichText runs={correction} blockIndex={blockIndex} field="correction" />
      </p>
    </aside>
  )
}

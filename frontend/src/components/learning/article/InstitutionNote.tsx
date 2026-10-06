import type { NoteSource, Run } from '@/types/learning'
import { RichText, SourceLink } from './RichText'
import styles from './ArticleBody.module.css'

interface InstitutionNoteProps {
  content: readonly Run[]
  asOf: string | null
  source: NoteSource | null
  blockIndex: number
}

/**
 * '제도 안내' 상자(013 FR-005, FR-026). 세율·공시 기준 같은 제도 수치를 담고 기준 시점과 '(*출처)'를 함께
 * 둔다. 제도 수치는 과거 성과가 아니므로 '가상의 예' 표시를 붙이지 않는다.
 */
export function InstitutionNote({ content, asOf, source, blockIndex }: InstitutionNoteProps) {
  return (
    <aside className={styles.box} aria-label="제도 안내">
      <p className={styles.boxHead}>
        <span className={styles.boxTitle}>제도 안내</span>
      </p>
      <p className={styles.boxText}>
        <RichText runs={content} blockIndex={blockIndex} field="content" />
      </p>
      {asOf || source ? (
        <p className={styles.noteMeta}>
          {asOf ? <span>기준 시점 {asOf}</span> : null}
          {source ? <SourceLink source={source} /> : null}
        </p>
      ) : null}
    </aside>
  )
}

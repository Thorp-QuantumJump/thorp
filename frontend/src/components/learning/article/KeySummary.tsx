import type { Run } from '@/types/learning'
import { RichText } from './RichText'
import styles from './ArticleBody.module.css'

interface KeySummaryProps {
  lines: readonly Run[][]
  blockIndex: number
}

/** 글 맨 위 '핵심 요약' 상자(013 data-model §5-3, FR-026). 줄이 없으면 그리지 않는다. */
export function KeySummary({ lines, blockIndex }: KeySummaryProps) {
  if (lines.length === 0) return null
  return (
    <section className={styles.summary} aria-label="핵심 요약">
      <p className={styles.boxTitle} aria-hidden="true">
        핵심 요약
      </p>
      <ul>
        {lines.map((line, index) => (
          <li key={index}>
            <RichText runs={line} blockIndex={blockIndex} field={`lines.${index}`} />
          </li>
        ))}
      </ul>
    </section>
  )
}

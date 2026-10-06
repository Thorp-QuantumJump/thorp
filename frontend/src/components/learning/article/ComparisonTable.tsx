import type { Run } from '@/types/learning'
import { RichText } from './RichText'
import styles from './ArticleBody.module.css'

interface ComparisonTableProps {
  caption: string
  columns: readonly string[]
  rows: readonly Run[][][]
  blockIndex: number
}

/**
 * 비교 표(013 data-model §5-3). 머리 칸은 `th scope="col"`이고 화면이 '가상의 예' 표시를 붙인다(FR-005).
 *
 * <p>칸은 글자와 '(*출처)'만 담는다(FR-026). 열 다섯·행 여덟이 상한이라 좁은 화면에서도 가로 스크롤 없이
 * 줄바꿈으로 들어간다.
 */
export function ComparisonTable({ caption, columns, rows, blockIndex }: ComparisonTableProps) {
  return (
    <figure className={styles.tableFigure}>
      <table className={styles.table}>
        <caption>
          <span className={styles.tag}>가상의 예</span>
          {caption ? <span className={styles.caption}>{caption}</span> : null}
        </caption>
        <thead>
          <tr>
            {columns.map((column, index) => (
              <th key={index} scope="col">
                {column}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {row.map((cell, cellIndex) => (
                <td key={cellIndex}>
                  <RichText runs={cell} blockIndex={blockIndex} field={`rows.${rowIndex}.${cellIndex}`} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  )
}

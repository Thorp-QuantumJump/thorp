import { ArrowUpRight } from 'lucide-react'
import { useId } from 'react'
import type { LearningSource } from '@/types/learning'
import styles from './ArticleSourceList.module.css'

/**
 * 9월 판 글 끝 출처 목록이다(013 FR-007). 독자 화면과 관리 미리 보기가 같은 목록을 그린다(FR-016). 목록이
 * 비면 그리지 않는다.
 *
 * <p>'출처'와 기관 이름을 한 줄에 잇는다. 주소가 있으면 밑줄과 화살표로 링크임을 보인다.
 */

interface ArticleSourceListProps {
  sources: readonly LearningSource[]
  /** 구역 제목의 단계. 독자 화면은 2, 미리 보기는 4다 */
  headingLevel: 2 | 4
}

export function ArticleSourceList({ sources, headingLevel }: ArticleSourceListProps) {
  const titleId = useId()
  if (sources.length === 0) return null
  const Heading = headingLevel === 2 ? 'h2' : 'h4'
  return (
    <section className={styles.sources} aria-labelledby={titleId}>
      <Heading id={titleId} className={styles.sourcesLabel}>
        출처
      </Heading>
      <ul>
        {sources.map((source, index) => (
          // 출처는 라벨이 겹칠 수 있어 순번으로 자리를 잡는다.
          <li key={index}>
            {source.url ? (
              <a href={source.url} target="_blank" rel="noopener noreferrer">
                {source.label}
                <ArrowUpRight size={14} aria-hidden="true" />
              </a>
            ) : (
              source.label
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

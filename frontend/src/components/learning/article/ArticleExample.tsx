import { useId } from 'react'
import { ExampleEntry } from '@/components/learning/ExampleEntry'
import type { LearningExample } from '@/types/learning'
import styles from './ArticleExample.module.css'

/**
 * 글의 예시 구역이다(013 FR-021). 독자 화면과 관리 미리 보기가 같은 구역을 그린다(FR-016). 둘이 따로 그리면
 * 미리 보기가 독자 화면과 달라져도 알 수 없다.
 *
 * <p>왼쪽은 예시 섹터와 비중, 오른쪽은 그 예시로 시작하는 행동이다. 섹터 이름은 지금 대응표의 이름이고 없으면
 * 코드다.
 */

interface ArticleExampleProps {
  articleId: string
  example: LearningExample
  /** 구역 제목의 단계. 독자 화면은 글 제목(h1) 아래라 2, 미리 보기는 그 안의 글 제목(h3) 아래라 4다 */
  headingLevel: 2 | 4
}

export function ArticleExample({ articleId, example, headingLevel }: ArticleExampleProps) {
  const titleId = useId()
  const Heading = headingLevel === 2 ? 'h2' : 'h4'
  return (
    <section className={styles.example} aria-labelledby={titleId}>
      <Heading id={titleId} className={styles.sectionTitle}>
        예시
      </Heading>
      <div className={styles.exampleSectors}>
        <ul className={styles.exampleList}>
          {example.sectors.map((sector) => (
            <li key={sector.code}>
              <span>{sector.name ?? sector.code}</span>
              <span className={styles.weight}>{formatWeight(sector.weightTenths)}</span>
            </li>
          ))}
        </ul>
      </div>
      <div className={styles.exampleAction}>
        <ExampleEntry articleId={articleId} example={example} />
      </div>
      <p className={styles.disclaimer}>설명을 위한 예시이며 투자 권고가 아닙니다.</p>
    </section>
  )
}

/** 0.1%p 정수를 소수 한 자리 %로 보인다. 1000이 100%다. */
function formatWeight(weightTenths: number): string {
  return `${(weightTenths / 10).toFixed(1)}%`
}

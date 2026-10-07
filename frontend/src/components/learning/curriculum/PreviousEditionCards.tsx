import { Link } from 'react-router-dom'
import type { PreviousEditionArticle } from '@/types/learning'
import { articlePath } from '../learningPaths'
import { PREVIOUS_EDITION_NOTE } from './curriculumCopy'
import { ChipRow, PreviousEditionChip, ReadingMinutes } from './StatusChips'
import styles from './PreviousEditionCards.module.css'

interface PreviousEditionCardsProps {
  articles: readonly PreviousEditionArticle[]
  /** 글로 옮겨 가도 처음 떠난 화면을 잃지 않게 싣는 navigation state */
  linkState?: unknown
}

/**
 * 공개하지 않은 단계에 남은 기존 글(013 FR-031 '이전 판 글', design-notes §3.1).
 *
 * <p>모서리 접힌 쪽지 카드로 보인다. 누구나 열어 읽을 수 있지만 번호·길 위의 노드·상태가 없고 '이전 판' 꼬리표만
 * 있다. 나의 학습에 기록하지 않는다는 것을 묶음 제목과 화면 낭독 문장으로 알린다. 움직임이 없다.
 */
export function PreviousEditionCards({ articles, linkState }: PreviousEditionCardsProps) {
  if (articles.length === 0) return null
  return (
    <div className={styles.group}>
      <h4 className={styles.heading}>
        다시 쓰기 전의 글 <span className={styles.keep}>{articles.length}편 ·</span>{' '}
        <span className={styles.keep}>나의 학습에 기록하지 않습니다</span>
      </h4>
      <ul className={styles.notes}>
        {articles.map((article) => (
          <li key={article.articleId}>
            <Link className={styles.note} to={articlePath(article.articleId)} state={linkState}>
              <span className={styles.paper}>
                <span className={styles.title}>{article.title}</span>
                <span className={styles.question}>{article.question}</span>
                <ChipRow>
                  <PreviousEditionChip />
                  <ReadingMinutes minutes={article.readingMinutes} />
                </ChipRow>
                <span className={styles.srOnly}>{PREVIOUS_EDITION_NOTE}</span>
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  )
}

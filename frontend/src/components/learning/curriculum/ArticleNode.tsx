import { Link } from 'react-router-dom'
import type { CurriculumArticle } from '@/types/learning'
import { ArticleStamp } from '../me/ArticleStamp'
import { roadKind, stampState, type ArticleProgress } from './articleProgress'
import { ChipRow, ProgressChip, ReadingMinutes, TermCount } from './StatusChips'
import styles from './ArticleNode.module.css'

interface ArticleNodeProps {
  article: CurriculumArticle
  stageNumber: number
  /** 단계 안 자리. 길이 굽이치는 가로 위치와 도장 기울기를 정한다 */
  index: number
  /** 나의 학습 상태. 없으면 상태 글자 없이 중립 모양이다(비로그인·기록 없음) */
  progress?: ArticleProgress
  /** 지금 이어 읽을 글. 카드에 초록 빛 한 겹과 진행 호 끝의 빛 점을 준다(design-notes §2) */
  current?: boolean
  /** 글로 옮겨 가도 처음 떠난 화면을 잃지 않게 싣는 navigation state */
  linkState?: unknown
}

/** 1280px에서 노드가 놓이는 가로 위치(px). 네 글마다 완만한 S자를 그린다. 좁은 화면에서는 CSS가 0으로 둔다. */
const OFFSETS = [0, 64, 96, 64]

/**
 * 커리큘럼 보기의 글 하나(013 design-notes §3.1). 길 위의 글 도장과 오른쪽 카드가 함께 한 링크다.
 *
 * <p>카드는 번호·제목·질문·상태·예상 읽기 시간·용어 수를 늘 보인다. 상태를 모르면 상태 글자를 두지 않고 도장도 중립
 * 모양이다. 어느 글도 잠그지 않으며 자물쇠를 두지 않는다(FR-023, FR-031).
 */
export function ArticleNode({ article, stageNumber, index, progress, current = false, linkState }: ArticleNodeProps) {
  const state = stampState(progress)
  const reading = progress?.state === 'reading' ? progress.percent : 0
  const stamp = (size: number, className: string) => (
    <ArticleStamp
      className={className}
      state={state}
      size={size}
      label={article.number}
      percent={reading}
      stageNumber={stageNumber}
      tilt={index}
      disc
      checkOnly={state === 'done'}
      tip={current && state === 'reading'}
    />
  )

  return (
    <li className={styles.item} style={{ ['--x' as string]: `${OFFSETS[index % OFFSETS.length]}px` }}>
      <Link
        className={current ? `${styles.stop} ${styles.current}` : styles.stop}
        to={`/learn/${encodeURIComponent(article.articleId)}`}
        state={linkState}
      >
        {/* 같은 도장을 넓은 화면(48px)과 좁은 화면(40px) 크기로 따로 그린다. 줄이면 상태를 나르는 선이 가늘어진다. */}
        <span className={styles.node} data-road={roadKind(progress)} aria-hidden="true">
          {stamp(48, styles.wide)}
          {stamp(40, styles.narrow)}
        </span>
        <span className={styles.card}>
          <span className={styles.main}>
            <span className={styles.number}>{article.number}</span>
            <span className={styles.title}>{article.title}</span>
            <span className={styles.question}>{article.question}</span>
          </span>
          <ChipRow className={progress ? styles.chips : `${styles.chips} ${styles.chipsGuest}`}>
            {progress ? <ProgressChip progress={progress} /> : null}
            <ReadingMinutes minutes={article.readingMinutes} />
            <TermCount termCount={article.termCount} progress={progress} />
          </ChipRow>
        </span>
      </Link>
    </li>
  )
}

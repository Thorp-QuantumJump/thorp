import { Link } from 'react-router-dom'
import type { CurriculumArticle } from '@/types/learning'
import { articlePath } from '../learningPaths'
import { ArticleStamp } from '../me/ArticleStamp'
import { roadKind, stampState, type ArticleProgress } from './articleProgress'
import { roadPointProps } from './stageAnchors'
import { ChipRow, ProgressChip, ReadingMinutes, TermCount } from './StatusChips'
import styles from './ArticleNode.module.css'

interface ArticleNodeProps {
  article: CurriculumArticle
  stageNumber: number
  /** 단계 안 자리. 길이 굽이치는 가로 위치와 도장 기울기를 정한다 */
  index: number
  /** 나의 학습 상태. 없으면 상태 글자 없이 중립 모양이다(비로그인·기록 없음) */
  progress?: ArticleProgress
  /**
   * 지금 이어 읽을 글인가(나의 학습의 '이어서 읽기'). 카드에 빛을 두고, 읽는 중이면 도장 진행 호 끝에 빛 점을 두며,
   * '이어서 읽기 →' 글자를 더한다(design-notes §2·§3.1). 한 화면에 한 글뿐이다
   */
  current?: boolean
  /** 글로 옮겨 가도 처음 떠난 화면을 잃지 않게 싣는 navigation state */
  linkState?: unknown
}

/** 1280px에서 노드가 놓이는 가로 위치(px). 네 글마다 완만한 S자를 그린다. 좁은 화면에서는 CSS가 0으로 둔다. */
const OFFSETS = [0, 64, 96, 64]

/**
 * 커리큘럼 보기의 글 하나(013 design-notes §3.1, T137). 길 위의 글 도장과 오른쪽 카드가 함께 한 링크다.
 *
 * <p>카드는 번호·제목·질문·상태·예상 읽기 시간·용어 수를 늘 보인다. 상태를 모르면 상태 글자를 두지 않고 도장도 중립
 * 모양이다. 어느 글도 잠그지 않으며 자물쇠를 두지 않는다(FR-023, FR-031).
 *
 * <p>지금 이어 읽을 글(`current`)은 이 화면에서 빛나는 곳 하나다. 카드 뒤의 빛과 '이어서 읽기 →' 글자는 그 글이 다음에
 * 읽을 글이라는 표시일 뿐이고, 카드를 누르면 다른 글처럼 맨 위에서 연다. 목록에서 '읽는 중' 글을 열면 맨 위에서 열고
 * '62%까지 읽었습니다 · 이어서 읽기' 한 줄을 보이기 때문이다(FR-020, data-model §15). 빛과 글자는 나의 학습 상태
 * 표시라 로그인 상태를 다시 확인하는 동안 화면이 숨긴다(`data-learner-mark`, research R33).
 */
export function ArticleNode({ article, stageNumber, index, progress, current = false, linkState }: ArticleNodeProps) {
  const state = stampState(progress)
  const reading = progress?.state === 'reading'
  const stamp = (size: number, className: string) => (
    <ArticleStamp
      className={className}
      state={state}
      size={size}
      label={article.number}
      percent={progress?.state === 'reading' ? progress.percent : 0}
      stageNumber={stageNumber}
      tilt={index}
      disc
      checkOnly={state === 'done'}
      tip={current && reading}
    />
  )

  return (
    <li className={styles.item} style={{ ['--x' as string]: `${OFFSETS[index % OFFSETS.length]}px` }}>
      <Link className={styles.stop} to={articlePath(article.articleId)} state={linkState}>
        {/* 같은 도장을 넓은 화면(48px)과 좁은 화면(40px) 크기로 따로 그린다. 줄이면 상태를 나르는 선이 가늘어진다. */}
        <span
          className={styles.node}
          {...roadPointProps(roadKind(progress))}
          data-learner-mark={progress ? '' : undefined}
          aria-hidden="true"
        >
          {stamp(48, styles.wide)}
          {stamp(40, styles.narrow)}
        </span>
        <span className={styles.card}>
          {current ? <span className={styles.glow} data-learner-mark="" aria-hidden="true" /> : null}
          <span className={styles.main}>
            <span className={styles.number}>{article.number}</span>
            <span className={styles.title}>{article.title}</span>
            <span className={styles.question}>{article.question}</span>
          </span>
          <ChipRow className={progress ? styles.chips : `${styles.chips} ${styles.chipsGuest}`}>
            {progress ? <ProgressChip progress={progress} /> : null}
            <ReadingMinutes minutes={article.readingMinutes} />
            <TermCount termCount={article.termCount} progress={progress} />
            {current ? (
              <span className={styles.goHint} data-learner-mark="">
                이어서 읽기 <span aria-hidden="true">→</span>
              </span>
            ) : null}
          </ChipRow>
        </span>
      </Link>
    </li>
  )
}

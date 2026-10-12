import type { Ref } from 'react'
import { Link } from 'react-router-dom'
import type { ContinueReading } from '@/types/learningMe'
import { articlePath } from '../learningPaths'
import { ArticleStamp } from './ArticleStamp'
import { continueLinkState, stageOfNumber } from './continueLink'
import styles from './ContinueCard.module.css'
import action from './greenAction.module.css'

interface ContinueCardProps {
  continueReading: ContinueReading
  /** 지금 공개된 글 수. 0이면 '모두 읽었습니다'도 그리지 않는다(계약 §1) */
  publishedArticles: number
  linkState?: unknown
  /** '이어서 읽기' 연결. 떠 있는 단추가 이 연결이 화면 밖으로 나갔는지 본다 */
  linkRef?: Ref<HTMLAnchorElement>
}

/**
 * '이어서 읽기' 한 칸(013 FR-020, 목업 `resumeInner`). 요약 패널과 '나의 학습' 화면이 함께 쓴다.
 *
 * <p>마지막으로 연 '읽는 중' 글을 기록된 위치에서 열고, 없으면 읽는 순서상 첫 '안 읽음' 글을 맨 위에서 연다. 공개된 글을
 * 모두 완료했으면 단추 대신 '지금 공개된 글을 모두 읽었습니다.'와, 공개되지 않은 단계가 있으면 '다음 단계는 준비
 * 중입니다.'를 보인다. 공개된 글이 0편이면 아무것도 그리지 않는다.
 */
export function ContinueCard({
  continueReading,
  publishedArticles,
  linkState,
  linkRef,
}: ContinueCardProps) {
  const { kind, articleId, number, title, percent, moreStagesPreparing } = continueReading
  if (kind === 'ALL_DONE' || articleId === null) {
    if (publishedArticles === 0) return null
    return (
      <p className={styles.allDone}>
        지금 공개된 글을 모두 읽었습니다.
        {moreStagesPreparing ? ' 다음 단계는 준비 중입니다.' : null}
      </p>
    )
  }
  const reading = kind === 'RESUME'
  return (
    <div className={styles.resume}>
      <span className={styles.stamp} aria-hidden="true">
        <ArticleStamp
          state={reading ? 'reading' : 'unread'}
          size={44}
          label={number ?? undefined}
          percent={percent ?? 0}
          stageNumber={stageOfNumber(number)}
          disc
        />
      </span>
      <div className={styles.text}>
        <span className={styles.caption}>이어서 읽기</span>
        <span className={styles.title}>
          {number ? <span className={styles.number}>{number}</span> : null}
          {title}
        </span>
        <span className={styles.state}>
          {reading && percent !== null ? `${percent}% 읽는 중` : '안 읽음'}
        </span>
      </div>
      <Link
        ref={linkRef}
        className={`${action.action} ${styles.go}`}
        to={articlePath(articleId)}
        state={continueLinkState(kind, linkState)}
      >
        이어서 읽기
      </Link>
    </div>
  )
}

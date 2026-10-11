import type { StageView } from '@/types/learning'
import { stampState, type ArticleProgressMap, type StageCount } from '../curriculum/articleProgress'
import { ArticleStamp } from './ArticleStamp'
import { StageMedal } from './StageMedal'
import styles from './StageStampWell.module.css'

interface StageStampWellProps {
  stage: StageView
  count: StageCount
  progress?: ArticleProgressMap
}

/**
 * 커리큘럼 보기의 단계 띠에 놓이는 글 도장 줄과 단계 완주 메달(013 design-notes §3.1, 목업 `stampWell`).
 *
 * <p>도장은 그 단계의 글 수만큼 있고, 칸마다 그 글의 상태(완료·읽는 중·안 읽음)로 찍힌다. 어느 글이든 읽으면 그 칸에
 * 찍히고 순서와 무관하다. 줄 끝의 단계 완주 메달은 다 읽기 전에는 실선 회색 아치와 진행 호, 다 읽으면 단계 색
 * 스테인드글라스다. 그림은 보조 기술에 읽히지 않고 아래 글자('4편 중 3편 읽음', '완주 · 4편')가 원본이다.
 */
export function StageStampWell({ stage, count, progress }: StageStampWellProps) {
  const { completedCount, publishedCount, completed } = count
  const percent = publishedCount > 0 ? Math.round((completedCount / publishedCount) * 100) : 0
  const medal = (size: number, className: string) => (
    <StageMedal
      className={className}
      stageNumber={stage.number}
      size={size}
      state={completed ? 'earned' : 'notYet'}
      progress={percent}
    />
  )

  return (
    <div className={styles.well} data-stage-well={stage.number} data-learner-mark="">
      <div className={styles.art} aria-hidden="true">
        <span className={styles.cells}>
          {stage.articles.map((article, index) => {
            const entry = progress?.get(article.articleId)
            const stamp = (size: number, className: string) => (
              <ArticleStamp
                className={className}
                state={stampState(entry)}
                size={size}
                label={article.number}
                percent={entry?.state === 'reading' ? entry.percent : 0}
                stageNumber={stage.number}
                tilt={index}
              />
            )
            return (
              <span key={article.articleId} className={styles.cell}>
                {stamp(44, styles.wide)}
                {stamp(40, styles.narrow)}
              </span>
            )
          })}
        </span>
        <span className={styles.medal}>
          {medal(48, styles.wide)}
          {medal(44, styles.narrow)}
        </span>
      </div>
      <p className={styles.caption}>
        <b>
          {completed
            ? `완주 · ${publishedCount}편`
            : `${publishedCount}편 중 ${completedCount}편 읽음`}
        </b>
        {completed ? null : (
          <>
            <span className={styles.dot} aria-hidden="true">
              ·
            </span>
            <span aria-hidden="true">
              {stage.number}단계 완주 · {completedCount}/{publishedCount}
            </span>
            <span className={styles.srOnly}>
              {stage.number}단계 완주 배지까지 {publishedCount}편 중 {completedCount}편
            </span>
          </>
        )}
      </p>
    </div>
  )
}

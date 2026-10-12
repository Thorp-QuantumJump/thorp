import type { StageView } from '@/types/learning'
import {
  stagePercent,
  stageProgressLabel,
  stampState,
  type ArticleProgressMap,
  type StageCount,
} from '../curriculum/articleProgress'
import { learnerMarkProps } from '../curriculum/stageAnchors'
import { ArticleStamp } from './ArticleStamp'
import { StageMedal } from './StageMedal'
import styles from './StageStampWell.module.css'

interface StageStampWellProps {
  stage: StageView
  count: StageCount
  progress?: ArticleProgressMap
  /**
   * 이 단계의 단계 완주 배지를 받았는가. 받은 뒤 그 단계에 새 글이 게시되면 지금은 다 읽지 않았어도 배지는 그대로다
   * (US6 시나리오 8). 모르면 지금 다 읽었는지로 정한다
   */
  badgeEarned?: boolean
}

/**
 * 커리큘럼 보기의 단계 띠에 놓이는 글 도장 줄과 단계 완주 메달(013 design-notes §3.1, 목업 `stampWell`).
 *
 * <p>도장은 그 단계의 글 수만큼 있고, 칸마다 그 글의 상태(완료·읽는 중·안 읽음)로 찍힌다. 어느 글이든 읽으면 그 칸에
 * 찍히고 순서와 무관하다. 줄 끝의 단계 완주 메달은 배지를 그린다. 받기 전에는 실선 회색 아치와 진행 호, 받았으면 단계
 * 색 스테인드글라스다. 배지를 받은 뒤 새 글이 더해지면 메달은 그대로이고 진행 글자만 새 글을 포함해 센다('5편 중 4편
 * 읽음'). 그림은 보조 기술에 읽히지 않고 아래 글자('4편 중 3편 읽음', '완주 · 4편')가 원본이다.
 */
export function StageStampWell({
  stage,
  count,
  progress,
  badgeEarned = count.completed,
}: StageStampWellProps) {
  const { completedCount, publishedCount, completed } = count
  const medal = (size: number, className: string) => (
    <StageMedal
      className={className}
      stageNumber={stage.number}
      size={size}
      state={badgeEarned ? 'earned' : 'notYet'}
      progress={stagePercent(count)}
    />
  )

  return (
    <div className={styles.well} data-stage-well={stage.number} {...learnerMarkProps()}>
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
        <b>{stageProgressLabel(count)}</b>
        {/* 배지까지 남은 진행은 아직 배지를 받지 않았을 때만 말한다. */}
        {completed || badgeEarned ? null : (
          <>
            <span className={styles.dot} aria-hidden="true">
              ·
            </span>
            <span aria-hidden="true">
              {stage.number}단계 완주 · {completedCount}/{publishedCount}
            </span>
            <span className="sr-only">
              {stage.number}단계 완주 배지까지 {publishedCount}편 중 {completedCount}편
            </span>
          </>
        )}
      </p>
    </div>
  )
}

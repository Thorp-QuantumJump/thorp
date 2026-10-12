import type { CSSProperties } from 'react'
import type { BadgeView, GainedBadge, LevelView, StageProgress } from '@/types/learningMe'
import { stagePercent } from '../curriculum/articleProgress'
import { ArticleStamp } from '../me/ArticleStamp'
import { BadgeMedal } from '../me/BadgeMedal'
import { badgePercent, medalSpecOf, stageBadgeKey } from '../me/badgeRules'
import { ExpLine } from '../me/ExpLine'
import { LevelEmblem } from '../me/LevelEmblem'
import { LevelPathMove } from '../me/LevelPath'
import { cssLinearGradient, GTEXT_STOPS } from '../me/materials'
import { unreachableNextLevelSentence } from '../me/meCopy'
import { ProgressFraction } from '../me/ProgressFraction'
import { StageMedal } from '../me/StageMedal'
import styles from './CompletionScene.module.css'

/** 넘어가는 레벨 숫자의 글자색. 레벨 표장의 숫자와 같은 초록 금속 단이다(`materials.ts`). */
const ROLL_TEXT = {
  ['--gtext' as string]: cssLinearGradient('180deg', GTEXT_STOPS),
} as CSSProperties

/**
 * 완료 카드의 그림 장면(013 design-notes §3.4·§5, 목업 '글 끝'의 `dcPath`·`heroArch`·`heroEmblem`·`mm2`).
 *
 * <p>핵심 장면은 단계의 마지막 글을 끝낸 순간이다. 단계 도장 줄의 마지막 칸이 찍히고, 단계 완주 메달에 색유리가 차고,
 * 레벨 표장의 숫자가 한 칸 넘어가며, 레벨 길의 점이 앞으로 옮겨 간다. 움직임은 `prefers-reduced-motion:
 * no-preference`에서만 있고 950ms 안에 끝나며, 움직임이 없으면 최종 모습을 바로 보인다. 폭죽·소리는 없다.
 *
 * <p><b>그림은 모두 보조 기술에 읽히지 않는다(`aria-hidden`).</b> 글자 정보(완료 날짜·단계 진행·새 배지·레벨·받은
 * 경험치·상태 문장)는 완료 카드의 글자가 원본이다.
 */

/**
 * 단계 도장 줄과 줄 끝의 단계 완주 메달. 도장은 그 단계의 공개된 글 수만큼이고, 읽은 편수만큼 찍혀 있다(어느 글이든
 * 읽으면 찍히고 순서와 무관하다). 이 화면에서 방금 완료했으면 마지막으로 찍힌 칸이 '방금 찍힌 도장'이다.
 *
 * <p>메달은 단계 완주 배지를 그린다. 배지를 받은 뒤 그 단계에 새 글이 게시되어 다 읽지 않은 상태가 되어도 메달은 받은
 * 모습 그대로다(`badgeEarned`, US6 시나리오 8). 도장 칸은 좁은 화면에서 줄어들어 한 줄에 들어간다(design-notes §6).
 */
export function StageStampPath({
  stageProgress,
  freshStamp,
  badgeEarned = stageProgress.stageCompleted,
}: {
  stageProgress: StageProgress
  freshStamp: boolean
  /** 이 단계의 단계 완주 배지를 받았는가. 모르면 지금 다 읽었는지로 정한다 */
  badgeEarned?: boolean
}) {
  const { stageNumber, completedCount, publishedCount, stageCompleted } = stageProgress
  const cells = Math.max(publishedCount, completedCount)
  return (
    <span className={styles.path} aria-hidden="true" data-stage-path={stageNumber}>
      <span className={styles.stamps}>
        {Array.from({ length: cells }, (_, index) => {
          const done = index < completedCount
          const fresh = freshStamp && done && index === completedCount - 1
          return (
            <span key={index} className={fresh ? `${styles.stamp} ${styles.fresh}` : styles.stamp}>
              <ArticleStamp
                state={done ? 'done' : 'unread'}
                size={32}
                stageNumber={stageNumber}
                tilt={index}
                disc
                checkOnly
                fresh={fresh}
              />
            </span>
          )
        })}
      </span>
      <span
        className={
          freshStamp && stageCompleted ? `${styles.medal} ${styles.planted}` : styles.medal
        }
      >
        <StageMedal
          stageNumber={stageNumber}
          size={54}
          state={badgeEarned ? 'earned' : 'notYet'}
          progress={stagePercent(stageProgress)}
        />
      </span>
    </span>
  )
}

/**
 * 새 배지의 메달(96px). 단계 완주 배지는 실선 회색 아치가 접히고 색유리가 찬 메달이 펼쳐진다(움직임 줄이기 설정이
 * 아닐 때만).
 *
 * <p>완료 응답의 배지(계약 §4)에는 단계 번호가 없다. 그래서 단계 번호는 '나의 학습'의 같은 배지에서 읽고, 아직 받지
 * 못했으면 이 글의 단계 완주 배지(`stage-{stageKey}`)일 때만 이 글의 단계 번호를 쓴다. 다른 단계의 배지인데 번호를
 * 모르면 숫자를 지어내지 않고 특별 갈래의 마름모로 그린다(`medalSpecOf`).
 */
export function BadgeHero({
  badge,
  overviewBadges,
  articleStage,
}: {
  badge: GainedBadge
  overviewBadges: readonly BadgeView[] | null
  /** 이 글이 놓인 단계 */
  articleStage: { stageKey: string; number: number }
}) {
  const known = overviewBadges?.find((item) => item.badgeKey === badge.badgeKey)
  const ownStage =
    badge.badgeKey === stageBadgeKey(articleStage.stageKey) ? articleStage.number : null
  const medal = medalSpecOf({ ...badge, stageNumber: known?.stageNumber ?? ownStage })
  if (medal.kind === 'stage') {
    return (
      <span className={styles.hero} aria-hidden="true">
        <span className={styles.flip}>
          <StageMedal
            className={styles.flipFrom}
            stageNumber={medal.stageNumber}
            size={96}
            state="notYet"
            progress={75}
          />
          <StageMedal
            className={styles.flipTo}
            stageNumber={medal.stageNumber}
            size={96}
            state="earned"
            shine
          />
        </span>
      </span>
    )
  }
  return (
    <span className={styles.hero} aria-hidden="true">
      <BadgeMedal {...medal} className={styles.rise} size={96} state="earned" shine />
    </span>
  )
}

/** 새 레벨의 표장(96px). 숫자가 앞 레벨에서 새 레벨로 넘어가고 새 눈금이 나타난다(움직임 줄이기 설정이 아닐 때만). */
export function LevelHero({
  levelBefore,
  levelAfter,
}: {
  levelBefore: number
  levelAfter: number
}) {
  return (
    <span className={styles.hero} aria-hidden="true">
      <span className={styles.roll}>
        <LevelEmblem
          level={levelAfter}
          size={96}
          hideNumber
          shine
          ticksClassName={styles.ticksIn}
        />
        <span className={styles.rollWindow} style={ROLL_TEXT}>
          <span
            className={
              levelBefore !== levelAfter
                ? `${styles.rollStrip} ${styles.rolling}`
                : styles.rollStrip
            }
          >
            <i>{levelBefore}</i>
            <i>{levelAfter}</i>
          </span>
        </span>
      </span>
    </span>
  )
}

/**
 * 다음 목표의 레벨 쪽: 레벨 길과 '경험치 360 · 다음 레벨까지 240'(T208). 길은 그림이고 글자는 보조 기술에 읽힌다.
 * 다음 레벨이 지금 공개된 단계의 글로 닿을 수 없는 레벨이면 길 끝이 점선이고 FR-029 문장('레벨 N부터는 …')을 덧붙인다
 * (FR-031).
 */
export function LevelGoal({
  level,
  expBefore,
  lateMs,
}: {
  level: LevelView
  expBefore: number
  /** 점이 옮겨 가는 움직임을 늦게 온 만큼 당긴다(ms). `null`이면 움직이지 않는다 */
  lateMs: number | null
}) {
  const unreachable = unreachableNextLevelSentence(level)
  return (
    <div className={styles.goal}>
      <LevelPathMove level={level} expBefore={expBefore} lateMs={lateMs} />
      <ExpLine level={level} className={styles.goalText} />
      {unreachable ? <p className={styles.goalNote}>{unreachable}</p> : null}
    </div>
  )
}

/** 다음 목표의 배지 쪽: '다음 배지 · 읽은 글 5편 · 4/5'(T208, 화면 낭독기는 '5편 중 4편'). */
export function NextBadgeLine({ badge }: { badge: BadgeView }) {
  return (
    <p className={styles.next}>
      <span className={styles.nextArt} aria-hidden="true">
        <BadgeMedal
          {...medalSpecOf(badge)}
          size={36}
          state="notYet"
          progress={badgePercent(badge)}
          tier={2}
        />
      </span>
      <span>
        <b>다음 배지</b>
        <span>
          {badge.name}
          {badge.progress ? (
            <>
              {' · '}
              <ProgressFraction badge={badge} />
            </>
          ) : null}
        </span>
      </span>
    </p>
  )
}

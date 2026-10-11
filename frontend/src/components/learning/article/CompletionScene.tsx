import type { BadgeView, GainedBadge, LevelView, StageProgress } from '@/types/learningMe'
import { ArticleStamp } from '../me/ArticleStamp'
import { BadgeMedal } from '../me/BadgeMedal'
import { LevelEmblem } from '../me/LevelEmblem'
import { LevelPathMove } from '../me/LevelPath'
import { familyMedal, progressSpoken } from '../me/meCopy'
import { StageMedal } from '../me/StageMedal'
import styles from './CompletionScene.module.css'

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
 */
export function StageStampPath({
  stageProgress,
  freshStamp,
}: {
  stageProgress: StageProgress
  freshStamp: boolean
}) {
  const { stageNumber, completedCount, publishedCount, stageCompleted } = stageProgress
  const cells = Math.max(publishedCount, completedCount)
  const percent = publishedCount > 0 ? Math.round((completedCount / publishedCount) * 100) : 0
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
          state={stageCompleted ? 'earned' : 'notYet'}
          progress={percent}
        />
      </span>
    </span>
  )
}

/**
 * 새 배지의 메달(96px). 단계 완주 배지는 실선 회색 아치가 접히고 색유리가 찬 메달이 펼쳐진다(움직임 줄이기 설정이
 * 아닐 때만). 단계 번호는 '나의 학습'의 같은 배지에서, 없으면 이름('2단계 완주')에서 읽는다.
 */
export function BadgeHero({
  badge,
  overviewBadges,
}: {
  badge: GainedBadge
  overviewBadges: readonly BadgeView[] | null
}) {
  const kind = familyMedal(badge.family)
  const known = overviewBadges?.find((item) => item.badgeKey === badge.badgeKey)
  if (kind === 'stage') {
    const stageNumber = known?.stageNumber ?? Number(/(\d+)단계/.exec(badge.name)?.[1] ?? 0)
    return (
      <span className={styles.hero} aria-hidden="true">
        <span className={styles.flip}>
          <StageMedal
            className={styles.flipFrom}
            stageNumber={stageNumber}
            size={96}
            state="notYet"
            progress={75}
          />
          <StageMedal
            className={styles.flipTo}
            stageNumber={stageNumber}
            size={96}
            state="earned"
            shine
          />
        </span>
      </span>
    )
  }
  const level = Number(/(\d+)$/.exec(badge.badgeKey)?.[1] ?? /(\d+)/.exec(badge.name)?.[1] ?? 1)
  return (
    <span className={styles.hero} aria-hidden="true">
      <BadgeMedal
        className={styles.rise}
        kind={kind}
        size={96}
        state="earned"
        level={level}
        special={badge.badgeKey === 'exploration' ? 'exploration' : 'persistence'}
        shine
      />
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
        <span className={styles.rollWindow}>
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
 */
export function LevelGoal({
  level,
  expBefore,
  moving,
}: {
  level: LevelView
  expBefore: number
  moving: boolean
}) {
  return (
    <div className={styles.goal}>
      <LevelPathMove level={level} expBefore={expBefore} moving={moving} />
      <p className={styles.goalText}>
        경험치 <b>{level.exp.toLocaleString('ko-KR')}</b> · 다음 레벨까지{' '}
        <b>{level.remainingToNext.toLocaleString('ko-KR')}</b>
      </p>
    </div>
  )
}

/** 다음 목표의 배지 쪽: '다음 배지 · 읽은 글 5편 · 4/5'(T208, 화면 낭독기는 '5편 중 4편'). */
export function NextBadgeLine({ badge }: { badge: BadgeView }) {
  const percent =
    badge.progress && badge.progress.target > 0
      ? (badge.progress.current / badge.progress.target) * 100
      : 0
  return (
    <p className={styles.next}>
      <span className={styles.nextArt} aria-hidden="true">
        <BadgeMedal
          kind={familyMedal(badge.family)}
          size={36}
          state="notYet"
          progress={percent}
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
              <span aria-hidden="true">
                {badge.progress.current}/{badge.progress.target}
              </span>
              <span className="sr-only">{progressSpoken(badge)}</span>
            </>
          ) : null}
        </span>
      </span>
    </p>
  )
}

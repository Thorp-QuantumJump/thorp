import { useId } from 'react'
import type { BadgeView } from '@/types/learningMe'
import { formatCompletedDate } from '../article/completionText'
import { BadgeMedal } from './BadgeMedal'
import { enamelColor } from './materials'
import {
  BADGE_FAMILIES,
  badgeRibbon,
  familyName,
  groupPreparing,
  isBadgeFamily,
  medalSpecOf,
  preparingReasonText,
  progressSpoken,
  type KnownBadgeFamily,
} from './meCopy'
import { NewPill } from './NewPill'
import styles from './BadgeFamilies.module.css'

/**
 * '나의 학습'의 갈래별 배지(013 FR-024, design-notes §3.3, 목업 `famBlock`).
 *
 * <p>갈래마다 사다리 모양으로 받은 배지(금속·에나멜, '받음 날짜')와 아직 받지 않은 배지(실선 회색, 기준과 'n/m')를 늘어놓는다.
 * 수를 세는 갈래(레벨·읽은 글·첫 답 정답·모두 맞힘·익힌 용어)의 준비 중 기준은 작은 점선 실루엣 묶음과 한 줄로 모으고,
 * 받을 수 있게 되는 때의 문구가 같은 것끼리만 묶는다. 이 갈래의 배지 이름은 기준을 그대로 말한다('읽은 글 10편'). 단계
 * 완주 여섯 개와 특별 갈래(끈기·탐험)는 낱장이다. 단계 완주는 단계와 짝을 이루고, 특별 배지는 이름만으로 기준을 알 수
 * 없어 준비 중에도 기준 문장이 보여야 하기 때문이다(FR-024, 목업 `FAMS`의 특별 갈래). 기준은 모두 공개하고 숨긴 배지가
 * 없다. 7일 안에 받은 배지에는 '새'가 붙는다.
 *
 * <p>메달 그림은 보조 기술에 읽히지 않는다. 배지 이름·상태·기준·사유 글자가 원본이다. 받은 배지의 기준 문장은 화면에서는
 * 이름에 맡기고 화면 낭독기에만 읽힌다(FR-019 '배지는 이름과 기준이 글자로 있어').
 *
 * <p>이 화면의 메달에는 지나가는 광택을 주지 않는다. 광택과 빛은 완료 카드의 '방금 얻은 것'에만 한 번 준다(design-notes
 * §5, §2 화려함의 상한).
 */
export function BadgeFamilies({ badges }: { badges: readonly BadgeView[] }) {
  const families = new Map<KnownBadgeFamily, BadgeView[]>()
  for (const badge of badges) {
    const family = isBadgeFamily(badge.family) ? badge.family : 'SPECIAL'
    families.set(family, [...(families.get(family) ?? []), badge])
  }
  return (
    <div className={styles.families}>
      {BADGE_FAMILIES.filter((family) => families.has(family)).map((family) => (
        <Family key={family} family={family} badges={families.get(family) ?? []} />
      ))}
    </div>
  )
}

function Family({ family, badges }: { family: KnownBadgeFamily; badges: BadgeView[] }) {
  const headingId = useId()
  const singles = family === 'STAGE' || family === 'SPECIAL'
  const cards = badges.filter((badge) => singles || badge.status !== 'PREPARING')
  const preparing = singles ? [] : badges.filter((badge) => badge.status === 'PREPARING')
  const groups = groupPreparing(preparing)
  const tierOf = (badge: BadgeView) => Math.min(3, badges.indexOf(badge) + 1)

  return (
    <section
      className={family === 'STAGE' ? `${styles.family} ${styles.wide}` : styles.family}
      aria-labelledby={headingId}
    >
      <h3 id={headingId} className={styles.familyName}>
        {familyName(family)}
      </h3>
      {cards.length > 0 ? (
        <ul className={singles ? `${styles.ladder} ${styles.loose}` : styles.ladder}>
          {cards.map((badge, index) => (
            <BadgeCard
              key={badge.badgeKey}
              badge={badge}
              tier={tierOf(badge)}
              // 사다리를 잇는 작은 길: 받은 배지로 오는 길은 초록, 그 밖은 회색. 낱장 갈래는 잇지 않는다.
              link={singles || index === 0 ? null : badge.status === 'EARNED' ? 'earned' : 'open'}
            />
          ))}
        </ul>
      ) : null}
      {groups.length > 0 ? (
        <>
          <div className={styles.tokens} aria-hidden="true">
            {preparing.map((badge) => (
              <span key={badge.badgeKey} className={styles.token}>
                <BadgeMedal {...medalSpecOf(badge)} size={32} state="preparing" />
                <small>{badgeRibbon(badge.name)}</small>
              </span>
            ))}
          </div>
          {groups.map((group) => (
            <p key={group.line} className={styles.preparingLine}>
              <span>{group.line}</span>
            </p>
          ))}
        </>
      ) : null}
    </section>
  )
}

function BadgeCard({
  badge,
  tier,
  link,
}: {
  badge: BadgeView
  tier: number
  link: 'earned' | 'open' | null
}) {
  const spec = medalSpecOf(badge)
  const { kind } = spec
  const state =
    badge.status === 'EARNED' ? 'earned' : badge.status === 'PREPARING' ? 'preparing' : 'notYet'
  const percent =
    badge.progress && badge.progress.target > 0
      ? (badge.progress.current / badge.progress.target) * 100
      : 0
  const ribbon =
    kind === 'level' || kind === 'stage' || kind === 'special' || state === 'preparing'
      ? null
      : badgeRibbon(badge.name)
  const medal = (size: number, className: string) => (
    <BadgeMedal
      {...spec}
      className={className}
      size={size}
      state={state}
      progress={percent}
      tier={tier}
    />
  )
  const reason = state === 'preparing' ? preparingReasonText(badge) : null

  return (
    <li
      className={link ? `${styles.card} ${styles[link]}` : styles.card}
      data-badge={badge.badgeKey}
      data-status={badge.status}
    >
      <span className={styles.medal} aria-hidden="true">
        {medal(76, styles.wideArt)}
        {medal(52, styles.narrowArt)}
        {ribbon ? (
          <span
            className={
              state === 'earned' ? `${styles.ribbon} ${styles.ribbonEarned}` : styles.ribbon
            }
            style={
              state === 'earned'
                ? { ['--enamel' as string]: enamelColor(kind) ?? 'transparent' }
                : undefined
            }
          >
            {ribbon}
          </span>
        ) : null}
      </span>
      <span className={styles.name}>{badge.name}</span>
      <span className={styles.status}>
        <BadgeStatus badge={badge} />
      </span>
      {state !== 'earned' ? (
        <span className={styles.criterion}>{badge.criterion}</span>
      ) : (
        <span className="sr-only">{badge.criterion}</span>
      )}
      {reason ? <span className={styles.criterion}>{reason}</span> : null}
    </li>
  )
}

/** 배지 상태 글자(design-notes §4). 받음은 날짜와 '새', 아직은 진행, 준비 중은 꼬리표다. */
function BadgeStatus({ badge }: { badge: BadgeView }) {
  if (badge.status === 'EARNED') {
    return (
      <>
        <span className={`${styles.chip} ${styles.earnedChip}`}>
          받음{badge.earnedAt ? ` ${formatCompletedDate(badge.earnedAt)}` : ''}
        </span>
        {badge.isNew ? <NewPill /> : null}
      </>
    )
  }
  if (badge.status === 'PREPARING')
    return <span className={`${styles.chip} ${styles.preparingChip}`}>준비 중</span>
  if (badge.status !== 'NOT_YET') return null
  if (!badge.progress) return <span className={styles.chip}>아직 받지 않음</span>
  return (
    <span className={styles.chip}>
      <span aria-hidden="true">
        아직 받지 않음 · {badge.progress.current}/{badge.progress.target}
      </span>
      <span className="sr-only">아직 받지 않음, {progressSpoken(badge)}</span>
    </span>
  )
}

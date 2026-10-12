import type { BadgeFamily, BadgeView, LearnerOverview, LevelView } from '@/types/learningMe'
import {
  badgeThreshold,
  familyName,
  familyUnit,
  isBadgeFamily,
  isWholeSetBadge,
  nextBadge,
} from './badgeRules'

/**
 * '나의 학습'의 글자(013 FR-020·FR-024·FR-029·FR-030, design-notes §7). 부품과 따로 두어 화면 테스트 없이 문장을 확인한다.
 *
 * <p>무엇을 익혔는지와 다음 목표만 말한다. 서두르게 하거나 놓치면 잃는다는 문장, 잠금·예고 어휘('잠김'·'해금'·
 * '열립니다'·'공개 예정'), 실력·순위로 읽히는 낱말을 쓰지 않는다(FR-024, FR-030, FR-031 표시 규칙). 갈래·기준 수·다음
 * 배지 같은 규칙은 `badgeRules.ts`가 맡는다.
 */

/** 기준 수 글자. 레벨은 수만('5'), 그 밖은 갈래의 단위를 붙인다('10편', '25개'). */
function thresholdText(value: number, family: BadgeFamily): string {
  const unit = familyUnit(family)
  return `${value}${unit === 'exp' ? '' : unit}`
}

/** 진행을 화면 낭독기가 읽을 문장. 예: '5편 중 3편', '경험치 300 중 250'. */
export function progressSpoken(badge: Pick<BadgeView, 'family' | 'progress'>): string {
  if (!badge.progress) return ''
  const { current, target } = badge.progress
  const unit = familyUnit(badge.family)
  if (unit === 'exp')
    return `경험치 ${target.toLocaleString('ko-KR')} 중 ${current.toLocaleString('ko-KR')}`
  return `${target}${unit} 중 ${current}${unit}`
}

/** 남은 양. 예: '1편', '3개', '40'(레벨은 경험치 수만). */
export function remainingText(badge: Pick<BadgeView, 'family' | 'progress'>): string {
  if (!badge.progress) return ''
  const left = Math.max(0, badge.progress.target - badge.progress.current)
  const unit = familyUnit(badge.family)
  return unit === 'exp' ? left.toLocaleString('ko-KR') : `${left}${unit}`
}

/**
 * 공개된 콘텐츠가 늘어야 한다는 사유의 말. 준비 중 배지('… 받을 수 있습니다')와 닿을 수 없는 레벨('… 닿을 수
 * 있습니다')이 같은 말을 쓴다(design-notes §7).
 */
const MORE_CONTENT = new Map<string, string>([
  ['MORE_STAGES', '단계가 더 공개된 뒤'],
  ['MORE_ARTICLES', '글이 더 공개된 뒤'],
])

/**
 * 준비 중 배지의 받을 수 있게 되는 때(FR-024, design-notes §7). 모르는 사유면 `null`이고, 화면은 '준비 중'만 보인다.
 * 지어낸 사유를 보이지 않기 위해서다.
 */
export function preparingReasonText(
  badge: Pick<BadgeView, 'preparingReason' | 'stageNumber'>,
): string | null {
  switch (badge.preparingReason) {
    case 'STAGE_NOT_RELEASED':
      return badge.stageNumber ? `${badge.stageNumber}단계가 공개된 뒤 받을 수 있습니다.` : null
    case 'STAGE_EMPTY':
      return badge.stageNumber
        ? `${badge.stageNumber}단계에 글이 다시 게시된 뒤 받을 수 있습니다.`
        : null
    case 'ALL_STAGES':
      return '여섯 단계가 모두 공개된 뒤 받을 수 있습니다.'
    default: {
      const more = badge.preparingReason ? MORE_CONTENT.get(badge.preparingReason) : undefined
      return more ? `${more} 받을 수 있습니다.` : null
    }
  }
}

/** '레벨 5부터는 단계가 더 공개된 뒤 닿을 수 있습니다.'(FR-029). 모르는 사유면 `null`이다. */
export function unreachableLevelSentence(
  level: Pick<LevelView, 'unreachableFrom' | 'unreachableReason'>,
): string | null {
  const more = MORE_CONTENT.get(level.unreachableReason)
  return more ? `레벨 ${level.unreachableFrom}부터는 ${more} 닿을 수 있습니다.` : null
}

/**
 * 다음 레벨이 지금 공개된 단계의 글로 닿을 수 있는 레벨인가(FR-029). 아니면 화면은 다음 레벨을 목표로 내세우지 않고
 * '레벨 N부터는 …' 문장과 점선 길로 보인다(FR-031).
 */
export function nextLevelReachable(level: Pick<LevelView, 'current' | 'visibleMax'>): boolean {
  return level.current + 1 <= level.visibleMax
}

/** 다음 레벨이 지금 닿을 수 없는 레벨일 때만 FR-029 문장이고, 닿을 수 있으면 `null`이다. 다음 목표 옆에 붙인다. */
export function unreachableNextLevelSentence(
  level: Pick<LevelView, 'current' | 'visibleMax' | 'unreachableFrom' | 'unreachableReason'>,
): string | null {
  return nextLevelReachable(level) ? null : unreachableLevelSentence(level)
}

/**
 * 같은 갈래의 준비 중 배지를 받을 수 있게 되는 때의 문구가 같은 것끼리 묶어 한 줄씩 짓는다(FR-024, design-notes §3.3).
 * 묶음이 한 개면 '읽은 글 27편 · 준비 중 · 여섯 단계가 모두 공개된 뒤 받을 수 있습니다.'(design-notes §7 '레벨 5 · 준비
 * 중 · …'), 여럿이면 '준비 중인 기준: 읽은 글 10편, 20편. …'이다. 어느 쪽이든 '준비 중'이라는 상태 낱말이 글자로 있다.
 * 점선 실루엣 묶음은 그림이라 화면 낭독기가 읽지 않기 때문이다. 기준 글자는 숨기지 않는다. 사유를 모르는 배지는 기준과
 * '준비 중'만 한 줄로 둔다.
 */
export function preparingLines(badges: readonly BadgeView[]): string[] {
  const groups: { reason: string | null; badges: BadgeView[] }[] = []
  for (const badge of badges) {
    const reason = preparingReasonText(badge)
    const group = reason === null ? undefined : groups.find((item) => item.reason === reason)
    if (group) group.badges.push(badge)
    else groups.push({ reason, badges: [badge] })
  }
  return groups.map(({ reason, badges: members }) => {
    const criteria = criteriaText(members)
    if (reason === null) return `${criteria} · 준비 중`
    return members.length === 1
      ? `${criteria} · 준비 중 · ${reason}`
      : `준비 중인 기준: ${criteria}. ${reason}`
  })
}

/**
 * 묶음의 기준 글자. 배지가 하나면 그 이름이다. 여럿이고 모두 같은 갈래의 수를 세는 배지면 갈래 이름 뒤에 키의 기준 수를
 * 잇는다('읽은 글 10편, 20편'). 셋 이상 이어진 수면 물결표로 잇는다('레벨 5~8'). 그 밖에는 이름을 쉼표로 잇는다.
 */
function criteriaText(badges: readonly BadgeView[]): string {
  const names = badges.map((badge) => badge.name)
  const family = badges[0]?.family
  const thresholds = badges.map((badge) => badgeThreshold(badge.badgeKey))
  if (
    badges.length <= 1 ||
    family === undefined ||
    !isBadgeFamily(family) ||
    badges.some((badge) => badge.family !== family) ||
    thresholds.some((value) => value === null)
  )
    return names.join(', ')
  const numbers = thresholds as number[]
  const consecutive = numbers.every(
    (value, index) => index === 0 || value === numbers[index - 1] + 1,
  )
  if (consecutive && numbers.length >= 3)
    return `${familyName(family)} ${numbers[0]}~${thresholdText(numbers[numbers.length - 1], family)}`
  return `${familyName(family)} ${numbers.map((value) => thresholdText(value, family)).join(', ')}`
}

/**
 * 다음 목표 문장(목업 `DONE_MSG` 끝). 예: '다음 레벨까지 240, 다음 배지(읽은 글 5편)까지 1편입니다.' 레벨이나 배지
 * 가운데 하나만 있으면 그것만 말한다. 둘 다 없으면 빈 문자열이다. 지금 닿을 수 없는 다음 레벨은 목표로 말하지 않는다.
 */
export function nextGoalSentence(overview: Pick<LearnerOverview, 'level' | 'badges'>): string {
  const parts: string[] = []
  if (overview.level.remainingToNext > 0 && nextLevelReachable(overview.level))
    parts.push(`다음 레벨까지 ${overview.level.remainingToNext.toLocaleString('ko-KR')}`)
  const badge = nextBadge(overview.badges)
  if (badge) parts.push(`다음 배지(${badge.name})까지 ${remainingText(badge)}`)
  return parts.length === 0 ? '' : `${parts.join(', ')}입니다.`
}

/**
 * 요약 패널의 새 배지 한 줄(FR-030, design-notes §3.1). 받은 지 7일 안의 배지 가운데 가장 최근 것의 이름이고, 여럿이면
 * '외 n개'를 붙인다. 레벨 갈래는 레벨 옆 '새'와 겹치므로 뺀다. 없으면 `null`이다.
 */
export function newBadgeLine(badges: readonly BadgeView[]): string | null {
  const fresh = badges.filter(
    (badge) => badge.isNew && badge.status === 'EARNED' && badge.family !== 'LEVEL',
  )
  if (fresh.length === 0) return null
  const latest = fresh.reduce((a, b) => ((b.earnedAt ?? '') > (a.earnedAt ?? '') ? b : a))
  return fresh.length === 1
    ? `새 배지: ${latest.name}`
    : `새 배지: ${latest.name} 외 ${fresh.length - 1}개`
}

/**
 * 배지 메달 아래 리본의 기준 낱말. 수를 세는 배지는 기준 수와 단위('5편', '10개'), 그때 게시된 전체가 기준인 배지는
 * '전체'다. 배지 키에서 읽고, 그 밖의 배지는 빈 문자열이다.
 */
export function badgeRibbon(badge: Pick<BadgeView, 'badgeKey' | 'family'>): string {
  if (isWholeSetBadge(badge.badgeKey)) return '전체'
  const threshold = badgeThreshold(badge.badgeKey)
  return threshold === null ? '' : thresholdText(threshold, badge.family)
}

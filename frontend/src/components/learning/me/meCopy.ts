import type { BadgeFamily, BadgeView, LearnerOverview, LevelView } from '@/types/learningMe'
import type { MedalKind } from './medalShapes'

/**
 * '나의 학습'의 글자(013 FR-020·FR-024·FR-029·FR-030, design-notes §7). 부품과 따로 두어 화면 테스트 없이 문장을 확인한다.
 *
 * <p>무엇을 익혔는지와 다음 목표만 말한다. 서두르게 하거나 놓치면 잃는다는 문장, 잠금·예고 어휘('잠김'·'해금'·
 * '열립니다'·'공개 예정'), 실력·순위로 읽히는 낱말을 쓰지 않는다(FR-024, FR-030, FR-031 표시 규칙).
 */

/** 화면이 이름과 메달을 아는 배지 갈래. 응답의 `badges`가 이 순서로 온다(계약 §1). */
export const BADGE_FAMILIES = [
  'LEVEL',
  'READ',
  'CORRECT',
  'PERFECT',
  'STAGE',
  'TERMS',
  'SPECIAL',
] as const satisfies readonly BadgeFamily[]

export type KnownBadgeFamily = (typeof BADGE_FAMILIES)[number]

const FAMILY_NAMES: Record<KnownBadgeFamily, string> = {
  LEVEL: '레벨',
  READ: '읽은 글',
  CORRECT: '첫 답 정답',
  PERFECT: '모두 맞힘',
  STAGE: '단계 완주',
  TERMS: '익힌 용어',
  SPECIAL: '특별',
}

const FAMILY_MEDALS: Record<KnownBadgeFamily, MedalKind> = {
  LEVEL: 'level',
  READ: 'read',
  CORRECT: 'first',
  PERFECT: 'all',
  STAGE: 'stage',
  TERMS: 'terms',
  SPECIAL: 'special',
}

export function isBadgeFamily(value: BadgeFamily): value is KnownBadgeFamily {
  return (BADGE_FAMILIES as readonly string[]).includes(value)
}

export function familyName(family: KnownBadgeFamily): string {
  return FAMILY_NAMES[family]
}

/** 갈래의 메달 실루엣. 모르는 갈래는 특별 갈래의 마름모로 그린다. */
export function familyMedal(family: BadgeFamily): MedalKind {
  return isBadgeFamily(family) ? FAMILY_MEDALS[family] : 'special'
}

/** 특별 갈래의 배지(data-model §12-4). 글리프가 서로 다르다. */
export type SpecialBadge = 'persistence' | 'exploration'

/** 특별 갈래의 어느 배지인가. 키가 카탈로그의 `persistence`·`exploration`이 아니면 `null`이다. */
export function specialBadgeOf(badgeKey: string): SpecialBadge | null {
  return badgeKey === 'persistence' || badgeKey === 'exploration' ? badgeKey : null
}

/**
 * 레벨 갈래 배지의 레벨. 배지 키는 바뀌지 않는 카탈로그 값이고 레벨 배지는 `level-{n}`이다(data-model §2·§12-4). 그
 * 모양이 아니면 `null`이다. 화면에 보이는 이름('레벨 3')에서는 읽지 않는다.
 */
export function badgeLevel(badgeKey: string): number | null {
  const match = /^level-(\d+)$/.exec(badgeKey)
  return match ? Number(match[1]) : null
}

/** 그 단계의 단계 완주 배지 키(`stage-{stageKey}`, data-model §12-4). */
export function stageBadgeKey(stageKey: string): string {
  return `stage-${stageKey}`
}

/** 메달 그림이 갈래마다 받는 값. 레벨 표장은 레벨이, 단계 완주 메달은 단계 번호가 있어야 그린다. */
export type MedalSpec =
  | { kind: 'level'; level: number }
  | { kind: 'stage'; stageNumber: number }
  | { kind: Exclude<MedalKind, 'level' | 'stage'>; special?: SpecialBadge }

/**
 * 배지 하나의 메달(design-notes §3.3). 레벨은 배지 키에서, 단계 번호는 응답의 `stageNumber`에서 읽는다. 그 값이 없거나
 * 갈래를 모르면 숫자를 지어내지 않고 특별 갈래의 마름모로 그린다(원칙 VI). 모르는 특별 배지는 글리프 없이 그린다.
 */
export function medalSpecOf(
  badge: Pick<BadgeView, 'badgeKey' | 'family' | 'stageNumber'>,
): MedalSpec {
  const kind = familyMedal(badge.family)
  if (kind === 'level') {
    const level = badgeLevel(badge.badgeKey)
    return level === null ? { kind: 'special' } : { kind, level }
  }
  if (kind === 'stage') {
    return badge.stageNumber === null
      ? { kind: 'special' }
      : { kind, stageNumber: badge.stageNumber }
  }
  if (kind === 'special') return { kind, special: specialBadgeOf(badge.badgeKey) ?? undefined }
  return { kind }
}

/** 진행을 세는 단위. 레벨은 경험치로 센다. */
function unitOf(family: BadgeFamily): '편' | '개' | 'exp' | '' {
  switch (family) {
    case 'READ':
    case 'PERFECT':
    case 'STAGE':
      return '편'
    case 'CORRECT':
    case 'TERMS':
      return '개'
    case 'LEVEL':
      return 'exp'
    default:
      return ''
  }
}

/** 진행을 화면 낭독기가 읽을 문장. 예: '5편 중 3편', '경험치 300 중 250'. */
export function progressSpoken(badge: Pick<BadgeView, 'family' | 'progress'>): string {
  if (!badge.progress) return ''
  const { current, target } = badge.progress
  const unit = unitOf(badge.family)
  if (unit === 'exp')
    return `경험치 ${target.toLocaleString('ko-KR')} 중 ${current.toLocaleString('ko-KR')}`
  return `${target}${unit} 중 ${current}${unit}`
}

/** 남은 양. 예: '1편', '3개', '40'(레벨은 경험치 수만). */
export function remainingText(badge: Pick<BadgeView, 'family' | 'progress'>): string {
  if (!badge.progress) return ''
  const left = Math.max(0, badge.progress.target - badge.progress.current)
  const unit = unitOf(badge.family)
  return unit === 'exp' ? left.toLocaleString('ko-KR') : `${left}${unit}`
}

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
    case 'MORE_STAGES':
      return '단계가 더 공개된 뒤 받을 수 있습니다.'
    case 'MORE_ARTICLES':
      return '글이 더 공개된 뒤 받을 수 있습니다.'
    default:
      return null
  }
}

/** '레벨 5부터는 단계가 더 공개된 뒤 닿을 수 있습니다.'(FR-029). 모르는 사유면 `null`이다. */
export function unreachableLevelSentence(
  level: Pick<LevelView, 'unreachableFrom' | 'unreachableReason'>,
): string | null {
  switch (level.unreachableReason) {
    case 'MORE_STAGES':
      return `레벨 ${level.unreachableFrom}부터는 단계가 더 공개된 뒤 닿을 수 있습니다.`
    case 'MORE_ARTICLES':
      return `레벨 ${level.unreachableFrom}부터는 글이 더 공개된 뒤 닿을 수 있습니다.`
    default:
      return null
  }
}

/** 준비 중 기준 한 줄. 같은 갈래에서 받을 수 있게 되는 때가 같은 것끼리 묶는다(FR-024, design-notes §3.3). */
export type PreparingGroup = {
  reason: string
  badges: BadgeView[]
  /** 보이는 한 줄. 예: '준비 중인 기준: 읽은 글 10편, 20편. 단계가 더 공개된 뒤 받을 수 있습니다.' */
  line: string
}

/**
 * 같은 갈래의 준비 중 배지를 사유 문장이 같은 것끼리 묶는다. 묶음이 한 개면 '읽은 글 27편 · 준비 중 · 여섯 단계가 모두
 * 공개된 뒤 받을 수 있습니다.'(design-notes §7 '레벨 5 · 준비 중 · …'), 여럿이면 '준비 중인 기준: 읽은 글 10편, 20편.
 * …'이다. 어느 쪽이든 '준비 중'이라는 상태 낱말이 글자로 있다. 점선 실루엣 묶음은 그림이라 화면 낭독기가 읽지 않기
 * 때문이다. 기준 글자(배지 이름)는 숨기지 않는다. 사유를 모르는 배지는 이름과 '준비 중'만 한 줄로 둔다.
 */
export function groupPreparing(badges: readonly BadgeView[]): PreparingGroup[] {
  const groups: { reason: string | null; badges: BadgeView[] }[] = []
  for (const badge of badges) {
    const reason = preparingReasonText(badge)
    const group = reason === null ? undefined : groups.find((item) => item.reason === reason)
    if (group) group.badges.push(badge)
    else groups.push({ reason, badges: [badge] })
  }
  return groups.map(({ reason, badges: members }) => {
    const names = joinNames(members.map((badge) => badge.name))
    if (reason === null) return { reason: '', badges: members, line: `${names} · 준비 중` }
    const line =
      members.length === 1
        ? `${names} · 준비 중 · ${reason}`
        : `준비 중인 기준: ${names}. ${reason}`
    return { reason, badges: members, line }
  })
}

/**
 * 같은 머리말을 가진 이름을 줄인다. '읽은 글 10편', '읽은 글 20편' → '읽은 글 10편, 20편'. 셋 이상의 이어진 수면
 * 물결표로 잇는다. '레벨 5'~'레벨 8' → '레벨 5~8'. 모양이 다르면 그대로 쉼표로 잇는다.
 */
export function joinNames(names: readonly string[]): string {
  if (names.length <= 1) return names.join('')
  const parts = names.map((name) => /^(.*?)(\d+)(\D*)$/.exec(name))
  const first = parts[0]
  const sameShape =
    first !== null &&
    parts.every((part) => part !== null && part[1] === first[1] && part[3] === first[3])
  if (!sameShape || first === null) return names.join(', ')
  const numbers = parts.map((part) => Number(part?.[2]))
  const consecutive = numbers.every(
    (value, index) => index === 0 || value === numbers[index - 1] + 1,
  )
  if (consecutive && numbers.length >= 3)
    return `${first[1]}${numbers[0]}~${numbers[numbers.length - 1]}${first[3]}`
  return [names[0], ...numbers.slice(1).map((value) => `${value}${first[3]}`)].join(', ')
}

/**
 * 단계 완주 배지를 이미 받은 단계 번호. 단계 완주 메달은 이 배지를 그린다. 배지를 받은 뒤 그 단계에 새 글이 게시되면
 * 단계 진행은 새 글을 포함해 다시 세지만 배지는 그대로 남는다(US6 시나리오 8, FR-024). 그래서 메달은 '지금 다 읽었는가'가
 * 아니라 '배지를 받았는가'로 그린다.
 */
export function earnedStageNumbers(badges: readonly BadgeView[]): ReadonlySet<number> {
  const numbers = new Set<number>()
  for (const badge of badges) {
    if (badge.family === 'STAGE' && badge.status === 'EARNED' && badge.stageNumber !== null) {
      numbers.add(badge.stageNumber)
    }
  }
  return numbers
}

/**
 * 다음 배지(FR-030 '다음 목표'). 레벨 갈래를 뺀 아직 받지 않은 배지 가운데 남은 양이 가장 적은 것이다. 같으면 응답의
 * 순서(갈래 순서와 사다리 순서)를 따른다. 진행이 없는 배지(끈기 등)는 고르지 않는다.
 */
export function nextBadge(badges: readonly BadgeView[]): BadgeView | null {
  let best: BadgeView | null = null
  let bestLeft = Number.POSITIVE_INFINITY
  for (const badge of badges) {
    if (badge.status !== 'NOT_YET' || badge.family === 'LEVEL' || !badge.progress) continue
    const left = badge.progress.target - badge.progress.current
    if (left <= 0) continue
    if (left < bestLeft) {
      best = badge
      bestLeft = left
    }
  }
  return best
}

/**
 * 다음 레벨이 지금 공개된 단계의 글로 닿을 수 있는 레벨인가(FR-029). 아니면 화면은 다음 레벨을 목표로 내세우지 않고
 * '레벨 N부터는 …' 문장과 점선 길로 보인다(FR-031).
 */
export function nextLevelReachable(level: Pick<LevelView, 'current' | 'visibleMax'>): boolean {
  return level.current + 1 <= level.visibleMax
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

/** 배지 이름 끝의 기준 낱말(리본). 예: '읽은 글 5편' → '5편', '그때 게시된 용어 전체' → '전체'. */
export function badgeRibbon(name: string): string {
  const words = name.trim().split(/\s+/)
  return words[words.length - 1] ?? ''
}

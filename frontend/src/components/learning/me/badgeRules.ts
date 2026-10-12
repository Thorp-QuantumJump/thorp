import type { BadgeFamily, BadgeView } from '@/types/learningMe'
import type { MedalKind } from './medalShapes'

/**
 * '나의 학습' 배지의 규칙(013 data-model §12-4, design-notes §3.3).
 *
 * <p>갈래마다 화면이 아는 것(이름·메달 실루엣·세는 단위), 배지 키에 담긴 기준, 메달 그림에 넘길 값, 응답에서 다음 배지와
 * 받은 단계 완주를 고르는 일을 이 모듈 하나가 맡는다. 화면 문장은 `meCopy.ts`가 짓는다.
 *
 * <p><b>화면에 보이는 배지 이름에서 숫자를 읽지 않는다.</b> 배지 키는 바뀌지 않는 카탈로그 값이라(data-model §2) 기준
 * 수와 레벨은 키에서 읽고, 단계 번호는 응답의 `stageNumber`에서 읽는다.
 */

/** 화면이 아는 배지 갈래. 응답의 `badges`가 이 순서로 온다(계약 §1). */
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

/** 진행을 세는 단위. 레벨은 경험치로 센다. 특별 갈래는 세는 단위가 없다. */
export type CountUnit = '편' | '개' | 'exp' | ''

/** 갈래마다 화면이 아는 것. 이름은 '나의 학습' 갈래 제목이고, 단위는 진행과 기준 수에 붙인다. */
const FAMILIES: Record<KnownBadgeFamily, { name: string; medal: MedalKind; unit: CountUnit }> = {
  LEVEL: { name: '레벨', medal: 'level', unit: 'exp' },
  READ: { name: '읽은 글', medal: 'read', unit: '편' },
  CORRECT: { name: '첫 답 정답', medal: 'first', unit: '개' },
  PERFECT: { name: '모두 맞힘', medal: 'all', unit: '편' },
  STAGE: { name: '단계 완주', medal: 'stage', unit: '편' },
  TERMS: { name: '익힌 용어', medal: 'terms', unit: '개' },
  SPECIAL: { name: '특별', medal: 'special', unit: '' },
}

export function isBadgeFamily(value: BadgeFamily): value is KnownBadgeFamily {
  return (BADGE_FAMILIES as readonly string[]).includes(value)
}

export function familyName(family: KnownBadgeFamily): string {
  return FAMILIES[family].name
}

/** 갈래의 메달 실루엣. 모르는 갈래는 특별 갈래의 마름모로 그린다. */
export function familyMedal(family: BadgeFamily): MedalKind {
  return isBadgeFamily(family) ? FAMILIES[family].medal : 'special'
}

/** 갈래의 세는 단위. 모르는 갈래는 단위가 없다. */
export function familyUnit(family: BadgeFamily): CountUnit {
  return isBadgeFamily(family) ? FAMILIES[family].unit : ''
}

/** 특별 갈래의 배지(data-model §12-4). 글리프가 서로 다르다. */
export type SpecialBadge = 'persistence' | 'exploration'

/** 특별 갈래의 어느 배지인가. 키가 카탈로그의 `persistence`·`exploration`이 아니면 `null`이다. */
export function specialBadgeOf(badgeKey: string): SpecialBadge | null {
  return badgeKey === 'persistence' || badgeKey === 'exploration' ? badgeKey : null
}

/**
 * 수를 세는 배지의 기준 수. 키가 `{갈래}-{n}`(`read-10`·`level-3`)이면 n이고, 아니면(`terms-all`·`persistence`) `null`이다.
 */
export function badgeThreshold(badgeKey: string): number | null {
  const match = /^[a-z]+-(\d+)$/.exec(badgeKey)
  return match ? Number(match[1]) : null
}

/** 기준이 '그때 게시된 전체'인 배지인가(`terms-all`). */
export function isWholeSetBadge(badgeKey: string): boolean {
  return /^[a-z]+-all$/.test(badgeKey)
}

/** 레벨 갈래 배지의 레벨. 레벨 배지 키는 `level-{n}`이다. 그 모양이 아니면 `null`이다. */
export function badgeLevel(badgeKey: string): number | null {
  return /^level-\d+$/.test(badgeKey) ? badgeThreshold(badgeKey) : null
}

/** 그 단계의 단계 완주 배지 키(`stage-{stageKey}`). */
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

/** 아직 받지 않은 배지의 진행 비율(0~100). 진행이 없으면 0이다. 메달의 진행 호가 쓴다. */
export function badgePercent(badge: Pick<BadgeView, 'progress'>): number {
  const progress = badge.progress
  return progress && progress.target > 0 ? (progress.current / progress.target) * 100 : 0
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

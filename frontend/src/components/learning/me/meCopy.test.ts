import { describe, expect, it } from 'vitest'
import { learnerOverview } from '@/test/learningMeFixtures'
import type { BadgeView } from '@/types/learningMe'
import {
  badgeLevel,
  badgePercent,
  badgeThreshold,
  earnedStageNumbers,
  medalSpecOf,
  nextBadge,
  specialBadgeOf,
  stageBadgeKey,
} from './badgeRules'
import {
  badgeRibbon,
  newBadgeLine,
  nextGoalSentence,
  preparingLines,
  preparingReasonText,
  progressSpoken,
  unreachableLevelSentence,
  unreachableNextLevelSentence,
} from './meCopy'

/**
 * 013 T192·T194·T208. '나의 학습'의 문장(FR-024·FR-029·FR-030, design-notes §7). quickstart 10-8·11-3·11-5.
 */

function badge(
  overrides: Partial<BadgeView> & Pick<BadgeView, 'badgeKey' | 'family' | 'name'>,
): BadgeView {
  return {
    criterion: '기준',
    status: 'PREPARING',
    earnedAt: null,
    isNew: false,
    progress: null,
    preparingReason: 'MORE_STAGES',
    stageNumber: null,
    ...overrides,
  }
}

describe('준비 중 사유 문장', () => {
  it('사유마다 design-notes §7의 문장이다', () => {
    expect(preparingReasonText({ preparingReason: 'STAGE_NOT_RELEASED', stageNumber: 3 })).toBe(
      '3단계가 공개된 뒤 받을 수 있습니다.',
    )
    expect(preparingReasonText({ preparingReason: 'STAGE_EMPTY', stageNumber: 2 })).toBe(
      '2단계에 글이 다시 게시된 뒤 받을 수 있습니다.',
    )
    expect(preparingReasonText({ preparingReason: 'ALL_STAGES', stageNumber: null })).toBe(
      '여섯 단계가 모두 공개된 뒤 받을 수 있습니다.',
    )
    expect(preparingReasonText({ preparingReason: 'MORE_STAGES', stageNumber: null })).toBe(
      '단계가 더 공개된 뒤 받을 수 있습니다.',
    )
    expect(preparingReasonText({ preparingReason: 'MORE_ARTICLES', stageNumber: null })).toBe(
      '글이 더 공개된 뒤 받을 수 있습니다.',
    )
  })

  it('모르는 사유는 지어내지 않는다', () => {
    expect(preparingReasonText({ preparingReason: 'SOMETHING_NEW', stageNumber: null })).toBeNull()
  })

  it('닿을 수 없는 레벨 문장은 사유를 따른다(FR-029, quickstart 10-8)', () => {
    expect(unreachableLevelSentence({ unreachableFrom: 5, unreachableReason: 'MORE_STAGES' })).toBe(
      '레벨 5부터는 단계가 더 공개된 뒤 닿을 수 있습니다.',
    )
    expect(
      unreachableLevelSentence({ unreachableFrom: 9, unreachableReason: 'MORE_ARTICLES' }),
    ).toBe('레벨 9부터는 글이 더 공개된 뒤 닿을 수 있습니다.')
  })
})

describe('준비 중 기준 묶음(FR-024, design-notes §3.3)', () => {
  it('같은 사유끼리만 한 줄로 묶고 기준 수를 잇는다', () => {
    expect(
      preparingLines([
        badge({ badgeKey: 'read-10', family: 'READ', name: '읽은 글 10편' }),
        badge({ badgeKey: 'read-20', family: 'READ', name: '읽은 글 20편' }),
        badge({
          badgeKey: 'read-27',
          family: 'READ',
          name: '읽은 글 27편',
          preparingReason: 'ALL_STAGES',
        }),
      ]),
    ).toEqual([
      '준비 중인 기준: 읽은 글 10편, 20편. 단계가 더 공개된 뒤 받을 수 있습니다.',
      '읽은 글 27편 · 준비 중 · 여섯 단계가 모두 공개된 뒤 받을 수 있습니다.',
    ])
  })

  it('기준 수는 배지 키에서 읽고, 셋 이상 이어진 레벨은 물결표로 잇는다', () => {
    const levels = [5, 6, 7, 8].map((n) =>
      badge({ badgeKey: `level-${n}`, family: 'LEVEL', name: `레벨 ${n}` }),
    )
    expect(preparingLines(levels)).toEqual([
      '준비 중인 기준: 레벨 5~8. 단계가 더 공개된 뒤 받을 수 있습니다.',
    ])
    // 화면 이름이 바뀌어도 기준 수는 키에서 온다.
    expect(
      preparingLines([
        badge({ badgeKey: 'correct-25', family: 'CORRECT', name: '첫 답 정답 스물다섯 개' }),
        badge({ badgeKey: 'correct-50', family: 'CORRECT', name: '첫 답 정답 쉰 개' }),
      ]),
    ).toEqual(['준비 중인 기준: 첫 답 정답 25개, 50개. 단계가 더 공개된 뒤 받을 수 있습니다.'])
  })

  it('기준 수가 없는 배지가 섞이면 이름을 쉼표로 잇는다', () => {
    expect(
      preparingLines([
        badge({ badgeKey: 'terms-25', family: 'TERMS', name: '익힌 용어 25개' }),
        badge({ badgeKey: 'terms-all', family: 'TERMS', name: '그때 게시된 용어 전체' }),
      ]),
    ).toEqual([
      '준비 중인 기준: 익힌 용어 25개, 그때 게시된 용어 전체. 단계가 더 공개된 뒤 받을 수 있습니다.',
    ])
  })

  it('메달 리본은 키의 기준 수와 단위이고, 전체가 기준이면 전체다', () => {
    expect(badgeRibbon({ badgeKey: 'read-5', family: 'READ' })).toBe('5편')
    expect(badgeRibbon({ badgeKey: 'level-4', family: 'LEVEL' })).toBe('4')
    expect(badgeRibbon({ badgeKey: 'terms-all', family: 'TERMS' })).toBe('전체')
    expect(badgeRibbon({ badgeKey: 'persistence', family: 'SPECIAL' })).toBe('')
    expect(badgeThreshold('correct-10')).toBe(10)
    expect(badgeThreshold('terms-all')).toBeNull()
  })
})

describe('다음 목표(FR-030, T208)', () => {
  it('다음 배지는 레벨 갈래를 빼고 남은 양이 가장 적은 아직 받지 않은 배지다', () => {
    expect(nextBadge(learnerOverview.badges)?.badgeKey).toBe('perfect-1')
  })

  it('다음 목표 문장은 다음 레벨과 다음 배지를 함께 말한다', () => {
    expect(nextGoalSentence(learnerOverview)).toBe(
      '다음 레벨까지 50, 다음 배지(모두 맞힘 1편)까지 1편입니다.',
    )
  })

  it('진행을 화면 낭독기가 읽을 문장으로 바꾼다', () => {
    expect(progressSpoken({ family: 'READ', progress: { current: 4, target: 5 } })).toBe(
      '5편 중 4편',
    )
    expect(progressSpoken({ family: 'LEVEL', progress: { current: 250, target: 300 } })).toBe(
      '경험치 300 중 250',
    )
  })
})

describe('새 배지 한 줄(FR-030, quickstart 11-3)', () => {
  it('레벨 갈래를 빼고 가장 최근 배지 이름에 나머지 수를 붙인다', () => {
    const earned = (key: string, name: string, family: string, at: string) =>
      badge({
        badgeKey: key,
        family,
        name,
        status: 'EARNED',
        isNew: true,
        earnedAt: at,
        preparingReason: null,
      })
    expect(newBadgeLine(learnerOverview.badges)).toBe('새 배지: 1단계 완주')
    expect(
      newBadgeLine([
        earned('level-3', '레벨 3', 'LEVEL', '2026-11-21T00:00:00Z'),
        earned('read-1', '읽은 글 1편', 'READ', '2026-11-19T00:00:00Z'),
        earned('stage-a', '1단계 완주', 'STAGE', '2026-11-20T00:00:00Z'),
      ]),
    ).toBe('새 배지: 1단계 완주 외 1개')
  })

  it('7일 안의 배지가 없으면 줄이 없다', () => {
    expect(
      newBadgeLine(learnerOverview.badges.map((item) => ({ ...item, isNew: false }))),
    ).toBeNull()
  })
})

describe('닿을 수 없는 다음 레벨과 받은 단계 완주 배지', () => {
  it('다음 레벨이 지금 공개된 단계로 닿을 수 없으면 다음 목표로 말하지 않는다(FR-029)', () => {
    const blocked = { ...learnerOverview.level, current: 3, visibleMax: 3, unreachableFrom: 4 }
    expect(nextGoalSentence({ level: blocked, badges: learnerOverview.badges })).toBe(
      '다음 배지(모두 맞힘 1편)까지 1편입니다.',
    )
    // 다음 목표 옆에는 닿을 수 없을 때만 FR-029 문장을 붙인다.
    expect(unreachableNextLevelSentence(blocked)).toBe(
      '레벨 4부터는 단계가 더 공개된 뒤 닿을 수 있습니다.',
    )
    expect(unreachableNextLevelSentence(learnerOverview.level)).toBeNull()
  })

  it('아직 받지 않은 배지의 진행 비율을 진행 호에 넘긴다', () => {
    expect(badgePercent({ progress: { current: 3, target: 5 } })).toBe(60)
    expect(badgePercent({ progress: null })).toBe(0)
  })

  it('단계 완주 배지를 받은 단계 번호를 모은다', () => {
    expect([...earnedStageNumbers(learnerOverview.badges)]).toEqual([1])
  })
})

describe('배지의 메달', () => {
  it('레벨은 배지 키(level-n)에서 읽고 화면에 보이는 이름에서는 읽지 않는다', () => {
    expect(badgeLevel('level-3')).toBe(3)
    expect(badgeLevel('level-x')).toBeNull()
    expect(medalSpecOf({ badgeKey: 'level-4', family: 'LEVEL', stageNumber: null })).toEqual({
      kind: 'level',
      level: 4,
    })
    // 이름에 숫자가 있어도 키가 카탈로그 모양이 아니면 레벨을 지어내지 않고 특별 갈래 모양이다.
    expect(medalSpecOf({ badgeKey: 'level-next', family: 'LEVEL', stageNumber: null })).toEqual({
      kind: 'special',
    })
  })

  it('단계 완주는 응답의 단계 번호로 그리고, 없으면 번호를 지어내지 않는다', () => {
    expect(
      medalSpecOf({ badgeKey: 'stage-harbor-basics', family: 'STAGE', stageNumber: 1 }),
    ).toEqual({ kind: 'stage', stageNumber: 1 })
    expect(
      medalSpecOf({ badgeKey: 'stage-harbor-basics', family: 'STAGE', stageNumber: null }),
    ).toEqual({ kind: 'special' })
    expect(stageBadgeKey('harbor-basics')).toBe('stage-harbor-basics')
  })

  it('특별 갈래는 끈기·탐험의 글리프를 고르고, 모르는 배지와 모르는 갈래는 글리프 없이 그린다', () => {
    expect(specialBadgeOf('exploration')).toBe('exploration')
    expect(specialBadgeOf('mystery')).toBeNull()
    expect(medalSpecOf({ badgeKey: 'persistence', family: 'SPECIAL', stageNumber: null })).toEqual({
      kind: 'special',
      special: 'persistence',
    })
    expect(medalSpecOf({ badgeKey: 'mystery', family: 'SPECIAL', stageNumber: null })).toEqual({
      kind: 'special',
      special: undefined,
    })
    expect(medalSpecOf({ badgeKey: 'new-1', family: 'NEW_FAMILY', stageNumber: null })).toEqual({
      kind: 'special',
      special: undefined,
    })
    expect(medalSpecOf({ badgeKey: 'read-5', family: 'READ', stageNumber: null })).toEqual({
      kind: 'read',
    })
  })
})

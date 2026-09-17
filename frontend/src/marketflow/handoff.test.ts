import { describe, expect, it } from 'vitest'
import { buildSimilarPeriodHandoff, buildStrategyComparisonHandoff } from './handoff'

describe('비슷했던 때 보기로 넘기는 값 (FR-009)', () => {
  it('분야와 그 분야의 기준월을 넘긴다', () => {
    expect(buildSimilarPeriodHandoff({ fieldId: 'labor', referenceMonth: '2026-07' })).toEqual({
      fieldId: 'labor',
      referenceMonth: '2026-07',
    })
  })

  it('기준월이 없으면 넘길 값이 없다', () => {
    // 기준월 없이 넘기면 010이 "언제와 비슷한지"를 모르는 채 검색하게 된다.
    expect(buildSimilarPeriodHandoff({ fieldId: 'labor', referenceMonth: null })).toBeNull()
  })

  it('분야마다 다른 기준월을 그대로 유지한다', () => {
    const production = buildSimilarPeriodHandoff({ fieldId: 'production', referenceMonth: '2026-08' })
    const labor = buildSimilarPeriodHandoff({ fieldId: 'labor', referenceMonth: '2026-06' })

    expect(production?.referenceMonth).toBe('2026-08')
    expect(labor?.referenceMonth).toBe('2026-06')
  })

  /**
   * FR-016이 "섹터를 골랐어도 넘기는 분야·기준월은 달라지지 않는다"를 요구한다.
   * 이 함수는 섹터를 받는 자리가 아예 없어서 실수로도 실을 수 없다. 그것을 여기서 못 박는다.
   */
  it('섹터를 실을 자리가 없다', () => {
    const handoff = buildSimilarPeriodHandoff({ fieldId: 'labor', referenceMonth: '2026-07' })

    expect(Object.keys(handoff ?? {}).sort()).toEqual(['fieldId', 'referenceMonth'])
  })
})

describe('이 기간으로 전략 비교로 넘기는 값 (FR-018)', () => {
  it('선택 섹터와 사례 식별자를 넘긴다', () => {
    expect(buildStrategyComparisonHandoff({ sectorCode: 'SEMICONDUCTOR', caseId: 'case-17' })).toEqual({
      sectorCode: 'SEMICONDUCTOR',
      caseId: 'case-17',
    })
  })

  it('섹터를 고르지 않았어도 사례만으로 넘어간다', () => {
    // 섹터는 선택 사항이다. 없다고 진입을 막으면 FR-018이 요구한 길이 사라진다.
    expect(buildStrategyComparisonHandoff({ sectorCode: null, caseId: 'case-17' })).toEqual({
      sectorCode: null,
      caseId: 'case-17',
    })
  })

  it('사례가 없으면 넘길 값이 없다', () => {
    // 010이 붙기 전에는 언제나 이 경우다. 섹터만으로 열면 '이 기간'이 빠진 채 넘어간다.
    expect(buildStrategyComparisonHandoff({ sectorCode: 'SEMICONDUCTOR', caseId: null })).toBeNull()
    expect(buildStrategyComparisonHandoff({ sectorCode: null, caseId: null })).toBeNull()
  })
})

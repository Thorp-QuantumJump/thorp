import { describe, expect, it } from 'vitest'
import { CASE_PERIOD_PREFILLED_NOTE, defaultCasePeriod } from './casePeriodConstraint'

const constraint = { startMax: '2012-01-02', endMin: '2012-07-03', note: '' }

describe('사례에서 들어왔을 때 미리 맞출 분석 기간 (S15P21E205-450)', () => {
  it('사례 사후 구간 끝에서 끝나고 최소 평가 기간을 채운다', () => {
    expect(defaultCasePeriod(constraint, { start: '2005-01-03', end: '2026-08-31' }, 3)).toEqual({
      start: '2009-07-03',
      end: '2012-07-03',
    })
  })

  it('최소 평가 기간이 없으면 워밍업이 끝나는 마지막 날부터다', () => {
    expect(defaultCasePeriod(constraint, { start: '2005-01-03', end: '2026-08-31' }, 0)).toEqual({
      start: '2012-01-02',
      end: '2012-07-03',
    })
  })

  it('범위 앞쪽에 막히면 범위 시작부터이고 모자란 평가 기간은 끝을 늘려 채운다', () => {
    expect(defaultCasePeriod(constraint, { start: '2011-01-03', end: '2026-08-31' }, 3)).toEqual({
      start: '2011-01-03',
      end: '2014-01-03',
    })
  })

  it('범위가 사례를 덮지 못하면 맞추지 않는다', () => {
    expect(defaultCasePeriod(constraint, { start: '2012-03-02', end: '2026-08-31' }, 0)).toBeNull()
  })

  it('미리 맞췄다는 문구가 있다', () => {
    expect(CASE_PERIOD_PREFILLED_NOTE).toContain('미리 맞춰')
  })
})

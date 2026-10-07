import { describe, expect, it } from 'vitest'
import { buildCaseId, buildRegimeLinkStartPath } from './handoff'

describe('사례 식별자 조립 (011 계약 §0)', () => {
  it('010 searchId와 관측 종료월을 물결로 잇는다', () => {
    expect(buildCaseId('FIELD:LABOR:2026-07-01:MACRO-1:REG-3:REGIME-V1', '2012-03')).toBe(
      'FIELD:LABOR:2026-07-01:MACRO-1:REG-3:REGIME-V1~2012-03',
    )
  })

  // 서버는 마지막 물결로 나눈다. 키 안에 구분자가 있으면 키가 잘리므로 값을 만들지 않는다.
  it('검색 식별자에 구분자가 있으면 값을 만들지 않는다', () => {
    expect(buildCaseId('FIELD~LABOR', '2012-03')).toBeNull()
  })

  it('종료월 모양이 아니면 값을 만들지 않는다', () => {
    expect(buildCaseId('FIELD:LABOR', '2012-3')).toBeNull()
    expect(buildCaseId('', '2012-03')).toBeNull()
  })
})

describe('011 시작 주소 (진입 계약 §2)', () => {
  it('사례만으로 열 수 있다', () => {
    expect(buildRegimeLinkStartPath({ caseId: 'FIELD:LABOR~2012-03' })).toBe(
      '/regime-link/start?case=FIELD%3ALABOR%7E2012-03',
    )
  })

  it('섹터가 있으면 002 초기 선택으로 함께 싣는다', () => {
    expect(buildRegimeLinkStartPath({ caseId: 'K~2012-03', sectorCode: 'SEMICONDUCTOR' })).toBe(
      '/regime-link/start?case=K%7E2012-03&sector=SEMICONDUCTOR',
    )
  })

  // 010 사례 상세에서 들어오는 길은 섹터를 알 수 없다. 빈 값을 주소에 남기지 않는다.
  it('섹터가 없으면 인자를 만들지 않는다', () => {
    expect(buildRegimeLinkStartPath({ caseId: 'K~2012-03', sectorCode: null })).not.toContain('sector')
  })
})


import { describe, expect, it } from 'vitest'
import type { IndicatorDisplay } from '@/types/marketFlow'
import type { RegimeCaseDetail, RegimeCaseSummary } from '@/types/regime'
import { shortRange } from './pastFormat'
import { bandLabel, bandOf, pastForIndicator, unplacedVerdicts, verdictFor } from './pastOverlay'

function indicator(overrides: Partial<IndicatorDisplay> = {}): IndicatorDisplay {
  return {
    indicatorId: 'all_industry_production', name: '전산업생산', unit: '%', value: 3.1,
    path: [{ month: '2026-05', value: 1.9 }, { month: '2026-06', value: 4.4 }, { month: '2026-07', value: 3.1 }],
    referenceMonth: '2026-07', releaseDate: null, source: 'KOSIS', agency: '통계청', afterReferenceMonthRelease: null,
    ...overrides,
  }
}

const summary: RegimeCaseSummary = {
  endMonth: '2001-03', rank: 1, observationMonths: ['2001-01', '2001-02', '2001-03'], distance: '0.37',
  similar: ['전산업생산은 3.1%로 당시 2.6%와 비슷합니다.'],
  different: ['서비스업생산은 지금이 7.3%로 당시 5.9%보다 높습니다.'],
  detailAvailable: true,
}

function detailWith(compared: Partial<RegimeCaseDetail['indicators'][number]>): RegimeCaseDetail {
  return {
    searchId: 'S-1', endMonth: '2001-03', observationMonths: ['2001-01', '2001-02', '2001-03'], distance: '0.37', byField: {},
    indicators: [{
      id: 'all_industry_production', name: '전산업생산', unit: '%',
      comparisonMonths: ['2026-05', '2026-06', '2026-07'], caseMonths: ['2001-01', '2001-02', '2001-03'],
      queryRaw: ['1.9', '4.4', '3.1'], caseRaw: ['2.539062', '4.968944', '2.568807'], queryZ: [], caseZ: [],
      e: '0.19', nominalWeight: '0.33',
      scale: { median: '2.046626', spread: '2.842431', method: 'MAD', windowStart: '2016-05', windowEnd: '2026-04', months: 120 },
      ...compared,
    }],
    eraPosition: null,
  } as RegimeCaseDetail
}

describe('verdictFor', () => {
  it('문장이 이 지표 이름과 조사로 시작할 때만 그 지표 칸에 붙인다', () => {
    expect(verdictFor('전산업생산', summary)).toEqual({ kind: 'similar', text: summary.similar[0] })
    expect(verdictFor('서비스업생산', summary)).toEqual({ kind: 'different', text: summary.different[0] })
    expect(verdictFor('광공업생산', summary)).toBeNull()
  })

  it('이름이 더 긴 다른 지표의 문장에 걸리지 않는다', () => {
    const longer = { ...summary, similar: ['소비자물가지수는 2.1%로 당시 2.0%와 비슷합니다.'], different: [] }
    expect(verdictFor('소비자물가', longer)).toBeNull()
    expect(verdictFor('소비자물가지수', longer)?.kind).toBe('similar')
  })
})

describe('문장이 두 개인 쪽', () => {
  // 010은 쪽마다 최대 두 문장을 준다. 가장 먼 지표까지 거리가 0이면 비슷한 점이 둘이다.
  const two = { ...summary, similar: ['전산업생산은 3.1%로 당시 3.1%와 비슷합니다.', '서비스업생산은 7.3%로 당시 7.3%와 비슷합니다.'], different: [] }

  it('둘째 문장도 그 지표 칸에 붙고 버려지지 않는다', () => {
    expect(verdictFor('서비스업생산', two)).toEqual({ kind: 'similar', text: two.similar[1] })
    expect(unplacedVerdicts([indicator()], two)).toEqual([{ kind: 'similar', text: two.similar[1] }])
  })
})

describe('unplacedVerdicts', () => {
  it('보이는 지표 어디에도 붙지 못한 문장만 돌려준다', () => {
    expect(unplacedVerdicts([indicator()], summary)).toEqual([{ kind: 'different', text: summary.different[0] }])
    expect(unplacedVerdicts([indicator(), indicator({ indicatorId: 'service_production', name: '서비스업생산' })], summary)).toEqual([])
  })
})

describe('pastForIndicator', () => {
  it('010이 비교한 달끼리 맞대어 당시 값과 달을 그래프 순서대로 둔다', () => {
    const past = pastForIndicator(indicator(), summary, detailWith({}))
    expect(past.path).toEqual({ months: ['2001-01', '2001-02', '2001-03'], values: [2.539062, 4.968944, 2.568807] })
    expect(past.verdict?.kind).toBe('similar')
    expect(past.band?.[0]).toBeCloseTo(2.046626 - 2.842431)
    expect(past.band?.[1]).toBeCloseTo(2.046626 + 2.842431)
  })

  it('비교하지 않은 달은 순서로 채우지 않고 비워 둔다', () => {
    const past = pastForIndicator(indicator(), summary, detailWith({
      comparisonMonths: ['2026-06', '2026-07'], caseMonths: ['2001-02', '2001-03'], caseRaw: ['4.9', '2.6'],
    }))
    expect(past.path).toEqual({ months: [null, '2001-02', '2001-03'], values: [null, 4.9, 2.6] })
  })

  it('비교 달이 한 달 밀리면 맞는 자리만 겹치고, 하나도 맞지 않으면 경로를 만들지 않는다', () => {
    const past = pastForIndicator(indicator(), summary, detailWith({
      comparisonMonths: ['2026-06', '2026-07', '2026-08'], caseMonths: ['2001-01', '2001-02', '2001-03'],
    }))
    // 그래프의 2026-06은 010이 첫째로 비교한 달(당시 2001-01)과 맞댄다. 순서만 보고 2001-02를 놓지 않는다.
    expect(past.path).toEqual({ months: [null, '2001-01', '2001-02'], values: [null, 2.539062, 4.968944] })
    const shifted = pastForIndicator(indicator({ path: [{ month: '2025-01', value: 1 }, { month: '2025-02', value: 1 }, { month: '2025-03', value: 1 }] }), summary, detailWith({}))
    expect(shifted.path).toBeNull()
  })

  it('상세를 받기 전에도 문장은 붙이고, 그 지표를 비교하지 않았으면 경로와 띠가 없다', () => {
    expect(pastForIndicator(indicator(), summary, null)).toEqual({ path: null, verdict: { kind: 'similar', text: summary.similar[0] }, band: null })
    expect(pastForIndicator(indicator({ indicatorId: 'other' }), summary, detailWith({})).path).toBeNull()
  })

  it('숫자가 아닌 값과 척도 없는 지표는 비워 둔다', () => {
    const past = pastForIndicator(indicator(), summary, detailWith({ caseRaw: ['', 'x', '2.6'], scale: null }))
    expect(past.path?.values).toEqual([null, null, 2.6])
    expect(past.band).toBeNull()
  })
})

describe('bandOf', () => {
  it('보통 범위는 사례와 상관없이 그 검색의 공통 척도로 만든다', () => {
    const past = pastForIndicator(indicator(), summary, null, detailWith({}))
    expect(past.path).toBeNull()
    expect(past.band?.[1]).toBeCloseTo(2.046626 + 2.842431)
    expect(bandOf(indicator({ indicatorId: 'other' }), detailWith({}))).toBeNull()
  })
})

describe('bandLabel·shortRange', () => {
  it('지표마다 척도 창이 다르면 기간을 이름에 넣지 않고 지표별로 적는다', () => {
    const mixed = detailWith({})
    mixed.indicators.push({ ...mixed.indicators[0], id: 'service_production', name: '서비스업생산', scale: { median: '3', spread: '1', method: 'MAD', windowStart: '2019-01', windowEnd: '2026-04', months: 88 } })
    expect(bandLabel(mixed)).toEqual({ name: '보통 범위', window: '전산업생산 2016-05~2026-04, 서비스업생산 2019-01~2026-04' })
  })

  it('척도 창이 열두 달 단위면 해로 줄이고, 아니면 달로 적는다', () => {
    expect(bandLabel(detailWith({}))).toEqual({ name: '지난 10년 보통 범위', window: '2016-05~2026-04' })
    expect(bandLabel(detailWith({ scale: { median: '1', spread: '1', method: 'MAD', windowStart: '2019-01', windowEnd: '2026-06', months: 90 } }))?.name).toBe('지난 90개월 보통 범위')
    expect(bandLabel(null)).toBeNull()
  })

  it('범례 기간은 같은 해면 끝 달의 연도를 뺀다', () => {
    expect(shortRange(['2026-05', '2026-06', '2026-07'])).toBe('2026.05 ~ 07')
    expect(shortRange(['2008-11', '2008-12', '2009-01'])).toBe('2008.11 ~ 2009.01')
    expect(shortRange([null, '2001-02', '2001-03'])).toBe('2001.02 ~ 03')
    expect(shortRange([null, null, null])).toBeNull()
  })
})

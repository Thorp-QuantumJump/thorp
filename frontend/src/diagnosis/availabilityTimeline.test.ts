import { describe, expect, it } from 'vitest'
import { buildAvailabilityTimeline, formatSpan } from './availabilityTimeline'
import type { SectorSummary } from '@/types/sector'

function sector(code: string, name: string, listedOn: string): SectorSummary {
  return {
    code,
    name,
    classification: 'INDUSTRY',
    representativeEtf: {
      instrumentId: `${code}.XKRX`,
      name: `KODEX ${name}`,
      listedOn,
      benchmarkIndex: `KRX ${name}`,
    },
  }
}

const 반도체 = sector('SEMICONDUCTOR', '반도체', '2006-06-27')
const 자동차 = sector('AUTO', '자동차', '2011-04-12')
const 조선 = sector('SHIPBUILDING', '조선', '2018-09-12')
const 같은날 = sector('STEEL', '철강', '2006-06-27')

const 오늘 = '2026-09-17'

describe('타임라인을 그릴지 가르는 조건', () => {
  it('고른 대상이 하나여도 그 대상의 상장일부터 오늘까지를 그린다', () => {
    expect(buildAvailabilityTimeline([반도체], 오늘)).toMatchObject({
      status: 'shown',
      axisStart: '2006-06-27',
      commonStart: '2006-06-27',
      commonStartRatio: 0,
      bindingSectorName: '반도체',
    })
  })

  it('상장일이 모두 같아도 공통 구간을 그린다', () => {
    expect(buildAvailabilityTimeline([반도체, 같은날], 오늘)).toMatchObject({
      status: 'shown',
      commonStart: '2006-06-27',
      commonStartRatio: 0,
    })
  })

  it('가장 늦은 상장일이 아직 오지 않았으면 그리지 않는다', () => {
    const 상장전 = sector('FUTURE', '예정', '2027-01-04')
    expect(buildAvailabilityTimeline([반도체, 상장전], 오늘)).toEqual({ status: 'silent' })
  })

  it('읽을 수 없는 상장일이 섞이면 날짜를 지어내지 않고 그리지 않는다', () => {
    const 깨진값 = sector('BROKEN', '깨짐', 'N/A')
    expect(buildAvailabilityTimeline([반도체, 깨진값], 오늘)).toEqual({ status: 'silent' })
  })

  it('아무것도 고르지 않으면 그리지 않는다', () => {
    expect(buildAvailabilityTimeline([], 오늘)).toEqual({ status: 'silent' })
  })

  it('오늘 상장한 한 대상도 0일 구간으로 그린다', () => {
    const 오늘상장 = sector('TODAY', '오늘상장', 오늘)
    expect(buildAvailabilityTimeline([오늘상장], 오늘)).toMatchObject({
      status: 'shown',
      commonStart: 오늘,
      commonStartRatio: 0,
      commonSpanLabel: '0일',
    })
  })
})

describe('축 위의 자리', () => {
  const timeline = buildAvailabilityTimeline([반도체, 자동차, 조선], 오늘)

  it('축은 가장 이른 상장일부터 오늘까지다', () => {
    expect(timeline).toMatchObject({ status: 'shown', axisStart: '2006-06-27', axisEnd: 오늘 })
  })

  it('상세에 쓸 대상 순서는 넘긴 표시 순서를 그대로 지킨다', () => {
    if (timeline.status !== 'shown') throw new Error('그려진 타임라인이어야 한다')
    expect(timeline.tracks.map((track) => track.code)).toEqual([
      'SEMICONDUCTOR',
      'AUTO',
      'SHIPBUILDING',
    ])
  })

  it('상세에는 대상마다 상장일을 담는다', () => {
    if (timeline.status !== 'shown') throw new Error('그려진 타임라인이어야 한다')
    expect(timeline.tracks).toEqual([
      { code: 'SEMICONDUCTOR', name: '반도체', listedOn: '2006-06-27' },
      { code: 'AUTO', name: '자동차', listedOn: '2011-04-12' },
      { code: 'SHIPBUILDING', name: '조선', listedOn: '2018-09-12' },
    ])
  })

  it('공통 구간은 가장 늦은 상장일에서 시작해 축 안의 한 자리로 계산된다', () => {
    if (timeline.status !== 'shown') throw new Error('그려진 타임라인이어야 한다')
    expect(timeline.commonStart).toBe('2018-09-12')
    expect(timeline.bindingSectorName).toBe('조선')
    expect(timeline.commonStartRatio).toBeGreaterThan(0)
    expect(timeline.commonStartRatio).toBeLessThan(1)
  })
})

describe('길이 문구', () => {
  it('해와 달을 모두 적는다', () => {
    expect(formatSpan('2018-09-12', '2026-09-17')).toBe('8년')
    expect(formatSpan('2018-06-12', '2026-09-17')).toBe('8년 3개월')
  })

  it('한 해가 차지 않으면 달만 적는다', () => {
    expect(formatSpan('2026-01-17', '2026-09-17')).toBe('8개월')
  })

  it('한 달이 차지 않으면 0개월이라고 쓰지 않고 일수를 적는다', () => {
    expect(formatSpan('2026-09-01', '2026-09-17')).toBe('16일')
  })

  it('끝날의 일자가 시작날보다 이르면 마지막 달을 세지 않는다', () => {
    expect(formatSpan('2026-01-20', '2026-09-17')).toBe('7개월')
  })
})

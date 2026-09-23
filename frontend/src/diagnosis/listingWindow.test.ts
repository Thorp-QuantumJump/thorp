import { describe, expect, it } from 'vitest'
import { describeListingWindow, localToday, pickSelectedSectors } from './listingWindow'
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

describe('상장일 기준 공통 비교 시작일', () => {
  it('상장일이 모두 같으면 안내할 사실이 없다', () => {
    expect(describeListingWindow([반도체, 같은날], 오늘)).toEqual({ status: 'silent' })
  })

  it('상장일이 다르면 가장 늦은 날짜와 그 날짜를 만든 섹터를 돌려준다', () => {
    expect(describeListingWindow([반도체, 자동차, 조선], 오늘)).toEqual({
      status: 'narrowed',
      commonStartFromListing: '2018-09-12',
      bindingSectorCode: 'SHIPBUILDING',
      bindingSectorName: '조선',
    })
  })

  it('넘기는 순서가 달라도 같은 결과가 나온다', () => {
    const 순서대로 = describeListingWindow([반도체, 자동차, 조선], 오늘)
    const 뒤집어서 = describeListingWindow([조선, 자동차, 반도체], 오늘)
    expect(뒤집어서).toEqual(순서대로)
  })

  it('선택이 하나면 비교할 상대가 없어 안내하지 않는다', () => {
    expect(describeListingWindow([조선], 오늘)).toEqual({ status: 'silent' })
  })

  it('선택이 하나도 없어도 안내하지 않는다', () => {
    expect(describeListingWindow([], 오늘)).toEqual({ status: 'silent' })
  })

  it('가장 늦은 상장일이 오늘 이후면 비교할 기간이 남지 않은 것으로 본다', () => {
    const 미상장 = sector('FUTURE', '미래', '2027-01-04')
    const window = describeListingWindow([반도체, 미상장], 오늘)

    expect(window.status).toBe('unavailable')
    // 기간을 세지 않으므로 음수 일수 같은 값이 만들어질 자리가 없다. 날짜와 원인만 남는다.
    expect(window).toEqual({
      status: 'unavailable',
      commonStartFromListing: '2027-01-04',
      bindingSectorCode: 'FUTURE',
      bindingSectorName: '미래',
    })
  })

  it('가장 늦은 상장일이 바로 오늘이면 아직 비교할 수 있는 상태로 본다', () => {
    const 오늘상장 = sector('TODAY', '오늘', 오늘)
    expect(describeListingWindow([반도체, 오늘상장], 오늘).status).toBe('narrowed')
  })

  it('읽을 수 없는 상장일이 섞이면 날짜를 지어내지 않고 안내를 접는다', () => {
    const 깨진값 = sector('BROKEN', '깨짐', '2018/09/12')
    expect(describeListingWindow([반도체, 깨진값], 오늘)).toEqual({ status: 'silent' })
  })
})

describe('오늘 날짜', () => {
  it('브라우저 기준 날짜를 YYYY-MM-DD로 만든다', () => {
    // 한 자리 월·일에 0을 채우지 않으면 문자열 비교가 무너진다.
    expect(localToday(new Date(2026, 0, 5, 23, 30))).toBe('2026-01-05')
  })
})

describe('선택된 섹터 고르기', () => {
  const 목록 = [반도체, 자동차, 조선]

  it('선택 코드의 순서를 그대로 지킨다', () => {
    expect(pickSelectedSectors(목록, ['SHIPBUILDING', 'SEMICONDUCTOR']).map((s) => s.code)).toEqual(
      ['SHIPBUILDING', 'SEMICONDUCTOR'],
    )
  })

  it('목록에 없는 코드는 결과에 담기지 않는다', () => {
    expect(pickSelectedSectors(목록, ['GONE', 'AUTO']).map((s) => s.code)).toEqual(['AUTO'])
  })
})

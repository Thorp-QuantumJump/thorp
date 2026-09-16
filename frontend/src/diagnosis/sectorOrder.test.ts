import { describe, expect, it } from 'vitest'
import type { SectorClassification, SectorSummary } from '@/types/sector'
import { KRX_SOURCE_ATTRIBUTION } from './attribution'
import { compareSectorsForDisplay, sortSectorsForDisplay } from './sectorOrder'

function sector(
  code: string,
  name: string,
  classification: SectorClassification,
  listedOn = '2020-01-02',
): SectorSummary {
  return {
    code,
    name,
    classification,
    representativeEtf: {
      instrumentId: `${code}.XKRX`,
      name: `KODEX ${name}`,
      listedOn,
      benchmarkIndex: `KRX ${name}`,
    },
  }
}

const semiconductor = sector('SEMICONDUCTOR', '반도체', 'INDUSTRY')
const bank = sector('BANK', '은행', 'INDUSTRY')
const auto = sector('AUTO', '자동차', 'INDUSTRY')
const secondaryBattery = sector('BATTERY', '2차전지', 'THEME')
const ai = sector('AI', '인공지능', 'THEME')

describe('섹터 표시 순서', () => {
  it('업종을 테마보다 먼저 둔다', () => {
    const ordered = sortSectorsForDisplay([ai, semiconductor, secondaryBattery, bank])

    expect(ordered.map((item) => item.classification)).toEqual([
      'INDUSTRY',
      'INDUSTRY',
      'THEME',
      'THEME',
    ])
  })

  it('같은 구분 안에서는 이름을 한국어 순으로 정렬한다', () => {
    const ordered = sortSectorsForDisplay([auto, semiconductor, bank])

    expect(ordered.map((item) => item.name)).toEqual(['반도체', '은행', '자동차'])
  })

  it('서버 응답의 배열 순서에 기대지 않는다', () => {
    const forward = sortSectorsForDisplay([semiconductor, bank, auto, ai, secondaryBattery])
    const reversed = sortSectorsForDisplay([secondaryBattery, ai, auto, bank, semiconductor])

    expect(reversed.map((item) => item.code)).toEqual(forward.map((item) => item.code))
  })

  it('같은 집합이면 어떤 순서로 넣어도 같은 순서가 나온다', () => {
    const input = [semiconductor, bank, auto, ai, secondaryBattery]
    const expected = sortSectorsForDisplay(input).map((item) => item.code)

    // 잔여분이 표시 순서상 첫 번째 섹터에 붙으므로 이 안정성이 M3의 비중 계산을 떠받친다.
    const shuffles = [
      [ai, auto, bank, secondaryBattery, semiconductor],
      [bank, semiconductor, secondaryBattery, auto, ai],
      [secondaryBattery, semiconductor, ai, bank, auto],
    ]

    for (const shuffle of shuffles) {
      expect(sortSectorsForDisplay(shuffle).map((item) => item.code)).toEqual(expected)
    }
  })

  it('이름이 같아도 코드로 순서를 고정한다', () => {
    const first = sector('ALPHA', '같은이름', 'INDUSTRY')
    const second = sector('BETA', '같은이름', 'INDUSTRY')

    expect(compareSectorsForDisplay(first, second)).toBeLessThan(0)
    expect(compareSectorsForDisplay(second, first)).toBeGreaterThan(0)
    expect(compareSectorsForDisplay(first, first)).toBe(0)
  })

  it('한국어 이름 대조가 실행 환경에 따라 달라지지 않는지 고정한다', () => {
    // 로케일 자료를 갖추지 않은 런타임에서 순서가 조용히 달라지는 대신 이 검사가 실패한다.
    const names = ['헬스케어', '반도체', '2차전지', '은행', '자동차', '인터넷', 'IT']
    const ordered = sortSectorsForDisplay(
      names.map((name, index) => sector(`CODE${index}`, name, 'INDUSTRY')),
    )

    // 숫자로 시작하는 이름이 앞, 한글이 그다음, 로마자가 뒤다. `ko` 대조의 성질이며
    // 이 기대값이 깨지면 실행 환경의 대조가 달라졌다는 뜻이다.
    expect(ordered.map((item) => item.name)).toEqual([
      '2차전지',
      '반도체',
      '은행',
      '인터넷',
      '자동차',
      '헬스케어',
      'IT',
    ])
  })

  it('받은 배열을 제자리에서 바꾸지 않는다', () => {
    const input = [secondaryBattery, semiconductor]
    sortSectorsForDisplay(input)

    expect(input.map((item) => item.code)).toEqual(['BATTERY', 'SEMICONDUCTOR'])
  })
})

describe('출처 문구', () => {
  it('001이 내보내는 문구와 같은 값을 한 곳에 둔다', () => {
    expect(KRX_SOURCE_ATTRIBUTION).toBe('한국거래소 통계정보')
  })
})

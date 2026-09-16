import { describe, expect, it } from 'vitest'
import { sortSectorsForDisplay } from './sectorOrder'
import {
  TOTAL_WEIGHT_TENTHS,
  distributeEven,
  formatWeightTenths,
  isValidWeightTenths,
  parseWeightInput,
  parseWeightSliderValue,
  summarizeWeights,
} from './weights'
import type { SectorSummary } from '@/types/sector'

function codes(count: number): string[] {
  return Array.from({ length: count }, (_, index) => `S${index}`)
}

function sector(
  code: string,
  name: string,
  classification: SectorSummary['classification'],
): SectorSummary {
  return {
    code,
    name,
    classification,
    representativeEtf: {
      instrumentId: `${code}.XKRX`,
      name: `KODEX ${name}`,
      listedOn: '2018-09-12',
      benchmarkIndex: `KRX ${name}`,
    },
  }
}

describe('distributeEven', () => {
  it('선택 수 2~20 전부에서 합계가 정확히 1000이다', () => {
    for (let count = 2; count <= 20; count += 1) {
      const weights = distributeEven(codes(count))
      const total = Object.values(weights).reduce((sum, value) => sum + value, 0)

      // 허용 오차가 없는 정수 비교다. 한 자리라도 어긋나면 FR-005가 요구한 "정확히 100%"가 깨진다.
      expect(total, `선택 수 ${count}`).toBe(TOTAL_WEIGHT_TENTHS)
      expect(Object.keys(weights)).toHaveLength(count)
    }
  })

  it('나눠떨어지지 않는 몫을 표시 순서상 첫 번째 섹터에 몰아준다', () => {
    // 3개: q = 333, r = 1 → 334 / 333 / 333. data-model.md의 예시 그대로다.
    expect(distributeEven(['A', 'B', 'C'])).toEqual({ A: 334, B: 333, C: 333 })

    // 6개: q = 166, r = 4 → 170 / 166 × 5.
    // 7개: q = 142, r = 6 → 148 / 142 × 6.
    //
    // 규칙의 원본은 data-model.md의 의사코드(`codes[0] -> q + r`)와 "합계를 정확히 1000으로
    // 맞춘다"는 불변식이다. 두 값 모두 그 둘을 함께 만족하는 유일한 답이다.
    expect(distributeEven(codes(6))).toEqual({ S0: 170, S1: 166, S2: 166, S3: 166, S4: 166, S5: 166 })
    expect(distributeEven(codes(7))).toEqual({
      S0: 148,
      S1: 142,
      S2: 142,
      S3: 142,
      S4: 142,
      S5: 142,
      S6: 142,
    })
  })

  it('나눠떨어지면 모두 같은 값이다', () => {
    expect(distributeEven(['A', 'B'])).toEqual({ A: 500, B: 500 })
    expect(distributeEven(codes(20))).toEqual(
      Object.fromEntries(codes(20).map((code) => [code, 50])),
    )
  })

  it('같은 섹터 집합이면 고른 순서가 달라도 결과가 같다', () => {
    // 잔여분이 남는 3개로 확인한다. 나눠떨어지는 집합은 순서가 달라도 값이 같아 검사가 되지 않는다.
    const battery = sector('BATTERY', '2차전지', 'THEME')
    const auto = sector('AUTO', '자동차', 'INDUSTRY')
    const semiconductor = sector('SEMICONDUCTOR', '반도체', 'INDUSTRY')

    // 잔여분이 붙는 자리는 화면의 표시 순서가 정한다. 사용자가 어떤 순서로 체크했든 같은 집합이면
    // 같은 결과가 나와야 한다(research.md 결정 2).
    const orders = [
      [battery, auto, semiconductor],
      [semiconductor, battery, auto],
      [auto, semiconductor, battery],
    ]

    for (const order of orders) {
      const weights = distributeEven(sortSectorsForDisplay(order).map((item) => item.code))
      // 업종이 먼저, 그 안에서 한국어 이름 순이므로 표시 순서는 언제나 반도체 → 자동차 → 2차전지다.
      // 잔여분 1은 그 첫 번째인 반도체에 붙는다.
      expect(weights).toEqual({ SEMICONDUCTOR: 334, AUTO: 333, BATTERY: 333 })
    }
  })

  it('고른 섹터가 없으면 빈 결과다', () => {
    expect(distributeEven([])).toEqual({})
  })

  it('하나만 고르면 그 섹터가 100%다', () => {
    expect(distributeEven(['A'])).toEqual({ A: 1000 })
  })
})

describe('parseWeightInput — data-model.md 「사용자 입력 파싱」 표', () => {
  it('33.4는 334로 받는다', () => {
    expect(parseWeightInput('33.4')).toEqual({ status: 'parsed', weightTenths: 334 })
  })

  it('33은 330으로 받는다', () => {
    expect(parseWeightInput('33')).toEqual({ status: 'parsed', weightTenths: 330 })
  })

  it('0은 받는다. 거부하지 않는다', () => {
    expect(parseWeightInput('0')).toEqual({ status: 'parsed', weightTenths: 0 })
    expect(parseWeightInput('0.0')).toEqual({ status: 'parsed', weightTenths: 0 })
  })

  it('100은 받고 100을 넘으면 받지 않는다', () => {
    expect(parseWeightInput('100')).toEqual({ status: 'parsed', weightTenths: 1000 })

    const result = parseWeightInput('120')
    expect(result.status).toBe('rejected')
    if (result.status !== 'rejected') throw new Error('거부되어야 한다')
    expect(result.reason).toBe('aboveMaximum')
    // 상한으로 잘라 넣지 않는다. 값이 아니라 거부가 돌아온다.
    expect(result).not.toHaveProperty('weightTenths')
    expect(result.message.length).toBeGreaterThan(0)
  })

  it('33.45는 받지 않는다. 반올림하지 않는다', () => {
    const result = parseWeightInput('33.45')
    expect(result.status).toBe('rejected')
    if (result.status !== 'rejected') throw new Error('거부되어야 한다')
    expect(result.reason).toBe('tooPrecise')
    // 334도 335도 아니다. 값 자체가 돌아오지 않는다.
    expect(result).not.toHaveProperty('weightTenths')
  })

  it('끝에 붙은 0은 값을 바꾸지 않으므로 그대로 받는다', () => {
    expect(parseWeightInput('33.40')).toEqual({ status: 'parsed', weightTenths: 334 })
  })

  it('음수는 받지 않는다. 부호를 바꾸어 넣지 않는다', () => {
    const result = parseWeightInput('-5')
    expect(result.status).toBe('rejected')
    if (result.status !== 'rejected') throw new Error('거부되어야 한다')
    expect(result.reason).toBe('negative')
  })

  it('숫자가 아닌 값은 받지 않는다', () => {
    for (const raw of ['abc', '33%', '1e3', '3 3', '--3', '.']) {
      const result = parseWeightInput(raw)
      expect(result.status, raw).toBe('rejected')
      if (result.status !== 'rejected') throw new Error('거부되어야 한다')
      expect(result.reason, raw).toBe('notANumber')
    }
  })

  it('입력 칸이 문자열을 넘기지 못한 경우도 빈 칸이 아니라 거부로 다룬다', () => {
    // `<input type="number">`는 읽을 수 없는 입력에 빈 문자열을 돌려주고 `validity.badInput`만
    // 올린다. 그 경우를 빈 칸과 같게 다루면 사용자가 글자를 넣었는데 아무 말도 없이 넘어간다.
    const result = parseWeightInput('', { hasUnparsableInput: true })
    expect(result.status).toBe('rejected')
    if (result.status !== 'rejected') throw new Error('거부되어야 한다')
    expect(result.reason).toBe('notANumber')
  })

  it('빈 값은 0으로 보지 않고 아직 입력 중으로 다룬다', () => {
    expect(parseWeightInput('')).toEqual({ status: 'pending' })
    expect(parseWeightInput('   ')).toEqual({ status: 'pending' })
  })
})

describe('parseWeightSliderValue', () => {
  it('슬라이더가 돌려주는 정수를 그대로 받는다', () => {
    expect(parseWeightSliderValue('334')).toEqual({ status: 'parsed', weightTenths: 334 })
    expect(parseWeightSliderValue('0')).toEqual({ status: 'parsed', weightTenths: 0 })
    expect(parseWeightSliderValue('1000')).toEqual({ status: 'parsed', weightTenths: 1000 })
  })

  it('범위 밖이거나 정수가 아닌 값은 받지 않는다', () => {
    for (const raw of ['-1', '1001', '33.4', 'abc']) {
      expect(parseWeightSliderValue(raw).status, raw).toBe('rejected')
    }
  })

  it('숫자 칸과 같은 문장을 쓴다', () => {
    const fromSlider = parseWeightSliderValue('1001')
    const fromField = parseWeightInput('120')

    if (fromSlider.status !== 'rejected' || fromField.status !== 'rejected') {
      throw new Error('둘 다 거부되어야 한다')
    }
    // 판정이 두 곳에 있으면 한쪽만 고쳐져 화면과 규칙이 갈라진다.
    expect(fromSlider.message).toBe(fromField.message)
  })
})

describe('formatWeightTenths', () => {
  it('표시할 때만 10으로 나눈다', () => {
    expect(formatWeightTenths(334)).toBe('33.4')
    expect(formatWeightTenths(1000)).toBe('100.0')
    expect(formatWeightTenths(0)).toBe('0.0')
    expect(formatWeightTenths(5)).toBe('0.5')
  })

  it('초과된 남은 비중처럼 음수도 표시할 수 있다', () => {
    expect(formatWeightTenths(-100)).toBe('-10.0')
  })
})

describe('isValidWeightTenths', () => {
  it('0 이상 1000 이하 정수만 받는다', () => {
    expect(isValidWeightTenths(0)).toBe(true)
    expect(isValidWeightTenths(1000)).toBe(true)
    expect(isValidWeightTenths(-1)).toBe(false)
    expect(isValidWeightTenths(1001)).toBe(false)
    expect(isValidWeightTenths(33.4)).toBe(false)
    expect(isValidWeightTenths('334')).toBe(false)
    expect(isValidWeightTenths(Number.NaN)).toBe(false)
  })
})

describe('summarizeWeights', () => {
  it('합계 판정은 정수 비교 하나다', () => {
    const summary = summarizeWeights(['A', 'B', 'C'], { A: 334, B: 333, C: 333 })

    expect(summary.totalTenths).toBe(1000)
    expect(summary.isBalanced).toBe(true)
    expect(summary.remainderTenths).toBe(0)
    expect(summary.canProceed).toBe(true)
    expect(summary.hasZeroWeight).toBe(false)
  })

  it('부족과 초과를 남은 비중의 부호로 구분한다', () => {
    expect(summarizeWeights(['A', 'B'], { A: 500, B: 400 }).remainderTenths).toBe(100)
    expect(summarizeWeights(['A', 'B'], { A: 600, B: 500 }).remainderTenths).toBe(-100)
  })

  it('0%인 섹터가 있으면 알린다. 합계에서는 그대로 0으로 센다', () => {
    const summary = summarizeWeights(['A', 'B', 'C'], { A: 500, B: 500, C: 0 })

    expect(summary.hasZeroWeight).toBe(true)
    expect(summary.isBalanced).toBe(true)
    expect(summary.canProceed).toBe(true)
  })

  it('하나만 골랐으면 합계가 맞아도 다음 단계로 가지 않는다', () => {
    expect(summarizeWeights(['A'], { A: 1000 }).canProceed).toBe(false)
  })

  it('선택에서 빠진 코드의 값은 합계에 넣지 않는다', () => {
    expect(summarizeWeights(['A'], { A: 500, B: 500 }).totalTenths).toBe(500)
  })
})

import { describe, expect, it } from 'vitest'
import {
  PARTIAL_PREFIX_TEMPLATES,
  SENTENCE_TEMPLATES,
  type SentenceSubject,
  selectSummarySentence,
  selectTemplateKey,
} from './summarySentences'

/** 005 T035. SC-004, quickstart 4. */

const forbiddenWords = ['추천', '최적', '승자', '우승', '유리', '좋다', '할 것', '유사', '확률']

const base: SentenceSubject = {
  name: '물결 고르기',
  netTotalReturn: '0.166652',
  maxDrawdown: '-0.041757',
  totalTradingCost: '17078.49',
}

function target(overrides: Partial<SentenceSubject>): SentenceSubject {
  return { ...base, name: '썰물 나누기', ...overrides }
}

describe('해석 요약 문장 틀', () => {
  it('틀 전체 문자열에 금지 어휘가 하나도 없다', () => {
    const allTemplates = [
      ...Object.values(SENTENCE_TEMPLATES),
      ...Object.values(PARTIAL_PREFIX_TEMPLATES),
    ].join('\n')

    for (const word of forbiddenWords) {
      expect(allTemplates, word).not.toContain(word)
    }
  })

  it('비교 대상이 없으면 세 숫자만 말하는 틀을 고른다', () => {
    expect(selectTemplateKey({ base })).toBe('single')
    expect(selectSummarySentence({ base })).toBe(
      '비용을 뺀 뒤 전체 수익률은 16.67%, 운용 중 고점 대비 가장 크게 줄었던 폭은 -4.18%, 거래비용은 17,078원입니다.',
    )
  })

  it.each([
    [{ netTotalReturn: '0.20', maxDrawdown: '-0.08' }, 'higherLarger'],
    [{ netTotalReturn: '0.20', maxDrawdown: '-0.02' }, 'higherSmaller'],
    [{ netTotalReturn: '0.20' }, 'higherSame'],
    [{ netTotalReturn: '0.10', maxDrawdown: '-0.08' }, 'lowerLarger'],
    [{ netTotalReturn: '0.10', maxDrawdown: '-0.02' }, 'lowerSmaller'],
    [{ netTotalReturn: '0.10' }, 'lowerSame'],
    [{ maxDrawdown: '-0.08' }, 'sameLarger'],
    [{ maxDrawdown: '-0.02' }, 'sameSmaller'],
    [{}, 'identical'],
    [{ totalTradingCost: '20000.00' }, 'costOnlyMore'],
    [{ totalTradingCost: '10000.00' }, 'costOnlyLess'],
  ] as const)('차이 방향 %o 에서 %s 틀을 고른다', (overrides, expected) => {
    expect(selectTemplateKey({ base, target: target(overrides) })).toBe(expected)
  })

  it('보이는 자릿수에서 같은 값은 같다고 말한다', () => {
    // 16.6652%와 16.6653%는 모두 16.67%로 보인다. 한쪽이 높다고 쓰면 숫자와 문장이 다른 말을 한다.
    expect(selectTemplateKey({ base, target: target({ netTotalReturn: '0.166653' }) })).toBe(
      'identical',
    )
  })

  it('수로 읽히지 않는 값이 있으면 비교하지 않고 같다고도 쓰지 않는다', () => {
    const unreadable = target({ netTotalReturn: 'N/A' })

    expect(selectTemplateKey({ base, target: unreadable })).toBe('single')
    expect(selectTemplateKey({ base: { ...base, maxDrawdown: '' }, target: target({}) })).toBe(
      'single',
    )
  })

  it('모든 틀이 값 자리를 남기지 않고 채워진다', () => {
    const cases = [
      { base },
      { base, target: target({ netTotalReturn: '0.20', maxDrawdown: '-0.08' }) },
      { base, target: target({ totalTradingCost: '20000.00' }) },
      { base, target: target({}) },
      { base, partial: { completedThrough: '2024-03-29' } },
      { base, partial: { completedThrough: null } },
    ]

    for (const input of cases) {
      const sentence = selectSummarySentence(input)
      expect(sentence).not.toMatch(/undefined|NaN|\{|\}/)
    }
  })

  it('틀에 적힌 값 자리가 모두 채울 수 있는 이름이다', () => {
    const known = new Set([
      'baseName',
      'baseReturn',
      'baseDrawdown',
      'baseCost',
      'targetName',
      'targetReturn',
      'targetDrawdown',
      'targetCost',
      'completedThrough',
    ])
    const placeholders = [
      ...Object.values(SENTENCE_TEMPLATES),
      ...Object.values(PARTIAL_PREFIX_TEMPLATES),
    ].flatMap((template) => [...template.matchAll(/\{(\w+)\}/g)].map((match) => match[1]))

    expect(placeholders.filter((name) => !known.has(name))).toEqual([])
  })

  it('부분 결과면 완료 범위까지의 값이라고 먼저 밝힌다', () => {
    expect(selectSummarySentence({ base, partial: { completedThrough: '2024-03-29' } })).toMatch(
      /^2024-03-29까지 계산한 값입니다\. 비용을 뺀 뒤/,
    )
    expect(selectSummarySentence({ base, partial: { completedThrough: null } })).toMatch(
      /^완료 범위까지 계산한 값입니다\. /,
    )
  })

  it('비교 문장은 두 값을 나란히 적고 차이를 만들어 내지 않는다', () => {
    const sentence = selectSummarySentence({
      base,
      target: target({ netTotalReturn: '0.20', maxDrawdown: '-0.08' }),
    })

    expect(sentence).toBe(
      '같은 조건에서 비교 대상(썰물 나누기)은 기준(물결 고르기)보다 수익률이 높고(20.00%, 기준 16.67%) 가장 크게 줄었던 폭도 컸습니다(-8.00%, 기준 -4.18%).',
    )
    expect(sentence).not.toContain('%p')
  })
})

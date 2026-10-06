import { describe, expect, it } from 'vitest'
import type { CurrentArticleBlock } from '@/types/learning'
import { currentArticle } from '@/test/learningFixtures'
import { firstOccurrences, runKey, termBlockKey } from './firstOccurrences'

/** 013 T134. 처음 나오는 자리를 문서 순서로 한 번만 정하는지 본다(data-model §5-4, R18, SC-013). */
describe('firstOccurrences', () => {
  it('다른 이름은 용어마다 처음 나오는 자리 한 곳에만 붙는다', () => {
    const { aliasAt } = firstOccurrences(currentArticle.blocks)

    // swell-height: 핵심 요약(블록 0)이 용어 블록(3)보다 먼저다.
    expect(aliasAt.has(runKey(0, 'lines.0', 0))).toBe(true)
    expect(aliasAt.has(termBlockKey(3))).toBe(false)
    // ebb-gap: 문단(2)이 가상의 계산 예(4)보다 먼저다.
    expect(aliasAt.has(runKey(2, 'content', 1))).toBe(true)
    expect(aliasAt.has(runKey(4, 'content', 1))).toBe(false)
    // moor-knot은 다른 이름이 없으므로 어디에도 붙지 않는다.
    expect(aliasAt.has(runKey(2, 'content', 3))).toBe(false)
    expect(aliasAt.size).toBe(2)
  })

  it('도움말 표시는 다른 글이 소유한 용어의 처음 언급 한 곳에만 둔다', () => {
    const { helpAt } = firstOccurrences(currentArticle.blocks)

    expect([...helpAt].sort()).toEqual(
      [runKey(2, 'content', 1), runKey(2, 'content', 3)].sort(),
    )
  })

  it('이 글이 소유한 용어에는 도움말 표시가 없다', () => {
    const blocks: CurrentArticleBlock[] = [
      {
        type: 'paragraph',
        content: [{ term: 'own-term', text: '내 용어', owned: true, aliases: ['mine'] }],
      },
    ]

    const { aliasAt, helpAt } = firstOccurrences(blocks)

    expect(helpAt.size).toBe(0)
    expect(aliasAt.has(runKey(0, 'content', 0))).toBe(true)
  })

  it('용어 블록이 언급보다 먼저면 다른 이름은 용어 블록에 붙는다', () => {
    const blocks: CurrentArticleBlock[] = [
      {
        type: 'term',
        termId: 'own-term',
        label: '내 용어',
        aliases: ['mine'],
        meaning: '뜻',
        reading: '읽는 법',
        caveat: '한계',
      },
      {
        type: 'paragraph',
        content: [{ term: 'own-term', text: '내 용어', owned: true, aliases: ['mine'] }],
      },
    ]

    const { aliasAt } = firstOccurrences(blocks)

    expect([...aliasAt]).toEqual([termBlockKey(0)])
  })

  it('오해 상자는 틀린 생각 칸을 바로잡기 칸보다 먼저 본다', () => {
    const mention = { term: 'other', text: '남의 용어', owned: false, aliases: ['alt'] }
    const blocks: CurrentArticleBlock[] = [
      { type: 'misconception', wrong: [mention], correction: [mention] },
    ]

    const { aliasAt, helpAt } = firstOccurrences(blocks)

    expect([...aliasAt]).toEqual([runKey(0, 'wrong', 0)])
    expect([...helpAt]).toEqual([runKey(0, 'wrong', 0)])
  })
})

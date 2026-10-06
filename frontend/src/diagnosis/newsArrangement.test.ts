import { describe, expect, it } from 'vitest'
import type { NewsDigest } from '@/types/marketNews'
import { arrangeNews } from './newsArrangement'

function digest(id: string): NewsDigest {
  return {
    digestId: id, representativeTitle: `제목 ${id}`, factualSummary: '요약', firstPublishedAt: null,
    publicationTimeStatus: 'UNKNOWN', publisherCount: 1, sectors: [], topics: [], importanceRuleVersion: 'v1', articles: [],
  }
}

const ids = (list: readonly NewsDigest[] | null | undefined) => (list ?? []).map((item) => item.digestId)

describe('arrangeNews (012 FR-001)', () => {
  it('주요 뉴스는 1위 하나와 그다음 넷이다', () => {
    const arranged = arrangeNews({ lead: ['a', 'b', 'c', 'd', 'e', 'f'].map(digest), selected: [], groups: [] })

    expect(arranged.lead?.first?.digestId).toBe('a')
    expect(ids(arranged.lead?.rest)).toEqual(['b', 'c', 'd', 'e'])
  })

  it('한 묶음은 먼저 나오는 부분에만 보이고, 건너뛴 자리는 같은 부분의 다음 순위로 채운다', () => {
    const arranged = arrangeNews({
      lead: ['a', 'b'].map(digest),
      selected: [{ code: 'SEMICONDUCTOR', digests: ['a', 'x', 'y', 'z'].map(digest) }],
      groups: [{ id: 'tech', digests: ['x', 'b', 'p', 'q', 'r'].map(digest) }],
    })

    // a는 주요 뉴스에 있으므로 고른 섹터에서 빠지고 그다음 x·y가 온다.
    expect(ids(arranged.selected?.[0].digests)).toEqual(['x', 'y'])
    // x는 고른 섹터에, b는 주요 뉴스에 있으므로 분류에서는 p·q·r이 온다.
    expect(ids(arranged.groups?.[0].digests)).toEqual(['p', 'q', 'r'])
  })

  it('모자라면 채우지 않고, 받은 것이 모두 앞에 보였는지 받은 것이 없는지를 가른다', () => {
    const arranged = arrangeNews({
      lead: ['a'].map(digest),
      selected: [],
      groups: [{ id: 'market', digests: ['a'].map(digest) }, { id: 'finance', digests: [] }],
    })

    expect(arranged.lead?.rest).toEqual([])
    expect(arranged.groups?.[0]).toEqual({ id: 'market', digests: [], shownAbove: true })
    expect(arranged.groups?.[1]).toEqual({ id: 'finance', digests: [], shownAbove: false })
  })

  it('처음 그릴 때는 앞 부분이 오기 전에 뒤 부분을 정하지 않는다', () => {
    expect(arrangeNews({ lead: 'pending', selected: [], groups: [] })).toEqual({ lead: null, selected: null, groups: null })

    const waitingSelected = arrangeNews({
      lead: [digest('a')],
      selected: [{ code: 'BANK', digests: 'pending' }],
      groups: [{ id: 'finance', digests: [digest('b')] }],
    })
    expect(waitingSelected.selected).toBeNull()
    expect(waitingSelected.groups).toBeNull()

    expect(arrangeNews({ lead: [digest('a')], selected: [], groups: 'pending' }).groups).toBeNull()
  })

  it('한 번 다 그린 뒤에는 받는 중인 칸만 비워 두고 나머지를 정한다', () => {
    const arranged = arrangeNews(
      {
        lead: [digest('a')],
        selected: [{ code: 'SEMICONDUCTOR', digests: [digest('s')] }, { code: 'BANK', digests: 'pending' }],
        groups: [{ id: 'finance', digests: ['b', 'c'].map(digest) }, { id: 'tech', digests: 'pending' }],
      },
      { holdUntilComplete: false },
    )

    expect(ids(arranged.selected?.[0].digests)).toEqual(['s'])
    expect(arranged.selected?.[1]).toEqual({ code: 'BANK', digests: null, shownAbove: false })
    // 받는 중인 칸은 아무 묶음도 가져가지 않으므로 분류 칸은 그대로 채워진다.
    expect(ids(arranged.groups?.[0].digests)).toEqual(['b', 'c'])
    expect(arranged.groups?.[1].digests).toBeNull()
    // 주요 뉴스가 오기 전에는 다 그린 뒤라도 정하지 않는다.
    expect(arrangeNews({ lead: 'pending', selected: [], groups: [] }, { holdUntilComplete: false }).lead).toBeNull()
  })
})

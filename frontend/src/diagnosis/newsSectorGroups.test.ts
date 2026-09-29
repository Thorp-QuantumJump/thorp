import { describe, expect, it } from 'vitest'
import type { NewsSector } from '@/types/marketNews'
import { groupNewsSectors } from './newsSectorGroups'

const sector = (sectorId: string, name: string, investable = false): NewsSector => ({ sectorId, name, investable })

describe('groupNewsSectors', () => {
  it('섹터를 분류별로 나누고 분류 안에서는 받은 순서를 지킨다', () => {
    const groups = groupNewsSectors([
      sector('NEWS_IT', 'IT·AI'),
      sector('BANK', '은행', true),
      sector('SEMICONDUCTOR', '반도체', true),
    ])

    expect(groups.map((group) => group.name)).toEqual(['기술', '금융'])
    expect(groups[0].sectors.map((item) => item.name)).toEqual(['IT·AI', '반도체'])
  })

  it('배정표에 없는 새 섹터는 기타에 넣어 칩이 사라지지 않게 한다', () => {
    const groups = groupNewsSectors([sector('NEWS_NEW_THING', '새 섹터')])

    expect(groups).toEqual([{ id: 'other', name: '기타', sectors: [sector('NEWS_NEW_THING', '새 섹터')] }])
  })

  it('섹터가 없으면 분류도 없다', () => {
    expect(groupNewsSectors([])).toEqual([])
  })
})

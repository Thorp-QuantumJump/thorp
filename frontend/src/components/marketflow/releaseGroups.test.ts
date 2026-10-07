import { describe, expect, it } from 'vitest'
import { groupReleases } from './releaseGroups'

describe('groupReleases (종합 비교 바닥 각주)', () => {
  it('같은 대상월·발표일의 지표를 한 줄로 모으고 늦은 달이 먼저 온다', () => {
    const groups = groupReleases([
      { indicator: '수출', month: '2026-07', publishedAt: '2026-09-22' },
      { indicator: '소비자물가', month: '2026-08', publishedAt: '2026-09-22' },
      { indicator: '수입', month: '2026-07', publishedAt: '2026-09-22' },
      { indicator: '실업률', month: '2026-08', publishedAt: '2026-09-10' },
    ])

    expect(groups).toEqual([
      { month: '2026-08', publishedAt: '2026-09-22', names: ['소비자물가'] },
      { month: '2026-08', publishedAt: '2026-09-10', names: ['실업률'] },
      { month: '2026-07', publishedAt: '2026-09-22', names: ['수출', '수입'] },
    ])
  })

  it('이름이 없는 지표는 묶지 않고, 날짜가 없는 지표는 빈 날짜로 묶는다', () => {
    const groups = groupReleases([
      { indicator: null, month: '2026-07', publishedAt: '2026-09-22' },
      { indicator: '두바이유', month: null, publishedAt: null },
    ])

    expect(groups).toEqual([{ month: null, publishedAt: null, names: ['두바이유'] }])
  })
})

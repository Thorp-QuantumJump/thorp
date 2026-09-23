import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { regimeResult } from '@/test/regimeFixtures'
import { DataAsOf } from './DataAsOf'

describe('자료 기준 시점', () => {
  it('지표별 대상월과 발표일을 보이고 수집 시각이나 유지율은 보이지 않는다', () => {
    const data = regimeResult().dataAsOf
    render(<DataAsOf data={data} context="시험 검색" />)
    expect(screen.getByText(/대상월 2026-07 · 발표일 2026-09-12/)).toBeTruthy()
    expect(screen.queryByText(/수집 시각|retention|유지율|겹침 비율/)).toBeNull()
    expect(screen.queryByText(/개정 ·/)).toBeNull()
  })

  it('개정 정보가 실제로 있을 때만 한 줄을 더한다', () => {
    const data = { ...regimeResult().dataAsOf, revisions: [{ indicatorId: 'indicator-1', indicator: '시험 지표', month: '2026-06', revisedAt: '2026-09-10' }] }
    render(<DataAsOf data={data} context="시험 검색" />)
    expect(screen.getByText('개정 · 시험 지표 2026-06 · 2026-09-10')).toBeTruthy()
  })
})

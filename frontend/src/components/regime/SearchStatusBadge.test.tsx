import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { regimeResult } from '@/test/regimeFixtures'
import { SearchStatusBadge } from './SearchStatusBadge'

describe('검색 상태 표시', () => {
  it.each([
    ['NOT_COMPUTABLE', '비교 기준을 만들 만큼 과거 자료가 이어지지 않아 계산할 수 없습니다.'],
    ['NEAREST_ONLY', '가까운 순서만 제공합니다. 유사성은 아직 판정하지 않습니다.'],
    ['THRESHOLD_APPLIED', '교정된 기준의 판정 결과입니다.'],
  ] as const)('%s 상태의 서버 문구를 그대로 보인다', (status, statusNote) => {
    const { container } = render(
      <SearchStatusBadge result={regimeResult({ status, statusNote })} context="시험 분야 2026-07 검색 결과" />,
    )
    expect(screen.getByText(statusNote)).toBeTruthy()
    expect(screen.getByRole('status', { name: '시험 분야 2026-07 검색 결과: 검색 상태' })).toBeTruthy()
    expect(container.textContent).not.toMatch(/% 유사|충분히 유사|대표 유사국면/)
  })
})

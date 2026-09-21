import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { BundleChangeNotice } from './BundleChangeNotice'

describe('비교 묶음 변경 알림', () => {
  it('한 문장 알림과 제외·추가된 사례, 제외 수를 보인다', () => {
    render(
      <BundleChangeNotice change={{ added: ['2009-02'], removed: ['2005-11'], excludedCount: 3 }} />,
    )
    expect(screen.getByText(/비교 묶음이 달라져/)).toBeTruthy()
    expect(screen.getByText('2005-11')).toBeTruthy()
    expect(screen.getByText('2009-02')).toBeTruthy()
    expect(screen.getByText('제외된 사례 3개')).toBeTruthy()
  })

  it('유지율과 비율 표기가 0건이다', () => {
    const { container } = render(
      <BundleChangeNotice change={{ added: [], removed: ['2005-11'], excludedCount: 1 }} />,
    )
    expect(container.textContent).not.toMatch(/유지율/)
    expect(container.textContent).not.toMatch(/%/)
  })
})

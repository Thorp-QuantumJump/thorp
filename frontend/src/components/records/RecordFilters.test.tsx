import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { RecordFilters } from './RecordFilters'

function detailsOf(summary: HTMLElement): HTMLDetailsElement {
  return summary.closest('details') as HTMLDetailsElement
}

describe('RecordFilters', () => {
  it('기본은 분석 기간·정렬만 보이고 종류·상태·섹터·전략은 접혀 있다. 펼치면 나온다', async () => {
    const user = userEvent.setup()
    render(<RecordFilters query={{}} onChange={vi.fn()} catalog={null} strategies={null} />)

    expect(screen.getByRole('group', { name: '분석 기간' })).toBeTruthy()
    expect(screen.getByRole('combobox', { name: '정렬' })).toBeTruthy()

    const summary = screen.getByText('상세 필터')
    const details = detailsOf(summary)
    expect(details.open).toBe(false)
    expect(screen.queryByText(/개 적용/)).toBeNull()
    // 기본 보기가 완료한 항목만임은 상태 제목 옆에 있고, 상태는 종류별 두 줄이다.
    const statusGroup = screen.getByRole('group', { name: /^상태/ })
    expect(statusGroup.textContent).toContain('완료한 항목만 보입니다')
    expect(statusGroup.textContent).toContain('분석·탐색 묶음')
    expect(statusGroup.textContent).toContain('경제환경')
    expect(screen.getByRole('checkbox', { name: '기준 충족 판정' })).toBeTruthy()
    // 종류·상태 체크박스는 접힌 details 안에 있다(jsdom 은 닫힌 details 의 내용을 숨기지 않으므로 소속으로 확인한다).
    expect(details.contains(screen.getByRole('checkbox', { name: '실패' }))).toBe(true)
    expect(details.contains(screen.getByRole('checkbox', { name: '분석' }))).toBe(true)

    await user.click(summary)
    expect(details.open).toBe(true)
  })

  it('들어올 때 접힌 쪽의 필터가 켜져 있으면 펼친 채 시작하고 개수를 보인다', () => {
    render(
      <RecordFilters
        query={{ kind: ['ANALYSIS'], status: ['FAILED', 'CANCELLED'] }}
        onChange={vi.fn()}
        catalog={null}
        strategies={null}
      />,
    )

    const summary = screen.getByText('상세 필터')
    expect(detailsOf(summary).open).toBe(true)
    expect(summary.textContent).toContain('3개 적용')
  })

  it('펼친 뒤 필터를 모두 끄더라도 저절로 접히지 않는다', async () => {
    const user = userEvent.setup()
    const { rerender } = render(
      <RecordFilters query={{ status: ['FAILED'] }} onChange={vi.fn()} catalog={null} strategies={null} />,
    )
    const summary = screen.getByText('상세 필터')
    expect(detailsOf(summary).open).toBe(true)

    rerender(<RecordFilters query={{}} onChange={vi.fn()} catalog={null} strategies={null} />)
    expect(detailsOf(summary).open).toBe(true)
    expect(screen.queryByText(/개 적용/)).toBeNull()

    await user.click(summary)
    expect(detailsOf(summary).open).toBe(false)
  })
})

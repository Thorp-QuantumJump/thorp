import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { RegimeBaselineMonths } from '@/types/regime'
import { FieldPicker } from './FieldPicker'

const baselines: RegimeBaselineMonths = {
  byField: [
    { id: 'F1', name: '분야 하나', baselineMonth: '2026-07' },
    { id: 'F2', name: '분야 둘', baselineMonth: '2026-06' },
    { id: 'F3', name: '분야 셋', baselineMonth: '2026-07' },
    { id: 'F4', name: '분야 넷', baselineMonth: '2026-07' },
    { id: 'F5', name: '분야 다섯', baselineMonth: '2026-07' },
    { id: 'F6', name: '분야 여섯', baselineMonth: '2026-07' },
    { id: 'F7', name: '분야 일곱', baselineMonth: '2026-07' },
  ],
  composite: '2026-07',
  range: { earliest: '2000-01', latest: '2026-07' },
  note: '',
}

describe('분야 선택', () => {
  it('일곱 분야를 응답 순서대로 그리고 종합은 마지막 선택지다', () => {
    render(<FieldPicker baselines={baselines} value={null} onChange={vi.fn()} />)
    const buttons = screen.getAllByRole('button')
    expect(buttons.map((button) => button.textContent)).toEqual([
      '분야 하나',
      '분야 둘',
      '분야 셋',
      '분야 넷',
      '분야 다섯',
      '분야 여섯',
      '분야 일곱',
      'Thorp 종합 비교',
    ])
  })

  it('처음에는 아무것도 고르지 않았고 종합이 기본이 아니다', () => {
    render(<FieldPicker baselines={baselines} value={null} onChange={vi.fn()} />)
    const pressed = screen.getAllByRole('button').filter((button) => button.getAttribute('aria-pressed') === 'true')
    expect(pressed).toHaveLength(0)
  })

  it('분야를 고르면 그 분야의 최신 기준월이 함께 온다', async () => {
    const onChange = vi.fn()
    render(<FieldPicker baselines={baselines} value={null} onChange={onChange} />)
    await userEvent.click(screen.getByRole('button', { name: '분야 둘' }))
    expect(onChange).toHaveBeenCalledWith({ mode: 'field', field: 'F2', baselineMonth: '2026-06' })
  })
})

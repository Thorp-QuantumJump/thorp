import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { NumberField } from './NumberField'

/**
 * 013 B14 converge. 숫자 칸이 받지 않은 입력을 말없이 버리지 않는다(constitution 원칙 VI, T089와 같은 방식).
 * 값은 부모가 다시 내려 주므로 상태를 가진 부모 안에서 친다.
 */

function Harness({
  initial,
  integer,
  onCommit,
}: {
  initial: number
  integer?: boolean
  onCommit: (value: number) => void
}) {
  const [value, setValue] = useState(initial)
  return (
    <NumberField
      label="값"
      value={value}
      integer={integer}
      onCommit={(next) => {
        setValue(next)
        onCommit(next)
      }}
    />
  )
}

describe('NumberField', () => {
  it('음수와 소수를 칠 수 있고 숫자가 되는 순간 올린다', async () => {
    const onCommit = vi.fn()
    render(<Harness initial={0} onCommit={onCommit} />)
    const user = userEvent.setup()
    const field = screen.getByLabelText('값')

    await user.clear(field)
    await user.type(field, '-2.5')

    expect(field).toHaveProperty('value', '-2.5')
    expect(onCommit).toHaveBeenLastCalledWith(-2.5)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('숫자로 읽을 수 없으면 이유와 지금 값을 알리고 값을 바꾸지 않는다', async () => {
    const onCommit = vi.fn()
    render(<Harness initial={3} onCommit={onCommit} />)
    const user = userEvent.setup()
    const field = screen.getByLabelText('값')

    await user.type(field, '%')

    expect(screen.getByRole('alert').textContent).toBe(
      '숫자로 읽을 수 없어 반영하지 않았습니다. 지금 값은 3입니다.',
    )
    expect(field.getAttribute('aria-invalid')).toBe('true')
    expect(onCommit).not.toHaveBeenCalled()

    // 칸을 벗어나면 확정된 값으로 돌아가고 안내도 걷힌다.
    await user.tab()
    expect(field).toHaveProperty('value', '3')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('정수 칸은 소수를 받지 않는다고 알린다', async () => {
    const onCommit = vi.fn()
    render(<Harness initial={2} integer onCommit={onCommit} />)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('값'), '.5')

    expect(screen.getByRole('alert').textContent).toBe(
      '정수만 받아 반영하지 않았습니다. 지금 값은 2입니다.',
    )
    // '2.'까지는 정수 2로 읽혀 그대로 올라가고, '2.5'는 올라가지 않는다.
    expect(onCommit).not.toHaveBeenCalledWith(2.5)
  })
})

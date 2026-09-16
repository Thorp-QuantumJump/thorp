import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { beforeEach, describe, expect, it } from 'vitest'
import { WeightInput } from './WeightInput'

const commits: number[] = []

/** 실제 화면과 같게 받아들인 값만 되돌아오도록 상태를 함께 둔다. */
function Harness({ initialTenths = 334 }: { initialTenths?: number }) {
  const [weightTenths, setWeightTenths] = useState(initialTenths)

  return (
    <div>
      <WeightInput
        sectorName="반도체"
        valueTenths={weightTenths}
        onCommit={(next) => {
          commits.push(next)
          setWeightTenths(next)
        }}
      />
      <p data-testid="value">{weightTenths}</p>
    </div>
  )
}

function field(): HTMLInputElement {
  return screen.getByRole('spinbutton', { name: '반도체 비중' }) as HTMLInputElement
}

function slider(): HTMLInputElement {
  return screen.getByRole('slider', { name: '반도체 비중' }) as HTMLInputElement
}

function storedValue(): string {
  return screen.getByTestId('value').textContent ?? ''
}

/** 한 번에 값을 통째로 넣는다. 붙여넣기처럼 `step`을 우회하는 경로와 같은 길이다. */
async function replaceWith(text: string): Promise<void> {
  const user = userEvent.setup()
  await user.clear(field())
  await user.paste(text)
}

describe('WeightInput', () => {
  beforeEach(() => {
    commits.length = 0
  })

  it('내부 정수를 소수 첫째 자리로 보인다', () => {
    render(<Harness initialTenths={334} />)

    expect(field().value).toBe('33.4')
  })

  it('0.1 단위로 다루는 숫자 칸이다', () => {
    render(<Harness />)

    // `step`은 화살표 키와 브라우저 검증이 쓰는 값이다. 실제 판정은 `onChange`가 한다.
    expect(field().getAttribute('step')).toBe('0.1')
    expect(field().getAttribute('min')).toBe('0')
    expect(field().getAttribute('max')).toBe('100')
  })

  it('받아들인 값만 올린다', async () => {
    render(<Harness />)
    await replaceWith('40')

    expect(commits.at(-1)).toBe(400)
    expect(storedValue()).toBe('400')
  })

  it('소수 첫째 자리 입력을 정수로 옮겨 올린다', async () => {
    render(<Harness />)
    await replaceWith('12.5')

    expect(commits.at(-1)).toBe(125)
  })

  it('0%를 거부하지 않는다', async () => {
    render(<Harness />)
    await replaceWith('0')

    expect(commits.at(-1)).toBe(0)
    expect(storedValue()).toBe('0')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('소수 둘째 자리는 받지 않고 그 자리에 한 문장으로 알린다', async () => {
    render(<Harness />)
    await replaceWith('33.45')

    // 반올림해 334나 335로 넣지 않는다. 값이 아예 올라가지 않는다.
    expect(commits).toEqual([])
    expect(storedValue()).toBe('334')
    expect(screen.getByRole('alert').textContent).toMatch(/소수 첫째 자리/)
    expect(field().getAttribute('aria-invalid')).toBe('true')
  })

  it('음수는 받지 않는다. 부호를 바꾸어 넣지 않는다', async () => {
    render(<Harness />)
    await replaceWith('-5')

    expect(commits).toEqual([])
    expect(storedValue()).toBe('334')
    expect(screen.getByRole('alert')).toBeTruthy()
  })

  it('100을 넘는 값은 받지 않는다. 상한으로 잘라 넣지 않는다', async () => {
    render(<Harness />)
    await replaceWith('120')

    expect(commits).toEqual([])
    expect(storedValue()).toBe('334')
    expect(screen.getByRole('alert')).toBeTruthy()
  })

  it('숫자로 읽을 수 없는 입력도 말없이 넘기지 않는다', async () => {
    render(<Harness />)

    // `<input type="number">`는 읽을 수 없는 입력을 빈 문자열로 돌려주고 `validity.badInput`만
    // 올린다. jsdom은 그 상태를 만들지 않으므로 같은 상태를 직접 세워 실제 경로를 지나게 한다.
    Object.defineProperty(field(), 'validity', {
      configurable: true,
      value: { badInput: true },
    })
    await userEvent.clear(field())

    expect(commits).toEqual([])
    expect(screen.getByRole('alert').textContent).toMatch(/숫자/)
  })

  it('빈 칸은 0으로 보지 않고 아직 입력 중으로 다룬다', async () => {
    render(<Harness />)
    const user = userEvent.setup()
    await user.clear(field())

    expect(commits).toEqual([])
    expect(storedValue()).toBe('334')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('확정 시점에 받지 않은 입력을 이전 값으로 되돌린다', async () => {
    render(<Harness />)
    const user = userEvent.setup()
    await replaceWith('33.45')

    await user.tab()

    expect(field().value).toBe('33.4')
    expect(storedValue()).toBe('334')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('키보드만으로 입력하고 확정할 수 있다', async () => {
    render(<Harness />)
    const user = userEvent.setup()

    // 탭 순서상 슬라이더가 먼저다. 둘 다 키보드로 닿는다(checklists/ux.md CHK033).
    await user.tab()
    expect(slider()).toBe(document.activeElement)

    await user.tab()
    expect(field()).toBe(document.activeElement)

    await user.keyboard('{Control>}a{/Control}')
    await user.paste('25')
    await user.keyboard('{Enter}')

    expect(storedValue()).toBe('250')
    // 확정한 값과 같은 값이 남는다. `25`와 `25.0`은 숫자 칸에서 같은 값이며, React가 값이 같은
    // 숫자 칸의 표기를 굳이 바꾸지 않는 것은 브라우저 동작과 같다.
    expect(Number(field().value)).toBe(25)
  })

  it('슬라이더와 숫자 칸이 같은 값을 가리킨다', () => {
    render(<Harness initialTenths={334} />)

    expect(slider().value).toBe('334')
    expect(field().value).toBe('33.4')
    // 보조 기술은 내부 단위가 아니라 사용자가 아는 단위로 읽어야 한다.
    expect(slider().getAttribute('aria-valuetext')).toBe('33.4%')
  })

  it('슬라이더는 내부 단위 0.1%p를 그대로 다룬다', () => {
    render(<Harness />)

    // 0~1000을 1씩 움직인다. 눈금 자체가 0.1%p라 끌어서 움직이는 것만으로 반올림이 생기지 않는다.
    expect(slider().getAttribute('min')).toBe('0')
    expect(slider().getAttribute('max')).toBe('1000')
    expect(slider().getAttribute('step')).toBe('1')
  })

  it('슬라이더를 움직이면 숫자 칸도 같은 값을 보인다', () => {
    render(<Harness initialTenths={334} />)

    // 브라우저가 끌기와 화살표 키에 내보내는 것과 같은 사건이다.
    fireEvent.change(slider(), { target: { value: '425' } })

    expect(commits.at(-1)).toBe(425)
    expect(field().value).toBe('42.5')
    expect(slider().getAttribute('aria-valuetext')).toBe('42.5%')
  })

  it('숫자 칸을 고치면 슬라이더도 따라 움직인다', async () => {
    render(<Harness />)
    await replaceWith('12.5')

    expect(slider().value).toBe('125')
  })

  it('슬라이더로는 100%를 넘는 값을 만들 수 없다', () => {
    render(<Harness initialTenths={334} />)

    // 범위 밖 값은 요소가 `max`로 잘라 내보낸다. 상태에는 언제나 0~1000 정수만 올라간다.
    // 자르기를 하는 쪽이 사용자가 끌고 있는 슬라이더 자신이라 "몰래 바꾼" 값이 아니다.
    // 문자열을 통째로 넣는 숫자 칸은 자르지 않고 거부한다(위 검사).
    fireEvent.change(slider(), { target: { value: '1200' } })

    expect(commits.at(-1)).toBe(1000)
    expect(slider().value).toBe('1000')
  })

  it('치고 있던 중간 문자열이 있어도 슬라이더를 움직이면 그 값으로 맞춘다', async () => {
    render(<Harness initialTenths={334} />)
    await replaceWith('33.45')
    expect(screen.getByRole('alert')).toBeTruthy()

    fireEvent.change(slider(), { target: { value: '400' } })

    expect(Number(field().value)).toBe(40)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('밖에서 값이 바뀌면 치고 있지 않은 칸은 그 값을 따른다', async () => {
    function Outside() {
      const [weightTenths, setWeightTenths] = useState(334)
      return (
        <div>
          <WeightInput sectorName="반도체" valueTenths={weightTenths} onCommit={setWeightTenths} />
          <button type="button" onClick={() => setWeightTenths(500)}>
            동일하게 나누기
          </button>
        </div>
      )
    }

    render(<Outside />)
    await userEvent.click(screen.getByRole('button', { name: '동일하게 나누기' }))

    expect(field().value).toBe('50.0')
  })
})

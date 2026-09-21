import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { KospiReference } from './KospiReference'

describe('KOSPI 참고 값', () => {
  it('수익률과 최대 하락 폭 두 값만 보이고 참고 정보로 표기한다', () => {
    const { container } = render(
      <KospiReference
        kospi={{
          window63: { return: '0.0180', maxDrawdown: '-0.0420' },
          window21: { return: '0.0050', maxDrawdown: '-0.0150' },
        }}
      />,
    )
    expect(screen.getByText(/참고 정보/)).toBeTruthy()
    expect(screen.getByText('0.0180')).toBeTruthy()
    expect(screen.getByText('-0.0420')).toBeTruthy()
    // 차이·초과 수익·승패는 타입에 필드가 없어 그릴 수 없다.
    expect(container.textContent).not.toMatch(/차이|초과|이겼|졌/)
    expect(container.querySelectorAll('dd')).toHaveLength(4)
  })

  it('값이 없으면 자리를 만들지 않는다', () => {
    const { container } = render(<KospiReference kospi={null} />)
    expect(container.innerHTML).toBe('')
  })
})

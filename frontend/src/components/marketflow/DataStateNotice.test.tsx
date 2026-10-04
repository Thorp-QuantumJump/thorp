import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DataStateFacts } from './DataStateNotice'

describe('DataStateFacts', () => {
  it('정상 공급이면 표를 달지 않고, 수집 실패·자료 없음은 확인 중입니다로 보인다', () => {
    const { container, rerender } = render(<DataStateFacts status="READY" />)
    // '자료 확인'·소속 지표 수는 적지 않는다(2026-09-29).
    expect(container.textContent).toBe('')
    rerender(<DataStateFacts status="COLLECTION_FAILED" />)
    expect(screen.getByText('확인 중입니다').getAttribute('data-tone')).toBe('pending')
    // 까닭은 보조기기에 읽힌다.
    expect(screen.getByText(/최근 수집 실패/)).toBeTruthy()
  })

  it('예시 자료는 늘 예시임을 밝힌다', () => {
    render(<DataStateFacts status="EXAMPLE" />)
    expect(screen.getByText('예시 자료')).toBeTruthy()
  })
})

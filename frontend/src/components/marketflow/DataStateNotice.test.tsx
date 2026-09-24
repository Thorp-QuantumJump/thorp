import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DataStateFacts, DataStateNotice } from './DataStateNotice'

describe('DataStateNotice', () => {
  it.each([
    ['EXAMPLE', '예시 자료'], ['EMPTY', '표시할 자료 없음'],
    ['COLLECTION_FAILED', '최근 수집 실패'],
  ] as const)('%s 상태를 오류 경계 없이 설명한다', (status, label) => {
    render(<DataStateNotice status={status} referenceMonth="2026-07" lastUpdatedAt={null} historicalComparisonNotice="최신 개정자료를 이용한 역사 비교" />)
    expect(screen.getByText(label)).toBeTruthy()
    expect(screen.getByText('최신 개정자료를 이용한 역사 비교')).toBeTruthy()
    expect(document.body.textContent).not.toMatch(/실시간|현재|지연/)
  })
})

describe('DataStateFacts', () => {
  it('확인된 자료는 자료 확인, 수집 실패·자료 없음은 확인 중입니다로 보인다', () => {
    const { rerender } = render(<DataStateFacts indicatorCount={2} status="READY" />)
    expect(screen.getByText('자료 확인').getAttribute('data-tone')).toBe('confirmed')
    rerender(<DataStateFacts indicatorCount={2} status="COLLECTION_FAILED" />)
    expect(screen.getByText('확인 중입니다').getAttribute('data-tone')).toBe('pending')
    // 까닭은 보조기기에 읽힌다.
    expect(screen.getByText(/최근 수집 실패/)).toBeTruthy()
  })

  it('예시 자료는 늘 예시임을 밝힌다', () => {
    render(<DataStateFacts indicatorCount={2} status="EXAMPLE" />)
    expect(screen.getByText('예시 자료')).toBeTruthy()
  })
})

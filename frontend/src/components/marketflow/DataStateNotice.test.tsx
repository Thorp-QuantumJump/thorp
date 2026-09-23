import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DataStateNotice } from './DataStateNotice'

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

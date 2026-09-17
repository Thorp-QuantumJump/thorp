import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { BasisNotice } from './BasisNotice'

describe('역사 비교 표시', () => {
  it('검색 상태와 관계없이 서버 문구를 보인다', () => {
    render(<BasisNotice context="시험 검색" basis={{ kind: 'HISTORICAL_REVISION', note: '역사 비교 근거입니다.' }} />)
    expect(screen.getByText('역사 비교 근거입니다.')).toBeTruthy()
    expect(screen.getByRole('status', { name: '시험 검색: 역사 비교' })).toBeTruthy()
  })
})

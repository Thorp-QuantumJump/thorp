import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { regimeResult } from '@/test/regimeFixtures'
import type { RegimeStatusReason } from '@/types/regime'
import { EmptyResult } from './EmptyResult'

const reasons: [RegimeStatusReason, string][] = [
  ['SCALE_WINDOW_TOO_SHORT', '비교 기준을 만들 만큼 과거 자료가 이어지지 않아 계산할 수 없습니다.'],
  ['SCALE_ZERO', '이 분야는 값이 거의 변하지 않아 계산할 수 없습니다.'],
  ['QUERY_INPUT_MISSING', '기준월 자료가 없는 지표가 있어 계산할 수 없습니다.'],
  ['NO_CANDIDATE', '비교할 수 있는 과거 시점이 없습니다.'],
]

describe('계산 불가 결과', () => {
  it.each(reasons)('%s를 서버의 한 문장 안내로 보인다', (statusReason, statusNote) => {
    render(<EmptyResult result={regimeResult({ status: 'NOT_COMPUTABLE', statusReason, statusNote, cases: [] })} />)
    expect(screen.getByText(statusNote)).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull()
  })
})

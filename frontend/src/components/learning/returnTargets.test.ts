import { describe, expect, it } from 'vitest'
import { carriedLearningReturn, resolveReturnTarget } from './returnTargets'

/** 013 T150. term-help-ui §3의 세 조건과 R35 표(quickstart 9-6 화면 부분). */

function withReturn(path: unknown, label?: unknown) {
  return { learningReturn: label === undefined ? { path } : { path, label } }
}

describe('resolveReturnTarget', () => {
  it.each([
    ['/diagnosis/sectors', '하던 진단으로 돌아가기'],
    ['/strategy', '하던 진단으로 돌아가기'],
    ['/results/abc123', '하던 진단으로 돌아가기'],
    ['/param-search', '자동 탐색으로 돌아가기'],
    ['/market-flow', '시장 흐름으로 돌아가기'],
    ['/regime-search', '비슷했던 때 보기로 돌아가기'],
    ['/regime-link', '경제환경이 비슷했던 과거로 돌아가기'],
    ['/regime-link/start', '경제환경이 비슷했던 과거로 돌아가기'],
  ])('%s는 표의 문구로 돌아간다', (path, label) => {
    expect(resolveReturnTarget(withReturn(path, '받은 이름'))).toEqual({ path, label })
  })

  it('쿼리 문자열이 붙은 경로는 쿼리까지 그대로 돌아간다', () => {
    expect(resolveReturnTarget(withReturn('/param-search?bundle=x'))).toEqual({
      path: '/param-search?bundle=x',
      label: '자동 탐색으로 돌아가기',
    })
  })

  it('state에 label이 없어도 경로가 맞으면 연결하고, 받은 label은 쓰지 않는다', () => {
    expect(resolveReturnTarget(withReturn('/market-flow'))?.label).toBe('시장 흐름으로 돌아가기')
    expect(resolveReturnTarget(withReturn('/market-flow', '엉뚱한 문구'))?.label).toBe(
      '시장 흐름으로 돌아가기',
    )
  })

  it.each([
    ['state 없음', undefined],
    ['learningReturn 없음', { other: true }],
    ['경로가 문자열이 아님', withReturn(42)],
    ['외부 주소', withReturn('https://evil.example/')],
    ['프로토콜 상대 주소', withReturn('//evil.example')],
    ['인증 화면', withReturn('/login')],
    ['역슬래시', withReturn('/results/\\evil.example')],
    ['정규화하면 달라지는 경로', withReturn('/results/../login')],
    ['표 밖의 경로', withReturn('/learn')],
    ['정확 일치 경로에 글자가 붙음', withReturn('/strategy-x')],
    ['정확 일치 경로 아래', withReturn('/strategy/x')],
    ['자동 탐색 아래', withReturn('/param-search/x')],
    ['경제환경 경로에 글자가 붙음', withReturn('/regime-linkx')],
    ['결과 토큰 없음', withReturn('/results/')],
  ])('%s면 돌아갈 자리가 없다', (_, state) => {
    expect(resolveReturnTarget(state)).toBeNull()
  })
})

describe('carriedLearningReturn', () => {
  it('받은 learningReturn을 다음 이동에 그대로 싣는다', () => {
    expect(carriedLearningReturn(withReturn('/strategy', '전략 선택'))).toEqual({
      path: '/strategy',
      label: '전략 선택',
    })
  })

  it('state가 없거나 경로가 문자열이 아니면 싣지 않는다', () => {
    expect(carriedLearningReturn(undefined)).toBeUndefined()
    expect(carriedLearningReturn(withReturn(null))).toBeUndefined()
  })
})

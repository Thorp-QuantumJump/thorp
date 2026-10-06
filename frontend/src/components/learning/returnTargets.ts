import { normalizeReturnTarget } from '@/auth/returnTarget'
import type { LearningReturnState } from '@/types/learning'

/**
 * 글 화면이 받은 navigation state에서 돌아갈 자리와 문구를 정하는 표(013 term-help-ui §3, research R35).
 *
 * <p><b>문구는 이 표가 정한다.</b> state의 `label`은 요구하지도 쓰지도 않는다. state는 누구나 실어 보낼 수
 * 있으므로 문구를 그대로 믿으면 엉뚱한 문장을 보이게 할 수 있다.
 *
 * <p>표에 화면을 더하거나 문구를 고치는 일은 그 화면이 도움말을 붙이는 MR에서 할 수 있다.
 */
type ReturnRule = {
  /** pathname이 이 규칙에 드는가 */
  matches: (pathname: string) => boolean
  label: string
}

function exactly(path: string): (pathname: string) => boolean {
  return (pathname) => pathname === path
}

const RETURN_RULES: readonly ReturnRule[] = [
  {
    // 002·003·005. 결과 화면만 뒤에 결과 토큰이 붙는다(한 글자 이상).
    matches: (pathname) =>
      pathname === '/diagnosis/sectors' ||
      pathname === '/strategy' ||
      (pathname.startsWith('/results/') && pathname.length > '/results/'.length),
    label: '하던 진단으로 돌아가기',
  },
  { matches: exactly('/param-search'), label: '자동 탐색으로 돌아가기' },
  { matches: exactly('/market-flow'), label: '시장 흐름으로 돌아가기' },
  { matches: exactly('/regime-search'), label: '비슷했던 때 보기로 돌아가기' },
  {
    // 011은 `/regime-link` 자체와 그 아래 단계 화면을 쓴다. `/regime-linkx`는 들지 않는다.
    matches: (pathname) => pathname === '/regime-link' || pathname.startsWith('/regime-link/'),
    label: '경제환경이 비슷했던 과거로 돌아가기',
  },
]

/** 돌아갈 자리. `path`는 쿼리 문자열까지 받은 그대로다. */
export type ReturnTarget = {
  path: string
  label: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** 쿼리 문자열과 조각을 뗀 경로. `normalizeReturnTarget`을 통과한 앱 내부 경로만 받는다. */
function pathnameOf(path: string): string {
  return new URL(path, window.location.origin).pathname
}

/**
 * 받은 state에서 돌아갈 자리를 정한다. 세 조건을 모두 만족할 때만 값이 있다(term-help-ui §3).
 *
 * 1. `learningReturn.path`가 문자열이다
 * 2. `normalizeReturnTarget(path)`가 입력과 같다(014). 쿼리 문자열이 붙은 경로도 받는다
 * 3. 그 경로의 pathname이 표에 든다
 *
 * 배우기 메뉴·외부 링크·새로고침처럼 state가 없으면 null이다(spec Edge Cases).
 */
export function resolveReturnTarget(state: unknown): ReturnTarget | null {
  if (!isRecord(state) || !isRecord(state.learningReturn)) return null
  const { path } = state.learningReturn
  if (typeof path !== 'string' || normalizeReturnTarget(path) !== path) return null
  const pathname = pathnameOf(path)
  const rule = RETURN_RULES.find((candidate) => candidate.matches(pathname))
  return rule ? { path, label: rule.label } : null
}

/**
 * 받은 state에서 `learningReturn`만 꺼내 다음 이동에 그대로 싣는다.
 *
 * 다음 글·글 안 도움말·목록으로 옮겨 가도 처음 떠난 화면을 잃지 않게 한다(spec Edge Cases). 모양만 보고
 * 검사는 받는 쪽이 다시 하므로 표에 없는 경로여도 버리지 않는다.
 */
export function carriedLearningReturn(state: unknown): LearningReturnState | undefined {
  if (!isRecord(state) || !isRecord(state.learningReturn)) return undefined
  const { path, label } = state.learningReturn
  if (typeof path !== 'string') return undefined
  return { path, label: typeof label === 'string' ? label : '' }
}

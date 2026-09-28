const tokenPattern = /^[0-9a-f]{32}$/

/** 결과 그래프에 참고선으로 겹칠 수 있는 지수. 비교 대상 상한과 따로 센다(FR-017). */
export const REFERENCE_INDEX = 'KOSPI'

/** 한 그래프에 겹치는 비교 대상의 상한. 기준까지 넷을 넘으면 색·선 모양으로 갈리지 않는다(FR-004). */
export const MAX_COMPARE_TARGETS = 3

/**
 * 비교 주소를 읽는다. `?compare=`는 쉼표로 이은 공개 토큰 목록이다(contracts "비교 주소").
 * 잘못된 토큰은 버리고, 같은 토큰은 한 번만, 상한을 넘는 것은 뒤에서 자른다.
 * `?index=KOSPI`는 참고 지수선을 켠다. 다른 값은 없는 것으로 본다.
 */
export function parseCompareQuery(search: string) {
  const query = new URLSearchParams(search)
  const tokens: string[] = []
  for (const raw of (query.get('compare') ?? '').split(',')) {
    const token = raw.trim()
    if (tokenPattern.test(token) && !tokens.includes(token)) tokens.push(token)
  }
  return {
    compareTokens: tokens.slice(0, MAX_COMPARE_TARGETS),
    searchId: query.get('search') || null,
    index: query.get('index') === REFERENCE_INDEX,
  }
}

export function buildResultPath(
  baseToken: string,
  options: {
    compareTokens?: readonly string[] | null
    searchId?: string | null
    index?: boolean
  } = {},
): string {
  const query = new URLSearchParams()
  const tokens = (options.compareTokens ?? []).filter((token) => tokenPattern.test(token))
  if (tokens.length) query.set('compare', tokens.slice(0, MAX_COMPARE_TARGETS).join(','))
  if (options.searchId) query.set('search', options.searchId)
  if (options.index) query.set('index', REFERENCE_INDEX)
  return '/results/' + baseToken + (query.size ? '?' + query.toString() : '')
}

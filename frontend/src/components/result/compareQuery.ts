const tokenPattern = /^[0-9a-f]{32}$/

export function parseCompareQuery(search: string) {
  const query = new URLSearchParams(search)
  const token = query.get('compare')
  return {
    compareToken: token && tokenPattern.test(token) ? token : null,
    searchId: query.get('search') || null,
  }
}

export function buildResultPath(
  baseToken: string,
  options: { compareToken?: string | null; searchId?: string | null } = {},
): string {
  const query = new URLSearchParams()
  if (options.compareToken && tokenPattern.test(options.compareToken))
    query.set('compare', options.compareToken)
  if (options.searchId) query.set('search', options.searchId)
  return '/results/' + baseToken + (query.size ? '?' + query.toString() : '')
}

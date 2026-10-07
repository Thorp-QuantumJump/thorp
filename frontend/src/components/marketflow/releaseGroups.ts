/** 같은 대상월·발표일로 묶은 지표 이름. 자료 기준 시점을 지표마다 한 줄씩 늘어놓지 않고 한 줄에 모은다. */
export interface ReleaseGroup {
  month: string | null
  publishedAt: string | null
  names: string[]
}

/**
 * 지표별 대상월·발표일을 (대상월, 발표일)이 같은 것끼리 묶는다. 늦은 대상월이 먼저 오고, 같은 대상월은 늦은 발표일이
 * 먼저다. 이름은 응답 순서를 지킨다. 이름이 없는 지표는 묶음에서 뺀다 — 이름 없이 날짜만 남으면 뜻이 없다.
 */
export function groupReleases(items: readonly { indicator: string | null; month: string | null; publishedAt: string | null }[]): ReleaseGroup[] {
  const groups = new Map<string, ReleaseGroup>()
  for (const item of items) {
    if (!item.indicator) continue
    const key = `${item.month ?? ''}|${item.publishedAt ?? ''}`
    const group = groups.get(key)
    if (group) group.names.push(item.indicator)
    else groups.set(key, { month: item.month, publishedAt: item.publishedAt, names: [item.indicator] })
  }
  return [...groups.values()].sort((a, b) => (b.month ?? '').localeCompare(a.month ?? '') || (b.publishedAt ?? '').localeCompare(a.publishedAt ?? ''))
}

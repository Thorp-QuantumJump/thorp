import type { NewsDigest } from '@/types/marketNews'

/*
  투자 대상 고르기 뉴스 구역의 세 부분에 묶음을 나눠 담는다(012 FR-001).

  - 순서는 주요 뉴스 → 고른 섹터 뉴스 → 분류별 뉴스다. 한 묶음은 먼저 나오는 부분에 한 번만 보인다.
  - 앞 부분에 이미 보인 묶음은 건너뛰고 같은 부분의 다음 순위 묶음을 쓴다. 그래도 모자라면 채우지 않는다.
  - 순위는 012가 정한다. 여기서는 받은 순서를 지키고 다시 줄 세우지 않는다(012 FR-004).
  - 주요 뉴스가 오기 전에는 어느 부분도 정하지 않는다. 무엇을 건너뛸지 모르기 때문이다.
  - 처음 그릴 때(`holdUntilComplete`)는 칸이 모두 올 때까지 고른 섹터·분류별 뉴스를 정하지 않는다. 먼저 온 분류 칸을
    보였다가 고른 섹터 칸이 오면서 묶음이 그쪽으로 옮겨 가면 목록이 흔들린다. 한 번 다 그린 뒤에는 기다리지 않는다. 투자
    대상을 하나 더 고를 때마다 두 부분이 통째로 '불러오는 중'으로 바뀌면 더 크게 흔들리기 때문이다. 그때는 새 칸만 받는
    중으로 두고(`digests: null`), 그 칸은 아무 묶음도 가져가지 않는다.
*/

/** 한 칸이 받은 묶음. 아직 받는 중이면 `'pending'`, 받지 못했으면 빈 배열로 넘긴다. */
export type PartInput = readonly NewsDigest[] | 'pending'

export const NEWS_LIMITS = {
  /** 주요 뉴스: 크게 보이는 1개 + 목록 4개. */
  lead: 5,
  /** 고른 섹터마다. */
  perSelected: 2,
  /** 분류마다. */
  perGroup: 3,
} as const

/**
 * 한 칸에 담을 묶음. `digests`가 `null`이면 그 칸만 아직 받는 중이다. `shownAbove`는 받은 묶음이 있는데 모두 앞 부분에
 * 이미 보였다는 뜻이다. 그 칸이 '뉴스가 없다'고 말하면 사실과 다르다.
 */
export type ArrangedPart = { digests: NewsDigest[] | null; shownAbove: boolean }

export type ArrangedNews = {
  lead: { first: NewsDigest | null; rest: NewsDigest[] } | null
  selected: ({ code: string } & ArrangedPart)[] | null
  groups: ({ id: string } & ArrangedPart)[] | null
}

function take(digests: readonly NewsDigest[], seen: Set<string>, count: number): NewsDigest[] {
  const picked: NewsDigest[] = []
  for (const digest of digests) {
    if (picked.length === count) break
    if (seen.has(digest.digestId)) continue
    seen.add(digest.digestId)
    picked.push(digest)
  }
  return picked
}

function arrangePart(digests: PartInput, seen: Set<string>, count: number): ArrangedPart {
  if (digests === 'pending') return { digests: null, shownAbove: false }
  const picked = take(digests, seen, count)
  return { digests: picked, shownAbove: picked.length === 0 && digests.length > 0 }
}

/** 부분 목록 자체를 아직 정할 수 없을 때(섹터 목록을 받는 중) `'pending'`이다. */
type PartList<T> = readonly T[] | 'pending'

function anyPending(parts: readonly { digests: PartInput }[]): boolean {
  return parts.some((part) => part.digests === 'pending')
}

export function arrangeNews(
  input: {
    lead: PartInput
    selected: PartList<{ code: string; digests: PartInput }>
    groups: PartList<{ id: string; digests: PartInput }>
  },
  { holdUntilComplete = true }: { holdUntilComplete?: boolean } = {},
): ArrangedNews {
  const seen = new Set<string>()
  if (input.lead === 'pending') return { lead: null, selected: null, groups: null }
  const leadDigests = take(input.lead, seen, NEWS_LIMITS.lead)
  const lead = { first: leadDigests[0] ?? null, rest: leadDigests.slice(1) }

  if (input.selected === 'pending' || (holdUntilComplete && anyPending(input.selected))) {
    return { lead, selected: null, groups: null }
  }
  const selected = input.selected.map((part) => ({
    code: part.code,
    ...arrangePart(part.digests, seen, NEWS_LIMITS.perSelected),
  }))

  if (input.groups === 'pending' || (holdUntilComplete && anyPending(input.groups))) {
    return { lead, selected, groups: null }
  }
  const groups = input.groups.map((part) => ({
    id: part.id,
    ...arrangePart(part.digests, seen, NEWS_LIMITS.perGroup),
  }))
  return { lead, selected, groups }
}

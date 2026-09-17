/**
 * `GET /api/market-news/digests` 응답을 camelCase로 1:1 옮긴 타입이다.
 *
 * 정의의 원본은 012의 계약(`specs/012-market-news-digest/contracts/openapi.yaml`)이며 009는
 * 그 정의를 다시 쓰지 않는다.
 *
 * **`NewsDigestState`를 009의 `FieldStatus`와 섞지 않는다.** 이름이 둘 다 상태이고 `EMPTY`가
 * 겹치지만 뜻이 다르고, 뉴스에는 `STALE`이 있고 경제환경 분야에는 **의도적으로 없다**
 * (009 research.md R6). 한 타입으로 합치면 009가 쓰지 않기로 한 판정이 화면에 들어온다.
 */

/** 012가 정하는 네 상태다. **모두 HTTP 200이며 009가 다시 판정하지 않는다.** */
export type NewsDigestState = 'READY' | 'EMPTY' | 'STALE' | 'PREPARING'

/** 발행 시각을 확인했는지 여부다. 확인하지 못한 것을 확인한 것처럼 쓰지 않는다. */
export type PublicationTimeStatus = 'VERIFIED' | 'UNKNOWN'

export type NewsArticle = {
  articleId: string
  title: string
  publisher: string
  publishedAt: string | null
  publicationTimeStatus: PublicationTimeStatus
  originalUrl: string
}

export type NewsDigest = {
  digestId: string
  representativeTitle: string
  factualSummary: string
  firstPublishedAt: string | null
  publicationTimeStatus: PublicationTimeStatus
  publisherCount: number
  sectors: string[]
  topics: string[]
  importanceRuleVersion: string
  articles: NewsArticle[]
}

export type NewsDigestResult = {
  context: 'CURRENT' | 'HISTORICAL'
  state: NewsDigestState
  sectorId: string | null
  from: string | null
  to: string | null
  lastUpdatedAt: string | null
  stale: boolean
  statusMessage: string | null
  /** 최대 3개다. */
  digests: NewsDigest[]
}

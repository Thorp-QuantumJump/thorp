import { AxiosHeaders, type AxiosResponse } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { NewsDigestState } from '@/types/marketNews'
import { apiClient } from './client'
import { fetchMarketNewsDigests } from './marketNews'

function response<T>(data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: 'OK', headers: {}, config: { headers: new AxiosHeaders() } }
}

function body(state: NewsDigestState, digests: unknown[] = []) {
  return {
    httpStatus: 'OK', isSuccess: true, message: 'ok', code: 200,
    result: {
      context: 'CURRENT', state, sectorId: null, from: null, to: null,
      lastUpdatedAt: state === 'PREPARING' ? null : '2026-09-17T00:10:00Z',
      stale: state === 'STALE', statusMessage: null, digests,
    },
  }
}

const digest = {
  digestId: 'a'.repeat(32), representativeTitle: '반도체 수출 증가', factualSummary: '요약',
  firstPublishedAt: '2026-09-16T23:00:00Z', publicationTimeStatus: 'VERIFIED',
  publisherCount: 3, sectors: ['SEMICONDUCTOR'], topics: ['수출'], importanceRuleVersion: 'v1',
  articles: [{
    articleId: 'b'.repeat(32), title: '기사 제목', publisher: '매체',
    publishedAt: '2026-09-16T23:00:00Z', publicationTimeStatus: 'VERIFIED',
    originalUrl: 'https://example.test/news/1',
  }],
}

describe('시장 흐름 뉴스 API', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('묶음을 그대로 읽는다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(response(body('READY', [digest])))

    await expect(fetchMarketNewsDigests()).resolves.toMatchObject({
      state: 'READY',
      digests: [{ digestId: digest.digestId, publisherCount: 3 }],
    })
  })

  /** 네 상태는 모두 정상 응답이다. 예외로 던지면 뉴스가 없다고 화면이 오류로 떨어진다. */
  it.each<NewsDigestState>(['READY', 'EMPTY', 'STALE', 'PREPARING'])(
    '%s는 오류가 아니라 정상 응답이다',
    async (state) => {
      vi.spyOn(apiClient, 'get').mockResolvedValue(response(body(state)))

      await expect(fetchMarketNewsDigests()).resolves.toMatchObject({ state })
    },
  )

  it('섹터를 고르지 않으면 sectorId를 붙이지 않는다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(response(body('EMPTY')))

    await fetchMarketNewsDigests()

    expect(apiClient.get).toHaveBeenCalledWith('/market-news/digests', {
      params: undefined, signal: undefined,
    })
  })

  it('섹터를 골랐으면 sectorId를 그대로 붙인다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(response(body('EMPTY')))

    await fetchMarketNewsDigests('SEMICONDUCTOR')

    expect(apiClient.get).toHaveBeenCalledWith('/market-news/digests', {
      params: { sectorId: 'SEMICONDUCTOR' }, signal: undefined,
    })
  })

  it('섹터를 여럿 주면 쉼표로 이어 한 번에 묻고, limit도 함께 보낸다 (012 FR-001 ③)', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(response(body('EMPTY')))

    await fetchMarketNewsDigests(['NEWS_IT', 'SEMICONDUCTOR'], undefined, undefined, 5)

    // axios 기본 배열 직렬화(sectorId[]=)는 서버가 받지 않으므로 쉼표로 잇는다.
    expect(apiClient.get).toHaveBeenCalledWith('/market-news/digests', {
      params: { sectorId: 'NEWS_IT,SEMICONDUCTOR', limit: '5' }, signal: undefined,
    })
  })

  it('빈 문자열 섹터는 고르지 않은 것과 같다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(response(body('EMPTY')))

    await fetchMarketNewsDigests('')
    await fetchMarketNewsDigests(['', 'NEWS_IT'])

    expect(apiClient.get).toHaveBeenNthCalledWith(1, '/market-news/digests', { params: undefined, signal: undefined })
    expect(apiClient.get).toHaveBeenNthCalledWith(2, '/market-news/digests', {
      params: { sectorId: 'NEWS_IT' }, signal: undefined,
    })
  })

  it('타입에 없는 상태는 막는다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(response(body('UNKNOWN' as NewsDigestState)))

    await expect(fetchMarketNewsDigests()).rejects.toThrow()
  })

  it('묶음 하나가 계약과 어긋나면 전체를 막는다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue(
      response(body('READY', [digest, { ...digest, articles: [] }])),
    )

    // 기사 없는 묶음은 계약 위반이다(minItems: 1). 조용히 빈 카드로 그리지 않는다.
    await expect(fetchMarketNewsDigests()).rejects.toThrow()
  })
})

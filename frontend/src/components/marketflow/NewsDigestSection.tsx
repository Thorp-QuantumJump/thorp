import { useEffect, useState } from 'react'
import { fetchMarketNewsDigests } from '@/api/marketNews'
import type { NewsDigestResult, NewsDigestState } from '@/types/marketNews'
import styles from './MarketFlow.module.css'

/**
 * 012의 뉴스 묶음을 같은 화면에 꽂는다(FR-019).
 *
 * **009는 상태를 다시 판정하지 않는다.** 012가 `READY`·`EMPTY`·`STALE`·`PREPARING` 가운데
 * 하나를 정해 보내면 그대로 그린다. 여기서 "뉴스가 오래됐는지"를 다시 재면 판정이 두 곳으로
 * 갈라지고, 012가 기준(30분)을 바꾼 날 두 화면이 다른 말을 한다.
 *
 * 뉴스가 없거나 늦어도 **분야 요약은 막지 않는다**(SC-007). 그래서 이 영역은 자기 실패를
 * 자기 안에서 끝낸다.
 */

interface NewsDigestSectionProps {
  /** 고른 섹터. 뉴스의 범위만 좁힌다(FR-016). */
  sectorCode: string | null
}

type NewsState =
  | { status: 'loading' }
  | { status: 'ready'; result: NewsDigestResult }
  | { status: 'unavailable' }

/** 012가 정한 상태를 사람 말로 옮긴다. 판정을 더하지 않고 옮기기만 한다. */
const stateNotices: Record<Exclude<NewsDigestState, 'READY'>, string> = {
  EMPTY: '지금 보여 드릴 뉴스 묶음이 없습니다.',
  STALE: '표시한 뉴스가 최근 갱신분이 아닙니다.',
  PREPARING: '뉴스 묶음을 준비하고 있습니다.',
}

export function NewsDigestSection({ sectorCode }: NewsDigestSectionProps) {
  const [state, setState] = useState<NewsState>({ status: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    setState({ status: 'loading' })

    fetchMarketNewsDigests(sectorCode, controller.signal)
      .then((result) => {
        if (!controller.signal.aborted) setState({ status: 'ready', result })
      })
      .catch(() => {
        if (!controller.signal.aborted) setState({ status: 'unavailable' })
      })

    return () => controller.abort()
  }, [sectorCode])

  if (state.status === 'loading') {
    return <p className={styles.sectorNote} role="status">뉴스 묶음을 불러오는 중입니다.</p>
  }

  if (state.status === 'unavailable') {
    return <p className={styles.sectorNote}>뉴스 묶음을 지금은 불러올 수 없습니다.</p>
  }

  const { result } = state
  return (
    <div className={styles.newsBlock}>
      {result.state === 'READY' ? null : (
        <p className={styles.sectorNote}>{result.statusMessage ?? stateNotices[result.state]}</p>
      )}
      {result.digests.length === 0 ? null : (
        <ul className={styles.newsList}>
          {result.digests.map((digest) => (
            <li key={digest.digestId}>
              <a href={digest.articles[0].originalUrl} target="_blank" rel="noreferrer noopener">
                {digest.representativeTitle}
              </a>
              {/* 매체 수와 날짜는 요약 오른쪽에 붙인다. 요약 아래 줄로 내리면 요약의 한 줄처럼 읽힌다. */}
              <div className={styles.newsBody}>
                <p>{digest.factualSummary}</p>
                <span className={styles.newsMeta}>
                  매체 {digest.publisherCount}곳
                  {/* 확인하지 못한 발행 시각을 확인한 것처럼 쓰지 않는다(012 계약). */}
                  {digest.publicationTimeStatus === 'VERIFIED' && digest.firstPublishedAt !== null
                    ? ` · ${digest.firstPublishedAt.slice(0, 10).replaceAll('-', '.')}`
                    : ' · 발행 시각 확인 안 됨'}
                </span>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

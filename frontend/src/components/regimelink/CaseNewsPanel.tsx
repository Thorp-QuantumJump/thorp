import { useEffect, useState } from 'react'
import { fetchMarketNewsDigests } from '@/api/marketNews'
import type { NewsDigestResult } from '@/types/marketNews'
import { newsLoadFailedNote, newsPanelTitle } from './fixedCopy'
import { observationRange } from './observationRange'

/**
 * 당시 뉴스 (T088 · FR-024).
 *
 * **011은 뉴스를 수집하거나 요약하지 않는다.** 012 과거 구간 조회를 부르고 `HISTORICAL` 응답만
 * 그린다. 빈 상태는 012의 문구를 정상 화면으로 보이고, 조회 실패는 이 영역에만 머물러 전략 결과를
 * 막지 않는다.
 */
export function CaseNewsPanel({
  endMonth,
  sectorId,
}: {
  endMonth: string
  sectorId?: string | null
}) {
  const [digests, setDigests] = useState<NewsDigestResult | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    setDigests(null)
    setFailed(false)
    fetchMarketNewsDigests(sectorId ?? null, controller.signal, observationRange(endMonth))
      .then((result) => setDigests(result))
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true)
      })
    return () => controller.abort()
  }, [endMonth, sectorId])

  return (
    <section aria-labelledby="regime-link-news-title">
      <h4 id="regime-link-news-title">{newsPanelTitle}</h4>
      {failed && <p role="status">{newsLoadFailedNote}</p>}
      {digests && digests.context === 'HISTORICAL' && (
        <>
          {digests.statusMessage && <p>{digests.statusMessage}</p>}
          <ul>
            {digests.digests.map((digest) => (
              <li key={digest.digestId}>
                <p>{digest.representativeTitle}</p>
                <p>{digest.factualSummary}</p>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}

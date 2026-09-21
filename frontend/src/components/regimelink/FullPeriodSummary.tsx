import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchResult } from '@/api/result'
import type { PublicResultDetail } from '@/types/result'
import { fullPeriodResultLabel, fullPeriodSummaryTitle } from './fixedCopy'

/**
 * 전체 기간 결과 동반 (T065 · FR-012).
 *
 * 사례 결과 옆에 **항상** 둔다. 사후 구간만으로 전체를 요약하는 문장을 만들지 않기 위해, 같은
 * 자리에서 전체 기간의 핵심 숫자 세 개와 기간을 함께 보이고 상세는 005로 보낸다.
 */
export function FullPeriodSummary({ token }: { token: string }) {
  const [detail, setDetail] = useState<PublicResultDetail | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    setDetail(null)
    fetchResult(token, controller.signal)
      .then((result) => setDetail(result))
      .catch(() => undefined)
    return () => controller.abort()
  }, [token])

  const summary = detail?.summary ?? null

  return (
    <section aria-labelledby={`full-period-${token}`}>
      <h4 id={`full-period-${token}`}>{fullPeriodSummaryTitle}</h4>
      {detail && (
        <p>{`${detail.config.effectiveStart} ~ ${detail.config.effectiveEnd}`}</p>
      )}
      {summary && (
        <dl>
          <dt>비용 차감 후 수익률</dt>
          <dd>{summary.netTotalReturn}</dd>
          <dt>최대 하락 폭</dt>
          <dd>{summary.maxDrawdown}</dd>
          <dt>거래비용 합계</dt>
          <dd>{summary.totalTradingCost}</dd>
        </dl>
      )}
      <Link to={`/results/${token}`}>{fullPeriodResultLabel}</Link>
    </section>
  )
}

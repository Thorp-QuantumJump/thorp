import { useState } from 'react'
import type { RegimeSearchResult } from '@/types/regime'
import { CaseDetailLoader } from './CaseDetailLoader'
import { Disclosure } from './Disclosure'
import styles from './CaseList.module.css'

interface CaseListProps {
  result: RegimeSearchResult
  onShowMore: () => void
  isLoading: boolean
  context: string
  fieldNames: Record<string, string>
}

/** 사례 목록은 관측 구간과 설명을 먼저 보인다. 거리와 기여는 M8 상세의 몫이다. */
export function CaseList({ result, onShowMore, isLoading, context, fieldNames }: CaseListProps) {
  const [selectedEndMonth, setSelectedEndMonth] = useState<string | null>(null)
  return (
    <section aria-labelledby="regime-cases-title" className={styles.list}>
      <div className={styles.heading}>
        <div>
          <p className={styles.kicker}>HISTORICAL CASES</p>
          <h2 id="regime-cases-title">가까운 과거 사례</h2>
        </div>
      </div>
      {result.caseLabel && (
        <Disclosure label="사례 구분" context={context} tone="warning">
          <p>{result.caseLabel}</p>
        </Disclosure>
      )}
      <ul className={styles.cases}>
        {result.cases.map((item) => (
          <li key={item.endMonth} className={styles.case}>
            <div className={styles.caseHeading}>
              <h3>{item.endMonth}</h3>
            </div>
            <p className={styles.months}>
              관측 구간 <span>{item.observationMonths.join(' · ')}</span>
            </p>
            <div className={styles.reasons}>
              <section>
                <h4>비슷한 점</h4>
                <ul>{item.similar.map((sentence, index) => <li key={index}>{sentence}</li>)}</ul>
              </section>
              <section>
                <h4>다른 점</h4>
                <ul>{item.different.map((sentence, index) => <li key={index}>{sentence}</li>)}</ul>
              </section>
            </div>
            {item.detailAvailable && (
              <button
                type="button" className={styles.detailButton}
                aria-expanded={selectedEndMonth === item.endMonth}
                aria-controls={`regime-detail-${item.endMonth}`}
                onClick={() => setSelectedEndMonth((current) => current === item.endMonth ? null : item.endMonth)}
              >
                {selectedEndMonth === item.endMonth ? '상세 닫기' : '상세 보기'}
              </button>
            )}
            {selectedEndMonth === item.endMonth && (
              <div id={`regime-detail-${item.endMonth}`} className={styles.detailSlot}>
                <CaseDetailLoader searchId={result.searchId} summary={item} mode={result.mode} fieldNames={fieldNames} />
              </div>
            )}
          </li>
        ))}
      </ul>
      {result.counts.stored > result.counts.displayed && (
        <button className={styles.more} type="button" onClick={onShowMore} disabled={isLoading}>
          {isLoading ? '불러오는 중' : '더 보기'}
        </button>
      )}
      {result.counts.eligible > result.counts.stored && result.displayLimitNote && (
        <p className={styles.limitNote}>{result.displayLimitNote}</p>
      )}
    </section>
  )
}

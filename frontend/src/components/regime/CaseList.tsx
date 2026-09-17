import type { RegimeSearchResult } from '@/types/regime'
import { Disclosure } from './Disclosure'
import styles from './CaseList.module.css'

interface CaseListProps {
  result: RegimeSearchResult
  onShowMore: () => void
  isLoading: boolean
  context: string
}

/** 사례 목록은 관측 구간과 설명을 먼저 보인다. 거리와 기여는 M8 상세의 몫이다. */
export function CaseList({ result, onShowMore, isLoading, context }: CaseListProps) {
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
      <ol className={styles.cases}>
        {result.cases.map((item) => (
          <li key={item.endMonth} className={styles.case}>
            <div className={styles.caseHeading}>
              <span className={styles.rank}>{String(item.rank).padStart(2, '0')}</span>
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
          </li>
        ))}
      </ol>
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

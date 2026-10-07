import { useState, type ReactNode } from 'react'
import type { RegimeCaseSummary, RegimeSearchResult } from '@/types/regime'
import { CaseDetailLoader } from './CaseDetailLoader'
import { Disclosure } from './Disclosure'
import * as copy from './fixedCopy'
import styles from './CaseList.module.css'

interface CaseListProps {
  result: RegimeSearchResult
  onShowMore: () => void
  isLoading: boolean
  context: string
  fieldNames: Record<string, string>
  /**
   * 사례 상세의 사후 구간 자리에 끼워 넣을 것을 만든다(011 진입 계약 §4).
   *
   * 없으면 010이 내려 준 문구를 그대로 그린다. 사례마다 다른 것을 넣어야 해서 값이 아니라 함수다.
   */
  postPeriodSlot?: (summary: RegimeCaseSummary) => ReactNode
}

/** 사례 목록은 관측 구간과 설명을 먼저 보인다. 거리와 기여는 M8 상세의 몫이다. */
export function CaseList({ result, onShowMore, isLoading, context, fieldNames, postPeriodSlot }: CaseListProps) {
  const [selectedEndMonth, setSelectedEndMonth] = useState<string | null>(null)
  const [showReferences, setShowReferences] = useState(false)
  const [expandedCases, setExpandedCases] = useState<Set<string>>(() => new Set())
  const toggleCase = (endMonth: string) =>
    setExpandedCases((current) => {
      const next = new Set(current)
      if (next.has(endMonth)) next.delete(endMonth)
      else next.add(endMonth)
      return next
    })
  const references = result.referenceCases ?? []
  const renderCase = (item: RegimeCaseSummary) => (
    <li key={item.endMonth} className={styles.case}>
      <div className={styles.caseHeading}>
        <h3>{item.endMonth}</h3>
      </div>
      <p className={styles.months}>
        관측 구간 <span>{item.observationMonths.join(' · ')}</span>
      </p>
      {(() => {
        // 문장이 많은 사례(종합 비교)는 처음에 한 문장씩만 보인다(S15P21E205-450). 문장은 서버가 만든 그대로이고
        // 화면은 몇 개를 먼저 보일지만 정한다.
        const expanded = expandedCases.has(item.endMonth)
        const hidden = Math.max(0, item.similar.length - 1) + Math.max(0, item.different.length - 1)
        const similar = expanded ? item.similar : item.similar.slice(0, 1)
        const different = expanded ? item.different : item.different.slice(0, 1)
        return (
          <>
            <div className={styles.reasons}>
              <section>
                <h4>비슷한 점</h4>
                <ul>{similar.map((sentence, index) => <li key={index}>{sentence}</li>)}</ul>
              </section>
              <section>
                <h4>다른 점</h4>
                <ul>{different.map((sentence, index) => <li key={index}>{sentence}</li>)}</ul>
              </section>
            </div>
            {(hidden > 0 || (item.context?.length ?? 0) > 0) && (
              <button
                type="button"
                className={styles.moreReasons}
                aria-expanded={expanded}
                onClick={() => toggleCase(item.endMonth)}
              >
                {expanded ? '설명 접기' : '설명 더 보기'}
              </button>
            )}
            {expanded && item.context && item.context.length > 0 && (
              <section className={styles.context} aria-label={`${item.endMonth} 당시 맥락`}>
                <h4>당시 맥락</h4>
                <p>{copy.contextNote}</p>
                <ul>
                  {item.context.map((link) => (
                    <li key={`${link.kind}-${link.date}-${link.title}`}>
                      <time dateTime={link.date}>{link.date}</time>{' '}
                      {link.url ? (
                        <a href={link.url} target="_blank" rel="noopener noreferrer">{link.title}</a>
                      ) : (
                        <span>{link.title}</span>
                      )}
                      <span className={styles.contextSource}> · {link.source}</span>
                    </li>
                  ))}
                </ul>
              </section>
            )}
          </>
        )
      })()}
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
          <CaseDetailLoader searchId={result.searchId} summary={item} mode={result.mode} fieldNames={fieldNames} postPeriodSlot={postPeriodSlot?.(item)} />
        </div>
      )}
    </li>
  )
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
      {result.phaseNote && (
        <Disclosure label="지금 시점" context={context} tone="warning">
          <p>{result.phaseNote}</p>
        </Disclosure>
      )}
      <ul className={styles.cases}>
        {result.cases.map(renderCase)}
      </ul>
      {result.thresholdNote && (
        <Disclosure label="유사성 기준" context={context} tone="warning">
          <p>{result.thresholdNote}</p>
        </Disclosure>
      )}
      {references.length > 0 && (
        <section className={styles.references} aria-label={result.referenceLabel ?? copy.referenceLabel}>
          <button
            type="button" className={styles.more}
            aria-expanded={showReferences} aria-controls="regime-reference-cases"
            onClick={() => setShowReferences((current) => !current)}
          >
            {showReferences ? `${result.referenceLabel ?? copy.referenceLabel} 접기` : `${result.referenceLabel ?? copy.referenceLabel} 보기`}
          </button>
          {showReferences && (
            <>
              <p className={styles.limitNote}>{copy.referenceNote}</p>
              <ul id="regime-reference-cases" className={styles.cases}>{references.map(renderCase)}</ul>
            </>
          )}
        </section>
      )}
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

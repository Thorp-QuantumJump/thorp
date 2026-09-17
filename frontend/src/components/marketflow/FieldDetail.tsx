import { ArrowUpRight, ScanLine } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import type { FieldDetailResult } from '@/types/marketFlow'
import { DataStateNotice } from './DataStateNotice'
import { ThreeMonthChart } from './ThreeMonthChart'
import { formatObservation, formatUpdatedAt } from './marketFlowFormat'
import styles from './MarketFlow.module.css'

/** 선택한 분야의 원자료와 3개월 경로를 가공 점수 없이 보여 준다. */
export function FieldDetail({ detail }: { detail: FieldDetailResult }) {
  const reducedMotion = useReducedMotion()
  const titleId = `field-${detail.fieldId}-title`
  const similarNoteId = `similar-note-${detail.fieldId}`
  return (
    <motion.section
      className={styles.detail}
      aria-labelledby={titleId}
      initial={reducedMotion ? false : { opacity: 0, y: 5 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: reducedMotion ? 0 : 0.2 }}
    >
      <header className={styles.detailHeader}>
        <div className={styles.detailTitle}>
          <div><span className={styles.detailEyebrow}>FIELD / {String(detail.displayOrder).padStart(2, '0')}</span><h2 id={titleId}>{detail.displayName}</h2></div>
          <span className={styles.indicatorCount}>소속 지표 <b>{detail.indicators.length}</b></span>
        </div>
        <p className={styles.detailNarrative}>{detail.narrative ?? '아래 자료 공급 상태와 기준 시점을 확인해 주세요.'}</p>
        <DataStateNotice status={detail.status} referenceMonth={detail.referenceMonth} lastUpdatedAt={detail.lastUpdatedAt} isExampleData={detail.isExampleData} />
        <p className={styles.referenceNote}>{detail.multipleReferenceMonthNotice}</p>
        {detail.referenceMonthHeldBackBy.length > 0 ? (
          <p className={styles.referenceNote}>
            기준월 결정에 반영된 지표: {detail.referenceMonthHeldBackBy.map((id) => detail.indicators.find((indicator) => indicator.indicatorId === id)?.name ?? id).join(', ')}
          </p>
        ) : null}
      </header>
      {detail.indicators.length === 0 ? (
        <div className={styles.emptyDetail}><ScanLine size={28} aria-hidden="true" /><p>표시할 지표 자료가 없습니다.</p><span>다른 분야의 자료는 계속 살펴볼 수 있습니다.</span></div>
      ) : (
        <div className={styles.indicatorList}>
          {detail.indicators.map((indicator) => (
            <article className={styles.indicator} key={indicator.indicatorId}>
              <div className={styles.indicatorHeading}>
                <div><span className={styles.indicatorAgency}>{indicator.agency}</span><h3>{indicator.name}</h3></div>
                <strong className={styles.observation}>{formatObservation(indicator.value)}{indicator.value === null ? null : <small>{indicator.unit}</small>}</strong>
              </div>
              <ThreeMonthChart indicator={indicator} />
              {indicator.afterReferenceMonthRelease ? (
                <p className={styles.afterRelease}>
                  기준월 뒤 발표 · {indicator.afterReferenceMonthRelease.referenceMonth}{' '}
                  {formatObservation(indicator.afterReferenceMonthRelease.value)} {indicator.afterReferenceMonthRelease.unit}
                  <span>3개월 경로에는 포함하지 않습니다.</span>
                </p>
              ) : null}
              <dl className={styles.indicatorMeta}>
                <div><dt>대상월</dt><dd>{indicator.referenceMonth}</dd></div>
                <div><dt>발표일</dt><dd>{indicator.releaseDate ?? '확인할 수 없음'}</dd></div>
                <div><dt>출처</dt><dd>{indicator.source}</dd></div>
                <div><dt>작성기관</dt><dd>{indicator.agency}</dd></div>
                <div className={styles.updatedMeta}><dt>마지막 갱신</dt><dd>{formatUpdatedAt(detail.lastUpdatedAt)}{detail.lastUpdatedAt ? ' KST' : ''}</dd></div>
              </dl>
            </article>
          ))}
        </div>
      )}
      {/* spec 010의 검색 목적지/입력 계약이 연결되기 전에는 임의 검색 결과를 제공하지 않는다. */}
      <div className={styles.similarEntry}>
        <div><span className={styles.detailEyebrow}>EXPLORE THE PAST</span><p id={similarNoteId}>이 분야와 비슷했던 시기를 찾는 기능을 준비하고 있습니다.</p></div>
        <button type="button" className={styles.similarButton} disabled aria-describedby={similarNoteId}>비슷했던 때 보기 <ArrowUpRight size={18} aria-hidden="true" /><small>준비 중</small></button>
      </div>
    </motion.section>
  )
}

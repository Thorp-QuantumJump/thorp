import { ArrowUpRight, ScanLine } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import { Link } from 'react-router-dom'
import { buildRegimeSearchPath, buildSimilarPeriodHandoff } from '@/marketflow/handoff'
import type { FieldDetailResult } from '@/types/marketFlow'
import { DataStateNotice } from './DataStateNotice'
import { ThreeMonthChart } from './ThreeMonthChart'
import { formatObservation } from './marketFlowFormat'
import styles from './MarketFlow.module.css'

/** 선택한 분야의 원자료와 3개월 경로를 가공 점수 없이 보여 준다. */
export function FieldDetail({ detail }: { detail: FieldDetailResult }) {
  const reducedMotion = useReducedMotion()
  const titleId = `field-${detail.fieldId}-title`
  const similarNoteId = `similar-note-${detail.fieldId}`
  const similarPeriod = buildSimilarPeriodHandoff(detail)
  const similarPath = buildRegimeSearchPath(similarPeriod)
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
                <h3>{indicator.name}</h3>
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
              {/*
                대상월·발표일·출처·작성기관을 한 줄로 잇는다(FR-007·013·015). 다섯 줄 표로 두면 카드마다 표가 하나씩
                늘어 지표보다 출처가 먼저 읽혔다. 마지막 갱신은 분야마다 하나라 상세 머리에만 둔다(FR-011).
              */}
              <p className={styles.indicatorSource}>
                <span>대상월 {indicator.referenceMonth}</span>
                <span>{indicator.releaseDate ? `${indicator.releaseDate} 발표` : '발표일 확인할 수 없음'}</span>
                <span>{indicator.agency} · {indicator.source}</span>
              </p>
            </article>
          ))}
        </div>
      )}
      {/* 넘기는 값은 분야와 그 분야의 기준월뿐이다. 섹터를 골랐어도 달라지지 않는다(FR-016). */}
      <div className={styles.similarEntry}>
        <div>
          <span className={styles.detailEyebrow}>EXPLORE THE PAST</span>
          <p id={similarNoteId}>
            {similarPeriod === null
              ? '이 분야는 기준월이 아직 정해지지 않아 비슷했던 시기를 찾을 수 없습니다.'
              : `${detail.displayName} 분야의 ${similarPeriod.referenceMonth}과 비슷했던 시기를 찾습니다.`}
          </p>
        </div>
        {similarPath === null ? (
          <button type="button" className={styles.similarButton} disabled aria-describedby={similarNoteId}>
            비슷했던 때 보기 <ArrowUpRight size={18} aria-hidden="true" />
          </button>
        ) : (
          <Link
            to={similarPath}
            className={styles.similarButton}
            aria-describedby={similarNoteId}
            data-handoff={JSON.stringify(similarPeriod)}
          >
            비슷했던 때 보기 <ArrowUpRight size={18} aria-hidden="true" />
          </Link>
        )}
      </div>
    </motion.section>
  )
}

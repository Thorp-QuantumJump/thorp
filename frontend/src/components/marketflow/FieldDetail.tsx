import { ArrowUpRight, ScanLine } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import { Link } from 'react-router-dom'
import { buildRegimeSearchPath, buildSimilarPeriodHandoff } from '@/marketflow/handoff'
import type { FieldDetailResult } from '@/types/marketFlow'
import { DataStateFacts } from './DataStateNotice'
import { ThreeMonthChart } from './ThreeMonthChart'
import { InlineHelp } from '@/components/common/InlineHelp'
import { describeAxis, formatObservation, formatUpdatedAt, sourceParts } from './marketFlowFormat'
import styles from './MarketFlow.module.css'

/** 선택한 분야의 원자료와 3개월 경로를 가공 점수 없이 보여 준다. */
export function FieldDetail({ detail, historyNotice }: { detail: FieldDetailResult; historyNotice?: string }) {
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
        {/* 기준 시점(대상월·마지막 갱신)과 상태 표(수집 실패·예시 자료일 때만)는 분야 제목 줄 오른쪽 위에 선다. */}
        <div className={styles.detailTitle}>
          <h2 id={titleId}>{detail.displayName}</h2>
          <div className={styles.detailMeta}>
            <p className={styles.detailTiming}>
              <span>대상월 {detail.referenceMonth ?? '확인할 수 없음'}</span>
              <span>마지막 갱신 {formatUpdatedAt(detail.lastUpdatedAt)}{detail.lastUpdatedAt ? ' KST' : ''}</span>
            </p>
            <DataStateFacts status={detail.status} isExampleData={detail.isExampleData} />
          </div>
        </div>
        <p className={styles.detailNarrative}>{detail.narrative ?? '기준 시점과 자료 상태를 확인해 주세요.'}</p>
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
                출처와 작성기관을 이름을 붙여 한 줄로 잇는다(FR-015). 발표일은 실제 자료가 주지 않아 적지 않는다(2026-09-24).
                대상월은 분야 머리와 다를 때만 적고, 마지막 갱신은 분야마다 하나라 머리에만 둔다(FR-011).
              */}
              {/* 출처는 카드 오른쪽 아래, 세로축 범위 물음표는 왼쪽 아래에 둔다. 그래프 위에 두면 제목 줄과 겹쳐 읽힌다. */}
              <div className={styles.indicatorFooter}>
                <InlineHelp label="세로축 범위" side="top"><p>{describeAxis(indicator)}</p></InlineHelp>
                <p className={styles.indicatorSource}>
                  {indicator.referenceMonth !== detail.referenceMonth ? <span>대상월 {indicator.referenceMonth}</span> : null}
                  {sourceParts(indicator.agency, indicator.source).map((part) => <span key={part}>{part}</span>)}
                </p>
              </div>
            </article>
          ))}
        </div>
      )}
      {/*
        분야마다 기준월이 다를 수 있다는 안내(FR-008)와 최신 개정자료로 비교한다는 안내(FR-012)는 지표를 다 본 뒤
        읽히도록 카드 아래 오른쪽에 작게 둔다. 역사 비교 안내는 예전에 화면 맨 아래 따로 있던 줄이다.
      */}
      <div className={styles.referenceNotes}>
        <p className={styles.referenceNote}>{detail.multipleReferenceMonthNotice}</p>
        {historyNotice ? <p className={styles.referenceNote}>{historyNotice}</p> : null}
        {detail.referenceMonthHeldBackBy.length > 0 ? (
          <p className={styles.referenceNote}>
            기준월 결정에 반영된 지표: {detail.referenceMonthHeldBackBy.map((id) => detail.indicators.find((indicator) => indicator.indicatorId === id)?.name ?? id).join(', ')}
          </p>
        ) : null}
      </div>
      {/* 넘기는 값은 분야와 그 분야의 기준월뿐이다. 섹터를 골랐어도 달라지지 않는다(FR-016). */}
      {/*
        버튼 하나만 오른쪽에 둔다. 무엇을 찾는지는 버튼의 설명으로 보조기기에만 읽히고, 찾을 수 없는 까닭(기준월 미정)만
        화면에 남긴다 — 버튼이 왜 눌리지 않는지는 보여야 한다.
      */}
      <div className={styles.similarEntry}>
        {similarPeriod === null ? (
          <p id={similarNoteId}>이 분야는 기준월이 아직 정해지지 않아 비슷했던 시기를 찾을 수 없습니다.</p>
        ) : (
          <p id={similarNoteId} className={styles.srOnly}>{`${detail.displayName} 분야의 ${similarPeriod.referenceMonth}과 비슷했던 시기를 찾습니다.`}</p>
        )}
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

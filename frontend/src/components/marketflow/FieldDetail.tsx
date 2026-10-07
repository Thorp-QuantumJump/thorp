import { ScanLine } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import type { ReactNode } from 'react'
import type { FieldDetailResult } from '@/types/marketFlow'
import { DataStateFacts } from './DataState'
import { IndicatorCard } from './IndicatorCard'
import { formatNarrative, formatUpdatedAt } from './marketFlowFormat'
import type { IndicatorPast } from './pastOverlay'
import styles from './MarketFlow.module.css'

/**
 * 선택한 분야의 원자료와 3개월 경로를 가공 점수 없이 보여 준다.
 *
 * `compare`는 머리와 지표 칸 사이에 서는 '지금과 비슷했던 과거' 줄이고, `pasts`는 그 줄에서 고른 과거 구간을
 * 지표마다 겹칠 값이다(009 FR-019). 둘 다 없으면 지금의 지표만 보인다.
 */
export function FieldDetail({ detail, historyNotice, compare = null, pasts = null, showBand = false, comparing = false }: {
  detail: FieldDetailResult
  /** 서버(목록 응답)가 정한 역사 비교 안내 문장(FR-012). 있을 때만 시점 줄에 짧은 표시를 단다 */
  historyNotice?: string | null
  compare?: ReactNode
  /** 지표 id마다 겹칠 당시 경로·비슷한 점/다른 점 문장·보통 범위. */
  pasts?: Readonly<Record<string, IndicatorPast>> | null
  showBand?: boolean
  /** 과거 구간을 고른 상태. 당시 경로를 받는 중에도 그래프 높이를 그대로 둔다. */
  comparing?: boolean
}) {
  const reducedMotion = useReducedMotion()
  const titleId = `field-${detail.fieldId}-title`
  return (
    <motion.section
      className={styles.detail}
      aria-labelledby={titleId}
      initial={reducedMotion ? false : { opacity: 0, y: 5 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: reducedMotion ? 0 : 0.2 }}
    >
      {/*
        머리는 두 줄 격자다. 윗줄은 왼쪽 제목·오른쪽 기준 시점, 아랫줄은 요약 문장이다. 예전 '비슷했던 때 보기'
        버튼은 아래 '지금과 비슷했던 과거' 줄의 '모두 보기'가 대신한다(009 FR-009).
      */}
      <header className={styles.detailHeader}>
        <h2 id={titleId} className={styles.detailName}>{detail.displayName}</h2>
        <div className={styles.detailMeta}>
          {/*
            '최신 개정자료 기준'은 역사 탐색의 자료 기준을 화면에 밝히라는 constitution 원칙 V(FR-012)의 표시다. 긴 안내 문장
            대신 시점 줄에 짧게 두고, 문장의 원본은 서버가 준 안내다 — 짧은 표시는 그 안내가 있을 때만 보이고, 원문은
            마우스를 올리면(title) 보이며 보조기기에도 읽힌다. 상태 표(수집 실패·예시 자료일 때만)는 그 밑에 선다.
          */}
          <p className={styles.detailTiming}>
            <span>대상월 {detail.referenceMonth ?? '확인할 수 없음'}</span>
            <span>마지막 갱신 {formatUpdatedAt(detail.lastUpdatedAt)}{detail.lastUpdatedAt ? ' KST' : ''}</span>
            {historyNotice ? (
              <span title={historyNotice}>
                최신 개정자료 기준<span className={styles.srOnly}>: {historyNotice}</span>
              </span>
            ) : null}
          </p>
          <DataStateFacts status={detail.status} isExampleData={detail.isExampleData} />
        </div>
        <p className={styles.detailNarrative}>{formatNarrative(detail.narrative) ?? '기준 시점과 자료 상태를 확인해 주세요.'}</p>
      </header>
      {compare}
      {detail.indicators.length === 0 ? (
        <div className={styles.emptyDetail}><ScanLine size={28} aria-hidden="true" /><p>표시할 지표 자료가 없습니다.</p><span>다른 분야의 자료는 계속 살펴볼 수 있습니다.</span></div>
      ) : (
        <div className={styles.indicatorList}>
          {detail.indicators.map((indicator) => (
            <IndicatorCard
              key={indicator.indicatorId}
              indicator={indicator}
              fieldReferenceMonth={detail.referenceMonth}
              past={pasts?.[indicator.indicatorId] ?? null}
              showBand={showBand}
              comparing={comparing}
            />
          ))}
        </div>
      )}
      {/* 기준월을 늦춘 지표가 있을 때만 그 이름을 카드 아래 오른쪽에 적는다(FR-008). */}
      {detail.referenceMonthHeldBackBy.length > 0 ? (
        <div className={styles.referenceNotes}>
          <p className={styles.referenceNote}>
            기준월 결정에 반영된 지표: {detail.referenceMonthHeldBackBy.map((id) => detail.indicators.find((indicator) => indicator.indicatorId === id)?.name ?? id).join(', ')}
          </p>
        </div>
      ) : null}
    </motion.section>
  )
}

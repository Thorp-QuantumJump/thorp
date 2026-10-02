import { ScanLine } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import type { ReactNode } from 'react'
import type { FieldDetailResult, IndicatorDisplay } from '@/types/marketFlow'
import { DataStateFacts } from './DataStateNotice'
import { ThreeMonthChart } from './ThreeMonthChart'
import { InlineHelp } from '@/components/common/InlineHelp'
import { describeAxis, formatNarrative, formatObservation, formatUpdatedAt, sourceParts } from './marketFlowFormat'
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
          {detail.indicators.map((indicator) => {
            const past = pasts?.[indicator.indicatorId] ?? null
            const band = showBand ? past?.band ?? null : null
            return (
              <article className={styles.indicator} key={indicator.indicatorId}>
                <div className={styles.indicatorHeading}>
                  <h3>{indicator.name}</h3>
                  <strong className={styles.observation}>{formatObservation(indicator.value, indicator.unit)}{indicator.value === null ? null : <small>{indicator.unit}</small>}</strong>
                </div>
                {past ? <PastLine indicator={indicator} past={past} /> : null}
                <ThreeMonthChart indicator={indicator} past={past?.path ?? null} band={band} comparing={comparing} />
                {indicator.afterReferenceMonthRelease ? (
                  <p className={styles.afterRelease}>
                    기준월 뒤 발표 · {indicator.afterReferenceMonthRelease.referenceMonth}{' '}
                    {formatObservation(indicator.afterReferenceMonthRelease.value, indicator.afterReferenceMonthRelease.unit)} {indicator.afterReferenceMonthRelease.unit}
                    <span>3개월 경로에는 포함하지 않습니다.</span>
                  </p>
                ) : null}
                {/*
                  출처와 작성기관을 이름을 붙여 한 줄로 잇는다(FR-015). 발표일은 실제 자료가 주지 않아 적지 않는다(2026-09-24).
                  대상월은 분야 머리와 다를 때만 적고, 마지막 갱신은 분야마다 하나라 머리에만 둔다(FR-011).
                */}
                {/* 출처는 카드 오른쪽 아래, 세로축 범위 물음표는 왼쪽 아래에 둔다. 그래프 위에 두면 제목 줄과 겹쳐 읽힌다. */}
                <div className={styles.indicatorFooter}>
                  <InlineHelp label="세로축 범위" side="top"><p>{describeAxis(indicator, axisExtras(past, band))}</p></InlineHelp>
                  <p className={styles.indicatorSource}>
                    {indicator.referenceMonth !== detail.referenceMonth ? <span>대상월 {indicator.referenceMonth}</span> : null}
                    {sourceParts(indicator.agency, indicator.source).map((part) => <span key={part}>{part}</span>)}
                  </p>
                </div>
              </article>
            )
          })}
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

/** 축을 넓히는 당시 값과 보통 범위. 그래프와 물음표 설명이 같은 축을 말하게 한다. */
function axisExtras(past: IndicatorPast | null, band: [number, number] | null): number[] {
  return [...(past?.path?.values.flatMap((value) => (value === null ? [] : [value])) ?? []), ...(band ?? [])]
}

/**
 * 지표 칸 머리 밑 한 줄. 010의 비슷한 점·다른 점 문장이 이 지표를 다루면 그 문장을, 아니면 지금 값과 당시 값만
 * 적는다(009 FR-019). 당시 값은 그래프의 마지막 달(t)과 맞댄 값이다.
 */
function PastLine({ indicator, past }: { indicator: IndicatorDisplay; past: IndicatorPast }) {
  if (past.verdict) {
    const similar = past.verdict.kind === 'similar'
    return (
      <p className={styles.pastLine} data-kind={past.verdict.kind}>
        <b className={styles.pastTag}>{similar ? '비슷한 점' : '다른 점'}</b>
        {/* 010 문장의 숫자도 이 칸의 다른 숫자처럼 줄여 적는다(009 요약 문장과 같은 formatNarrative). 문장은 그대로다. */}
        <span>{formatNarrative(past.verdict.text)}</span>
      </p>
    )
  }
  const then = past.path?.values[past.path.values.length - 1] ?? null
  if (then === null) return null
  return (
    <p className={styles.pastLine}>
      <span>지금 {withUnit(indicator.value, indicator.unit)} · 당시 <b className={styles.pastThen}>{withUnit(then, indicator.unit)}</b></span>
    </p>
  )
}

/** 값과 단위를 이어 적는다. %는 붙이고 다른 단위는 한 칸 띄운다. 값이 없으면 단위를 붙이지 않는다. */
function withUnit(value: number | null, unit: string): string {
  const text = formatObservation(value, unit)
  if (value === null) return text
  return unit === '%' ? `${text}%` : `${text} ${unit}`
}

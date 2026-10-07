import { InlineHelp } from '@/components/common/InlineHelp'
import type { IndicatorDisplay } from '@/types/marketFlow'
import { ThreeMonthChart } from './ThreeMonthChart'
import { describeAxis, formatNarrative, formatObservation, sourceParts } from './marketFlowFormat'
import type { IndicatorPast } from './pastOverlay'
import styles from './MarketFlow.module.css'

/**
 * 지표 한 칸. 이름과 실제 값, 3개월 경로 그래프, 출처를 가공 점수 없이 보인다(009 FR-007·FR-015).
 *
 * 분야 상세(FieldDetail)와 종합 비교(CompositePast)가 같은 칸을 쓴다. `past`가 있으면 고른 과거 구간의 같은 지표
 * 경로를 점선으로 겹치고, 010의 비슷한 점·다른 점 문장이 이 지표를 다루면 머리 밑 한 줄에 붙인다(009 FR-019).
 */
export function IndicatorCard({ indicator, fieldReferenceMonth, headingTag: Heading = 'h3', past = null, showBand = false, comparing = false }: {
  indicator: IndicatorDisplay
  /** 분야 머리의 대상월. 지표의 대상월이 이와 다를 때만 칸에 적는다(FR-011). */
  fieldReferenceMonth: string | null
  /** 지표 이름의 제목 단계. 분야 상세(h2) 안에서는 h3, 종합 비교의 분야 묶음(h3) 안에서는 h4다. */
  headingTag?: 'h3' | 'h4'
  past?: IndicatorPast | null
  showBand?: boolean
  /** 과거 구간을 고른 상태. 당시 경로를 받는 중에도 그래프 높이를 그대로 둔다. */
  comparing?: boolean
}) {
  const band = showBand ? past?.band ?? null : null
  return (
    <article className={styles.indicator}>
      <div className={styles.indicatorHeading}>
        <Heading>{indicator.name}</Heading>
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
          {indicator.referenceMonth !== fieldReferenceMonth ? <span>대상월 {indicator.referenceMonth}</span> : null}
          {sourceParts(indicator.agency, indicator.source).map((part) => <span key={part}>{part}</span>)}
        </p>
      </div>
    </article>
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

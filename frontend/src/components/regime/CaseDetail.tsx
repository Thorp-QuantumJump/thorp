import type { ReactNode } from 'react'
import type { RegimeCaseDetail, RegimeCaseSummary, RegimeMode } from '@/types/regime'
import { FieldBreakdown } from './FieldBreakdown'
import { Num } from './Num'
import * as copy from './fixedCopy'
import styles from './CaseDetail.module.css'

interface Props {
  detail: RegimeCaseDetail
  summary: RegimeCaseSummary
  mode: RegimeMode
  fieldNames: Record<string, string>
  /**
   * 사후 구간 자리에 끼워 넣을 것(011 진입 계약 §4).
   *
   * 없으면 010이 내려 준 `postPeriod.note`를 그대로 그린다 — 010 동작을 그대로 둔다. 010 검색 화면은
   * 여기에 '이 기간으로 전략 비교' 링크를, 011 화면은 전략 결과 영역을 넣는다.
   */
  postPeriodSlot?: ReactNode
}

/** 저장된 계산 입력과 척도를 그대로 보여 손계산 대조가 가능하게 한다. */
export function CaseDetail({ detail, summary, mode, fieldNames, postPeriodSlot }: Props) {
  return (
    <section className={styles.detail} aria-label={`${detail.endMonth} 사례 상세`}>
      <h4>사례 상세 · {detail.endMonth}</h4>
      <p>관측 구간 {detail.observationMonths.join(' · ')}</p>
      <p>전체 거리 <strong><Num value={detail.distance} /></strong></p>
      {mode === 'COMPOSITE' ? (
        <FieldBreakdown detail={detail} summary={summary} fieldNames={fieldNames} />
      ) : (
        <div className={styles.reasons}>
          <section><h5>비슷한 점</h5><ul>{summary.similar.map((sentence, index) => <li key={index}>{sentence}</li>)}</ul></section>
          <section><h5>다른 점</h5><ul>{summary.different.map((sentence, index) => <li key={index}>{sentence}</li>)}</ul></section>
        </div>
      )}
      <h5>지표별 계산 근거</h5>
      {detail.indicators.map((indicator) => (
        <section key={indicator.id} className={styles.indicator} aria-label={`${indicator.name} 계산 근거`}>
          <h6>{indicator.name}</h6>
          <p>지표 거리 Eᵢ <Num value={indicator.e} /> · 명목 비중 <Num value={indicator.nominalWeight} as="share" />
            {indicator.distanceShare != null && <> · 실제 거리 기여 <Num value={indicator.distanceShare} as="share" /></>}
          </p>
          {indicator.scale ? (
            <p>공통 척도 · {indicator.scale.method} · 중앙값 <Num value={indicator.scale.median} /> · 산포 <Num value={indicator.scale.spread} /> · 구간 {indicator.scale.windowStart}~{indicator.scale.windowEnd} ({indicator.scale.months}개월)</p>
          ) : <p>{copy.scaleMissingNote}</p>}
          <div className={styles.tableScroll}>
            <table>
              <caption>비교 시점별 실제 값과 공통 척도 z</caption>
              <thead><tr><th scope="col">질의 시점</th><th scope="col">사례 시점</th><th scope="col">질의값</th><th scope="col">사례값</th><th scope="col">질의 z</th><th scope="col">사례 z</th></tr></thead>
              <tbody>{indicator.comparisonMonths.map((month, index) => (
                <tr key={`${month}-${index}`}>
                  <td>{month}</td><td>{indicator.caseMonths[index]}</td>
                  <td><Num value={indicator.queryRaw[index]} /> {indicator.unit}</td><td><Num value={indicator.caseRaw[index]} /> {indicator.unit}</td>
                  <td><Num value={indicator.queryZ[index]} /></td><td><Num value={indicator.caseZ[index]} /></td>
                </tr>
              ))}</tbody>
            </table>
          </div>
          <div className={styles.era} aria-label={`${indicator.name} 당시 평소 대비 위치`}>
            <strong>당시 평소 대비 위치 · 보조 설명</strong>
            <p>{detail.eraPosition?.[indicator.id]?.sentence ?? copy.eraPositionMissingNote}</p>
          </div>
        </section>
      ))}
      {postPeriodSlot === undefined ? <p>{detail.postPeriod.note}</p> : postPeriodSlot}
    </section>
  )
}

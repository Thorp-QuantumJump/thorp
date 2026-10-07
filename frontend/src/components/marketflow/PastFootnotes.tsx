import type { ReactNode } from 'react'
import * as copy from '@/components/regime/fixedCopy'
import type { RegimeSearchResult } from '@/types/regime'
import { groupReleases, type ReleaseGroup } from './releaseGroups'
import styles from './PastFootnotes.module.css'

/**
 * 종합 비교 바닥의 신뢰 범위 표시와 자료 기준(010 FR-031·FR-037). 이름표 한 열과 내용 한 열의 짧은 줄로 늘어놓는다.
 *
 * 010 검색 화면은 표시마다 안내 상자를 하나씩 세우는데, 종합은 지표가 열일곱이라 상자가 길어져 사례와 그래프보다
 * 눈에 띄었다(2026-10-07). 그래서 결과가 주인공인 자리에서 내려와 각주처럼 선다. 다섯 표시는 모두 문구로 늘 보이고
 * 접히지 않으며, 이름표(dt)와 내용(dd)이 짝으로 읽힌다. 문장은 응답이나 fixedCopy에서만 온다(010 FR-022 ②).
 */
export function PastFootnotes({ result, context }: { result: RegimeSearchResult; context: string }) {
  const omitted = result.comparisonMonths.filter((item) => item.omitted.length > 0)
  const asOf = groupReleases(result.dataAsOf.indicators.map((item) => ({ indicator: item.indicator, month: item.targetMonth, publishedAt: item.publishedAt })))
  const newer = groupReleases(result.dataAsOf.newerReleases)
  return (
    <dl className={styles.footnotes} aria-label={`${context}: 신뢰 범위와 자료 기준`}>
      <Row label="검색 상태" tone={result.status === 'NOT_COMPUTABLE' ? 'error' : 'warning'}>
        <p>{result.statusNote}</p>
        {result.caseLabel ? <p>{result.caseLabel}</p> : null}
        {result.phaseNote ? <p>{result.phaseNote}</p> : null}
      </Row>
      {result.versions.sampleData ? (
        <Row label="예시 자료" tone="warning"><p>{copy.sampleDataNote}</p></Row>
      ) : null}
      {omitted.length > 0 ? (
        <Row label="빠진 비교 시점" tone="warning">
          {omitted.map((item) => <p key={item.indicatorId}>{item.note}</p>)}
        </Row>
      ) : null}
      <Row label="역사 비교"><p>{result.basis.note}</p></Row>
      <Row label="자료 기준 시점">
        {result.dataAsOf.note ? <p>{result.dataAsOf.note}</p> : null}
        {asOf.map((group) => <ReleaseLine key={`${group.month}-${group.publishedAt}`} group={group} />)}
        {result.dataAsOf.revisions.map((item) => (
          <p key={`${item.indicatorId}-${item.month}`}>개정 · {item.indicator} {item.month} · {item.revisedAt}</p>
        ))}
      </Row>
      {newer.length > 0 ? (
        <Row label="더 최신인 개별 발표">
          {newer.map((group) => <ReleaseLine key={`${group.month}-${group.publishedAt}`} group={group} />)}
        </Row>
      ) : null}
    </dl>
  )
}

function Row({ label, tone = 'neutral', children }: { label: string; tone?: 'neutral' | 'warning' | 'error'; children: ReactNode }) {
  return (
    <div className={`${styles.row} ${styles[tone]}`}>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  )
}

/** 대상월·발표일 하나에 그 날짜의 지표 이름을 잇는다. */
function ReleaseLine({ group }: { group: ReleaseGroup }) {
  return (
    <p className={styles.release}>
      <span className={styles.when}>대상월 {group.month ?? '—'} · 발표일 {group.publishedAt ?? '—'}</span>
      <span className={styles.names}>{group.names.join(' · ')}</span>
    </p>
  )
}

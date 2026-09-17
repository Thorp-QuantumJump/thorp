import type { RegimeDataAsOf } from '@/types/regime'
import styles from './DataAsOf.module.css'

/** 결과에 실제로 반영된 대상월과 발표일만 표시한다. */
export function DataAsOf({ data, context }: { data: RegimeDataAsOf; context: string }) {
  return (
    <section className={styles.section} aria-label={`${context}: 자료 기준 시점`}>
      <h3>자료 기준 시점</h3>
      {data.note && <p>{data.note}</p>}
      <dl>
        {data.indicators.map((item) => (
          <div key={item.indicatorId}>
            <dt>{item.indicator}</dt>
            <dd>대상월 {item.targetMonth ?? '—'} · 발표일 {item.publishedAt ?? '—'}</dd>
          </div>
        ))}
      </dl>
      {data.revisions.map((item) => (
        <p key={`${item.indicatorId}-${item.month}`}>
          개정 · {item.indicator} {item.month} · {item.revisedAt}
        </p>
      ))}
    </section>
  )
}

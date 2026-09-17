import type { RegimeCaseDetail, RegimeCaseSummary } from '@/types/regime'
import styles from './FieldBreakdown.module.css'

interface Props {
  detail: RegimeCaseDetail
  summary: RegimeCaseSummary
  fieldNames: Record<string, string>
}

/** 종합 상세에서 서버의 분야 거리와 실제 기여를 함께 보여 준다. */
export function FieldBreakdown({ detail, summary, fieldNames }: Props) {
  return (
    <section className={styles.breakdown} aria-label="분야별 비교">
      <h4>분야별 거리와 기여</h4>
      <p>분야별 거리를 같은 비중으로 합치는 것은 Thorp의 설계 선택입니다.</p>
      <ul>
        {Object.entries(detail.byField).map(([id, distance]) => (
          <li key={id}>
            <strong>{fieldNames[id] ?? id}</strong>
            <span>거리 {distance}</span>
            {detail.contribution?.[id] != null && <span>실제 거리 기여 {detail.contribution[id]}</span>}
          </li>
        ))}
      </ul>
      <div className={styles.reasons}>
        <section><h5>비슷한 점</h5><ul>{summary.similar.map((sentence, index) => <li key={index}>{sentence}</li>)}</ul></section>
        <section><h5>다른 점</h5><ul>{summary.different.map((sentence, index) => <li key={index}>{sentence}</li>)}</ul></section>
      </div>
    </section>
  )
}

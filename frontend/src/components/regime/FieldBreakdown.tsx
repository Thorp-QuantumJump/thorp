import type { RegimeCaseDetail, RegimeCaseSummary } from '@/types/regime'
import * as copy from './fixedCopy'
import { Num } from './Num'
import styles from './FieldBreakdown.module.css'

interface Props {
  detail: RegimeCaseDetail
  summary: RegimeCaseSummary
  fieldNames: Record<string, string>
}

/**
 * 종합 상세에서 서버의 분야(v1) 또는 경제 블록(v3) 거리와 실제 기여를 함께 보여 준다.
 *
 * <p>v3 결과는 `blocks`가 있고 `byField`가 비어 있다. 둘 중 서버가 준 쪽만 그린다.
 */
export function FieldBreakdown({ detail, summary, fieldNames }: Props) {
  const blocks = detail.blocks ?? []
  return (
    <section className={styles.breakdown} aria-label={blocks.length > 0 ? '경제 블록별 비교' : '분야별 비교'}>
      {blocks.length > 0 ? (
        <>
          <h4>경제 블록별 거리와 기여</h4>
          <p>{copy.blockWeightNote}</p>
          <ul>
            {blocks.map((block) => (
              <li key={block.id}>
                <strong>{block.name}</strong>
                <span>거리 <Num value={block.gap} /></span>
                {block.contribution != null && <span>실제 거리 기여 <Num value={block.contribution} as="share" /></span>}
              </li>
            ))}
          </ul>
        </>
      ) : (
        <>
          <h4>분야별 거리와 기여</h4>
          <p>{copy.equalWeightNote}</p>
          <ul>
            {Object.entries(detail.byField).map(([id, distance]) => (
              <li key={id}>
                <strong>{fieldNames[id] ?? id}</strong>
                <span>거리 <Num value={distance} /></span>
                {detail.contribution?.[id] != null && <span>실제 거리 기여 <Num value={detail.contribution[id]} as="share" /></span>}
              </li>
            ))}
          </ul>
        </>
      )}
      <div className={styles.reasons}>
        <section><h5>비슷한 점</h5><ul>{summary.similar.map((sentence, index) => <li key={index}>{sentence}</li>)}</ul></section>
        <section><h5>다른 점</h5><ul>{summary.different.map((sentence, index) => <li key={index}>{sentence}</li>)}</ul></section>
      </div>
    </section>
  )
}

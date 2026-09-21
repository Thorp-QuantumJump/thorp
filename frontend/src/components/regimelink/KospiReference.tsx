import type { RegimeLinkKospi } from '@/types/regimeLink'
import { kospiLabel } from './fixedCopy'

/**
 * 같은 구간의 KOSPI 참고 값 (T063 · FR-013).
 *
 * **두 값만 있다** — 수익률과 최대 하락 폭이다. 전략과의 차이·초과 수익·승패는
 * `RegimeLinkKospi` 타입에 필드가 없어 그릴 수 없다(계약 §6).
 */
export function KospiReference({ kospi }: { kospi: RegimeLinkKospi | null }) {
  const window63 = kospi?.window63 ?? null
  const window21 = kospi?.window21 ?? null
  if (!window63 && !window21) return null

  return (
    <section aria-labelledby="regime-link-kospi-title">
      <h4 id="regime-link-kospi-title">{`KOSPI (${kospiLabel})`}</h4>
      {window63 && (
        <dl>
          <dt>63거래일 수익률</dt>
          <dd>{window63.return}</dd>
          <dt>63거래일 최대 하락 폭</dt>
          <dd>{window63.maxDrawdown}</dd>
        </dl>
      )}
      {window21 && (
        <dl>
          <dt>21거래일 수익률</dt>
          <dd>{window21.return}</dd>
          <dt>21거래일 최대 하락 폭</dt>
          <dd>{window21.maxDrawdown}</dd>
        </dl>
      )}
    </section>
  )
}

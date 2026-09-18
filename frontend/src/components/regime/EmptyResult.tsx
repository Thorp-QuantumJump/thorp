import type { RegimeSearchResult } from '@/types/regime'
import styles from './EmptyResult.module.css'

/** 계산 불가와 후보 없음은 조회 실패가 아니라 조회된 정상 결과다. */
export function EmptyResult({ result }: { result: RegimeSearchResult }) {
  return (
    <section className={styles.empty} aria-labelledby="regime-empty-title">
      <h2 id="regime-empty-title">{result.statusNote}</h2>
    </section>
  )
}

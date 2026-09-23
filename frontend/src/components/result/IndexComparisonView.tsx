import { useEffect, useState } from 'react'
import { fetchResultIndexComparison, normalizeResultError } from '@/api/result'
import { Button } from '@/components/ui/button'
import type { ResultIndexComparison } from '@/types/result'
import styles from './auxiliaryViews.module.css'

type State =
  | { status: 'loading' }
  | { status: 'ready'; data: ResultIndexComparison }
  | { status: 'error'; message: string }

const unavailableReasons: Record<string, string> = {
  INDEX_SERIES_UNAVAILABLE:
    '같은 기간의 KOSPI 자료를 아직 받을 수 없어 비교를 표시하지 않았습니다.',
}

/**
 * 'KOSPI 비교' 탭(FR-017, T077·T098). 탭을 처음 고를 때 붙고 그때 서버가 환산한 KOSPI 보조 계열을 받는다.
 * 서버가 기간 자료를 얻지 못하면 표 대신 그 이유를 이 자리에 둔다 — 값을 만들어 채우지 않는다.
 */
export function IndexComparisonView({ token }: { token: string }) {
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<State>({ status: 'loading' })
  const unavailableReason =
    state.status === 'ready' && !state.data.available
      ? (unavailableReasons[state.data.reason ?? ''] ??
        '지수 비교 자료를 제공할 수 없어 비교를 표시하지 않았습니다.')
      : null

  useEffect(() => {
    const controller = new AbortController()
    setState({ status: 'loading' })
    void fetchResultIndexComparison(token, controller.signal)
      .then((data) => setState({ status: 'ready', data }))
      .catch((error) => {
        if (!controller.signal.aborted) {
          setState({ status: 'error', message: normalizeResultError(error).message })
        }
      })
    return () => controller.abort()
  }, [attempt, token])

  return (
    <section className={styles.view} role="region" aria-label="지수와 비교하기">
      <h2 className={styles.title}>KOSPI와 비교</h2>
      {unavailableReason ? (
        <p className={styles.reason} role="status">
          {unavailableReason}
        </p>
      ) : null}
      {state.status === 'loading' ? (
        <p className={styles.copy}>지수 자료를 불러오는 중입니다.</p>
      ) : null}
      {state.status === 'error' ? (
        <>
          <p className={styles.reason} role="alert">
            {state.message}
          </p>
          <Button
            type="button"
            variant="outline"
            onClick={() => setAttempt((value) => value + 1)}
          >
            다시 불러오기
          </Button>
        </>
      ) : null}
      {state.status === 'ready' && state.data.available ? (
        <>
          <div className={styles.tableScroll}>
            <table className={styles.table} aria-label="환산한 KOSPI 값">
              <thead>
                <tr>
                  <th scope="col">날짜</th>
                  <th scope="col">환산 값</th>
                </tr>
              </thead>
              <tbody>
                {state.data.series.map((point) => (
                  <tr key={point.date}>
                    <td>{point.date}</td>
                    <td>{point.value}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className={styles.note}>지수를 시작 금액으로 환산했습니다.</p>
        </>
      ) : null}
    </section>
  )
}

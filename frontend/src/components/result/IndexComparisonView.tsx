import { useEffect, useId, useState } from 'react'
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

/** 사용자가 연 뒤에만 서버가 환산한 KOSPI 보조 계열을 받는다(T077). */
export function IndexComparisonView({
  token,
  disabled = false,
}: {
  token: string
  disabled?: boolean
}) {
  const panelId = useId()
  const [open, setOpen] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<State>({ status: 'loading' })
  const unavailableReason =
    state.status === 'ready' && !state.data.available
      ? (unavailableReasons[state.data.reason ?? ''] ??
        '지수 비교 자료를 제공할 수 없어 비교를 표시하지 않았습니다.')
      : null

  useEffect(() => {
    if (!open) return
    const controller = new AbortController()
    setState({ status: 'loading' })
    void fetchResultIndexComparison(token, controller.signal)
      .then((data) => {
        setState({ status: 'ready', data })
        if (!data.available) setOpen(false)
      })
      .catch((error) => {
        if (!controller.signal.aborted) {
          setState({ status: 'error', message: normalizeResultError(error).message })
        }
      })
    return () => controller.abort()
  }, [attempt, open, token])

  return (
    <section className={styles.view}>
      <Button
        type="button"
        variant="outline"
        disabled={disabled || unavailableReason !== null}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((value) => !value)}
      >
        지수와 비교하기
      </Button>
      {unavailableReason ? (
        <p className={styles.reason} role="status">
          {unavailableReason}
        </p>
      ) : null}
      {open ? (
        <div id={panelId} className={styles.panel} role="region" aria-label="지수와 비교하기">
          <h2 className={styles.title}>KOSPI와 비교</h2>
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
        </div>
      ) : null}
    </section>
  )
}

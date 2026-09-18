import axios from 'axios'
import { normalizeApiError } from '@/api/client'
import * as copy from './fixedCopy'
import styles from './LoadError.module.css'

interface Props { error: unknown; onRetry: () => void }

export function LoadError({ error, onRetry }: Props) {
  const body: unknown = axios.isAxiosError(error) ? error.response?.data : null
  const message = body && typeof body === 'object' && 'isSuccess' in body &&
    body.isSuccess === false && 'message' in body && typeof body.message === 'string'
    ? body.message
    : normalizeApiError(error).message
  return (
    <section className={styles.error} role="alert">
      <h2>{copy.loadErrorTitle}</h2>
      <p>{message}</p>
      <button type="button" onClick={onRetry}>다시 시도</button>
    </section>
  )
}

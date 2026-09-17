import styles from './ReducedPeriodNotice.module.css'
import { describeReducedPeriod, type ReducedPeriodInput } from './reducedPeriod'

interface ReducedPeriodNoticeProps {
  /** 줄어든 기간과 원인. 원인을 모르면 `null`이다 */
  input: ReducedPeriodInput | null
  /**
   * 기간이 줄었다는 판정은 있으나 원인을 받지 못했을 때 보일 판정 문장. 줄지 않았으면 넘기지 않는다.
   *
   * 원인을 모른다고 안내를 통째로 빼면 SC-004가 요구한 두 자리 가운데 한 곳이 빈다.
   */
  fallbackMessage?: string
}

/**
 * 실행 전 안내(T066, FR-011·SC-004). '분석하기' 바로 위에 둔다.
 *
 * <p>문장은 `describeReducedPeriod` 하나로 짓는다. 고른 카드도 같은 함수를 써서 두 곳이 같은 말을 한다.
 *
 * <p><b>줄지 않았으면 그리지 않는다.</b> "줄지 않았습니다" 같은 문장은 매번 읽히는 소음이 된다.
 * <b>막는 안내가 아니다</b> — 줄어든 기간으로도 분석할 수 있고, 막을지는 최소 평가 기간 판정이 정한다.
 */
export function ReducedPeriodNotice({ input, fallbackMessage }: ReducedPeriodNoticeProps) {
  const message = input !== null ? describeReducedPeriod(input) : fallbackMessage
  if (message === undefined) return null
  return (
    <p className={styles.notice} role="status" aria-label="줄어든 평가 기간">
      {message}
    </p>
  )
}

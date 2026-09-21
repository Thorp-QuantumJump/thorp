import { useId } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import type { ResultConfig } from '@/types/result'
import { OtherConditionsButton } from './OtherConditionsButton'
import styles from './auxiliaryViews.module.css'

/**
 * 결과 화면의 다음 탐색.
 *
 * 011 진입(`/regime-link`)은 열려 있다(T086, entry-handoff §1). **`compare`는 비교 대상이
 * 열람 가능할 때만 싣는다** — 아직 계산 중이거나 실패·만료된 토큰을 넘기면 011이
 * `RESULT_NOT_READY`로 거절한다(계약 §2).
 *
 * 006 내보내기는 화면이 없어 그대로 막아 둔다.
 */
export function NextStepLinks({
  config,
  token,
  compareToken,
  compareReady,
}: {
  config: ResultConfig
  token?: string
  compareToken?: string | null
  compareReady?: boolean
}) {
  const exportReasonId = useId()
  const regimeLinkTo =
    token && compareReady && compareToken
      ? `/regime-link?base=${token}&compare=${compareToken}`
      : `/regime-link?base=${token ?? ''}`

  return (
    <section className={styles.nextSteps} aria-labelledby="next-step-title">
      <h2 id="next-step-title" className={styles.title}>
        다음 탐색
      </h2>
      <div className={styles.actions}>
        <OtherConditionsButton config={config} />
        {token ? (
          <Link to={regimeLinkTo}>경제환경이 비슷했던 과거 보기</Link>
        ) : (
          <Button type="button" variant="outline" disabled>
            경제환경이 비슷했던 과거 보기
          </Button>
        )}
        <Button
          type="button"
          variant="outline"
          disabled
          aria-describedby={exportReasonId}
        >
          기록으로 내보내기
        </Button>
      </div>
      <div className={styles.disabledReason}>
        <span id={exportReasonId}>
          결과 기록 내보내기는 006 화면이 아직 없어 사용할 수 없습니다.
        </span>
      </div>
    </section>
  )
}

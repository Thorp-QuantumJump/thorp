import { useId } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/auth/auth-context'
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
 * 006 내 기록은 **로그인한 사용자에게만 열린다**(spec 006 T045a, FR-020). 기록은 식별된 사용자 단위로만
 * 있으므로 비로그인에게는 014의 안내와 함께 막는다 — 눌러 본 뒤에 로그인 화면으로 보내지 않는다.
 * 여는 주소는 목록(`/records?resultToken=`)이고, 그 결과의 줄로 초점을 옮기는 것은 006 화면이 한다.
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
  const { user } = useAuth()
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
        {user && token ? (
          <Button asChild variant="outline">
            <Link to={`/records?resultToken=${encodeURIComponent(token)}`}>기록으로 내보내기</Link>
          </Button>
        ) : (
          <Button type="button" variant="outline" disabled aria-describedby={exportReasonId}>
            기록으로 내보내기
          </Button>
        )}
      </div>
      <div className={styles.disabledReason}>
        {user && token ? null : (
          <span id={exportReasonId}>
            기록으로 내보내려면 로그인이 필요합니다. 기록은 로그인한 사용자에게만 남습니다.
          </span>
        )}
      </div>
    </section>
  )
}

import { useId } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/auth/auth-context'
import { Button } from '@/components/ui/button'
import type { ResultConfig } from '@/types/result'
import { OtherConditionsButton } from './OtherConditionsButton'
import styles from './auxiliaryViews.module.css'

/**
 * 현재 구현된 다음 탐색만 연결하고, 아직 계약·화면이 없는 011은 이유와 함께 막는다(T079).
 *
 * <p><b>내 기록은 로그인한 사용자에게만 열린다</b>(spec 006 T045a, FR-020). 기록은 식별된 사용자 단위로만 있으므로
 * 비로그인에게는 014의 안내 문구와 함께 막는다 — 눌러 본 뒤에 로그인 화면으로 보내지 않는다.
 *
 * <p>여는 주소는 목록(`/records?resultToken=`)이다. 이 결과의 줄로 초점을 옮기고 '더 보기'를 펼치는 것은 006 화면이
 * 한다 — 여기서 그 줄의 식별자를 알 방법이 없고, 알아야 할 이유도 없다.
 */
export function NextStepLinks({ config, resultToken }: { config: ResultConfig; resultToken: string }) {
  const regimeReasonId = useId()
  const exportReasonId = useId()
  const { user } = useAuth()

  return (
    <section className={styles.nextSteps} aria-labelledby="next-step-title">
      <h2 id="next-step-title" className={styles.title}>
        다음 탐색
      </h2>
      <div className={styles.actions}>
        <OtherConditionsButton config={config} />
        <Button
          type="button"
          variant="outline"
          disabled
          aria-describedby={regimeReasonId}
        >
          경제환경이 비슷했던 과거 보기
        </Button>
        {user ? (
          <Button asChild variant="outline">
            <Link to={`/records?resultToken=${encodeURIComponent(resultToken)}`}>
              기록으로 내보내기
            </Link>
          </Button>
        ) : (
          <Button type="button" variant="outline" disabled aria-describedby={exportReasonId}>
            기록으로 내보내기
          </Button>
        )}
      </div>
      <div className={styles.disabledReason}>
        <span id={regimeReasonId}>
          경제환경 탐색은 결과 조건을 넘기는 011 연결 계약이 아직 없어 사용할 수 없습니다.
        </span>
        {user ? null : (
          <span id={exportReasonId}>
            기록으로 내보내려면 로그인이 필요합니다. 기록은 로그인한 사용자에게만 남습니다.
          </span>
        )}
      </div>
    </section>
  )
}

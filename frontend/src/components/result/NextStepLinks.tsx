import { useId } from 'react'
import { Button } from '@/components/ui/button'
import type { ResultConfig } from '@/types/result'
import { OtherConditionsButton } from './OtherConditionsButton'
import styles from './auxiliaryViews.module.css'

/** 현재 구현된 다음 탐색만 연결하고, 아직 계약·화면이 없는 011·006은 이유와 함께 막는다(T079). */
export function NextStepLinks({ config }: { config: ResultConfig }) {
  const regimeReasonId = useId()
  const exportReasonId = useId()

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
        <span id={regimeReasonId}>
          경제환경 탐색은 결과 조건을 넘기는 011 연결 계약이 아직 없어 사용할 수 없습니다.
        </span>
        <span id={exportReasonId}>
          결과 기록 내보내기는 006 화면이 아직 없어 사용할 수 없습니다.
        </span>
      </div>
    </section>
  )
}

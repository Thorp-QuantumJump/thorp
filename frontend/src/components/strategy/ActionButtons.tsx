import styles from './ActionButtons.module.css'

/** 행동 하나를 지금 누를 수 있는가와, 없다면 왜인가. */
export type ActionAvailability = {
  disabled: boolean
  /** 누를 수 없는 이유 한 문장. 누를 수 있으면 비운다 */
  reason?: string
}

interface ActionButtonsProps {
  analyze: ActionAvailability & {
    /** 요청을 보내는 중이면 다시 누를 수 없다 */
    busy?: boolean
  }
  autoCompare: ActionAvailability
  onAnalyze: () => void
  onAutoCompare: () => void
}

/**
 * '분석하기'와 '설정 조합 자동 비교' 두 행동 버튼(FR-002, FR-013).
 *
 * <p><b>같은 크기, 같은 모양이다.</b> constitution 원칙 II의 예외 조항(3.4.0,
 * S15P21E205-253, 2026-09-15 승인)이 이 화면에 같은 크기의 버튼 두 개를 허용했다. 어느 쪽이 주된
 * 행동이라는 시각적 위계를 두지 않는다.
 *
 * <p><b>누를 수 없는 이유를 함께 전달한다.</b> `disabled` 대신 `aria-disabled`를 쓰고 이유 문장을
 * `aria-describedby`로 잇는다 — `disabled`를 걸면 키보드 사용자가 버튼에 닿지 못해 <b>왜</b> 막혔는지
 * 읽을 방법이 사라진다(NFR-001). 눌러도 동작하지 않게 막는 것은 처리부에서 한다. 이유는 호출자가
 * 정한다(등록표 없음·전략 미선택·규칙 위반·워밍업 미충족·분석 조건 버전 없음).
 */
export function ActionButtons({ analyze, autoCompare, onAnalyze, onAutoCompare }: ActionButtonsProps) {
  const analyzeBlocked = analyze.disabled || analyze.busy === true
  const analyzeReasonId = analyze.disabled && analyze.reason ? 'action-analyze-reason' : undefined
  const autoCompareReasonId =
    autoCompare.disabled && autoCompare.reason ? 'action-auto-compare-reason' : undefined

  return (
    <div className={styles.actions}>
      <div className={styles.buttons}>
        <button
          type="button"
          className={styles.button}
          aria-disabled={analyzeBlocked}
          aria-busy={analyze.busy === true}
          aria-describedby={analyzeReasonId}
          data-action="analyze"
          onClick={() => {
            if (!analyzeBlocked) onAnalyze()
          }}
        >
          {analyze.busy ? '분석 요청을 보내는 중' : '분석하기'}
        </button>
        <button
          type="button"
          className={styles.button}
          aria-disabled={autoCompare.disabled}
          aria-describedby={autoCompareReasonId}
          data-action="auto-compare"
          onClick={() => {
            if (!autoCompare.disabled) onAutoCompare()
          }}
        >
          설정 조합 자동 비교
        </button>
      </div>

      {(analyzeReasonId || autoCompareReasonId) && (
        <div className={styles.reasons}>
          {analyzeReasonId && (
            <p id={analyzeReasonId} className={styles.reason}>
              <span className={styles.reasonLabel}>분석하기</span>
              {analyze.reason}
            </p>
          )}
          {autoCompareReasonId && (
            <p id={autoCompareReasonId} className={styles.reason}>
              <span className={styles.reasonLabel}>설정 조합 자동 비교</span>
              {autoCompare.reason}
            </p>
          )}
        </div>
      )}
    </div>
  )
}

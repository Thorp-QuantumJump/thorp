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
 * '분석하기'와 '설정 조합 자동 비교' 두 행동(FR-002, FR-013).
 *
 * <p><b>'분석하기'가 주된 행동이다.</b> 한때 두 버튼을 같은 크기로 두었으나, 어느 쪽을 눌러야
 * 하는지 화면이 말해 주지 않아 사용자가 멈춰 서는 문제가 있었다. 자동 비교는 설정을 어떻게 잡을지
 * 모를 때 찾는 우회로이므로 옆에 두되 무게를 낮춘다(constitution 원칙 II).
 *
 * <p><b>자동 비교를 감추지는 않는다.</b> 그 경로가 없으면 같은 결과를 얻으려고 설정을 하나씩
 * 바꿔 가며 반복 실행해야 한다 — 예외 조항이 원래 지키려던 것이 그 사실이다. 늘 보이는 자리에
 * 두고 화살표로 다음 화면으로 이어짐을 알린다.
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
      <div className={styles.bar}>
        <button
          type="button"
          className={styles.secondary}
          aria-disabled={autoCompare.disabled}
          aria-describedby={autoCompareReasonId}
          data-action="auto-compare"
          onClick={() => {
            if (!autoCompare.disabled) onAutoCompare()
          }}
        >
          설정 조합 자동 비교
          <span aria-hidden="true">→</span>
        </button>
        <button
          type="button"
          className={styles.primary}
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

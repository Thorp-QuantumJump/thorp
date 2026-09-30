import styles from './ActionButtons.module.css'

/** 행동 하나를 지금 누를 수 있는가와, 없다면 왜인가. */
export type ActionAvailability = {
  disabled: boolean
  /** 누를 수 없는 이유 한 문장. 누를 수 있으면 비운다 */
  reason?: string
}

/**
 * '분석하기'가 막힌 이유 문장의 id. 단추(`StepOrbButton`)는 카드 밖 원으로 화면이 그리고, 이유는 이 컴포넌트가
 * 카드 아래에 적는다. 둘은 `aria-describedby`로 이어져야 하므로 id를 한 곳에서 정한다.
 */
export const ANALYZE_REASON_ID = 'action-analyze-reason'

interface ActionButtonsProps {
  /** '분석하기'를 누를 수 없는 이유. 누를 수 있으면 비운다. */
  analyzeReason?: string
  autoCompare: ActionAvailability
  onAutoCompare: () => void
}

/**
 * 실행 영역의 우회로 '설정 조합 자동 비교'와, 두 행동이 막힌 이유(FR-002, FR-013).
 *
 * <p><b>'분석하기'가 주된 행동이다.</b> 그 단추는 카드 오른쪽 테두리에 걸친 원으로 화면이 따로 그린다 —
 * 투자 대상 고르기의 다음 단계 단추와 같은 모양이라, 진단 흐름의 마지막 걸음이 어디인지 화면마다 같은 자리에서
 * 읽힌다. 한때 두 버튼을 같은 크기로 나란히 두었으나, 어느 쪽을 눌러야 하는지 화면이 말해 주지 않아 사용자가
 * 멈춰 서는 문제가 있었다(constitution 원칙 II).
 *
 * <p><b>자동 비교를 감추지는 않는다.</b> 설정을 어떻게 잡을지 모를 때 찾는 우회로이므로 카드 아래 늘 보이는
 * 자리에 글자와 화살표만으로 두어 무게를 낮춘다. 그 경로가 없으면 같은 결과를 얻으려고 설정을 하나씩 바꿔
 * 가며 반복 실행해야 한다.
 *
 * <p><b>누를 수 없는 이유를 함께 전달한다.</b> `disabled` 대신 `aria-disabled`를 쓰고 이유 문장을
 * `aria-describedby`로 잇는다 — `disabled`를 걸면 키보드 사용자가 버튼에 닿지 못해 <b>왜</b> 막혔는지
 * 읽을 방법이 사라진다(NFR-001). 눌러도 동작하지 않게 막는 것은 처리부에서 한다. 이유는 호출자가
 * 정한다(등록표 없음·전략 미선택·규칙 위반·워밍업 미충족·분석 조건 버전 없음).
 */
export function ActionButtons({ analyzeReason, autoCompare, onAutoCompare }: ActionButtonsProps) {
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
      </div>

      {(analyzeReason || autoCompareReasonId) && (
        <div className={styles.reasons}>
          {analyzeReason && (
            <p id={ANALYZE_REASON_ID} className={styles.reason}>
              <span className={styles.reasonLabel}>분석하기</span>
              {analyzeReason}
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

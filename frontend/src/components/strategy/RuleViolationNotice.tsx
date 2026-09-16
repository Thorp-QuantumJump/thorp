import type { ValidationRule } from '@/types/strategyRegistry'
import styles from './DetailSettingsPanel.module.css'

interface RuleViolationNoticeProps {
  /** `evaluateRules`가 돌려준 위반 규칙. 비어 있으면 아무것도 그리지 않는다 */
  violations: readonly ValidationRule[]
}

/**
 * 지금 설정이 어기는 유효성 규칙을 알린다(FR-008, quickstart 5-1).
 *
 * <p><b>등록표의 `detail`을 그대로 보인다. 화면이 문장을 짓지 않는다.</b> 규칙이 무엇을 뜻하는지는
 * 등록표만 알고, 화면이 사유 코드로 문구를 고르면 그것이 전략 지식의 중복이다(research R7-1).
 *
 * <p>값을 바꾸는 즉시 나타나야 하므로 `role="alert"`다. '분석하기'를 누른 뒤에 알리면 늦다.
 */
export function RuleViolationNotice({ violations }: RuleViolationNoticeProps) {
  if (violations.length === 0) return null

  return (
    <div className={styles.violations} role="alert">
      <ul>
        {violations.map((rule) => (
          <li key={rule.code}>{rule.detail}</li>
        ))}
      </ul>
    </div>
  )
}

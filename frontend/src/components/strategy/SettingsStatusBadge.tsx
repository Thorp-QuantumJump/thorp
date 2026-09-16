import styles from './SettingsStatusBadge.module.css'

interface SettingsStatusBadgeProps {
  /** 선택 전략의 모든 축 값이 등록표 기본값과 같은가. 계산은 `useStrategySettings.isDefault`가 한다 */
  isDefault: boolean
}

/**
 * '기본 설정' / '설정을 바꿈' 상태 표시(FR-002).
 *
 * <p><b>누를 수 없다. 토글이 아니다.</b> 사용자가 켜고 끄는 항목이 아니라 지금 설정이 등록표의
 * 기본값 그대로인지를 알리는 표시다. 값을 하나라도 바꾸면 '설정을 바꿈'으로, 모두 되돌리면
 * '기본 설정'으로 저절로 돌아온다. 그래서 `button`이 아니라 `role="status"`다.
 *
 * <p>옆의 한 문장은 FR-004·원칙 III다 — 기본값은 편의용 출발점이며 최적값이나 투자 권고가 아니다.
 * 상태를 색으로만 알리지 않고 낱말로 적는다(NFR-002).
 */
export function SettingsStatusBadge({ isDefault }: SettingsStatusBadgeProps) {
  return (
    <div className={styles.badge} role="status">
      <span className={styles.state} data-state={isDefault ? 'default' : 'changed'}>
        {isDefault ? '기본 설정' : '설정을 바꿈'}
      </span>
      <span className={styles.note}>기본값은 편의용 출발점이며 최적값이 아닙니다.</span>
    </div>
  )
}

import { writeStoredView, type LearningView } from './viewStorage'
import styles from './ViewToggle.module.css'

interface ViewToggleProps {
  view: LearningView
  onChange: (view: LearningView) => void
}

const OPTIONS: { view: LearningView; label: string }[] = [
  { view: 'curriculum', label: '커리큘럼' },
  { view: 'list', label: '목록' },
]

/**
 * 커리큘럼 보기와 목록 보기 전환(013 FR-023). 눌린 상태를 `aria-pressed`로 알리는 두 단추다.
 *
 * <p>고른 보기는 그 탭 동안 기억한다(`viewStorage`). 학습 기록이 아니라 화면 상태라 서버에 보내지 않는다.
 */
export function ViewToggle({ view, onChange }: ViewToggleProps) {
  return (
    <div className={styles.toggle} role="group" aria-label="보기">
      {OPTIONS.map((option) => (
        <button
          key={option.view}
          type="button"
          className={styles.button}
          aria-pressed={view === option.view}
          onClick={() => {
            if (option.view === view) return
            writeStoredView(option.view)
            onChange(option.view)
          }}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

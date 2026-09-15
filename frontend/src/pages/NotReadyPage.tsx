import { NotReadyNotice } from '@/components/common/NotReadyNotice'
import { AppHeader } from '@/components/navigation/AppHeader'
import styles from './NotReadyPage.module.css'

/**
 * 아직 만들지 않은 메뉴를 정상 화면으로 연다.
 *
 * 메뉴를 숨기는 대신 이 화면을 열어 한 문장으로 상태를 알린다. 다시 눌러도 결과가 달라지지
 * 않는 상태이므로 행동 버튼을 두지 않고, 첫 화면으로 돌아가는 길은 기존 공통 내비게이션이
 * 준다. 문구는 임시이며 UI 결정 항목이 정해질 때 확정한다.
 */
export function NotReadyPage() {
  return (
    <div className={styles.page}>
      <AppHeader />
      <main className={styles.main} aria-labelledby="not-ready-title">
        <h1 id="not-ready-title" className={styles.title}>
          준비 중
        </h1>
        <NotReadyNotice className={styles.notice} message="이 메뉴는 아직 준비 중입니다." />
      </main>
    </div>
  )
}

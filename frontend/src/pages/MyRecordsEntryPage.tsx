import { ArrowRight, FolderClock } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/auth/auth-context'
import { AppHeader } from '@/components/navigation/AppHeader'
import styles from './MyRecordsEntryPage.module.css'

/** spec 006의 기록 목록을 구현하지 않고 인증된 내 기록 진입 경계만 제공한다. */
export function MyRecordsEntryPage() {
  const { user } = useAuth()

  return (
    <div className={styles.page}>
      <AppHeader />
      <main className={styles.main} aria-labelledby="records-title">
        <div className={styles.eyebrow}>
          <FolderClock size={17} aria-hidden="true" />
          MY RECORDS
        </div>
        <h1 id="records-title">{user?.nickname}님의 내 기록</h1>
        <p>
          로그인 상태가 확인되었습니다. 저장된 분석 기록과 다시보기 기능은 내 기록 기능에서
          이어집니다.
        </p>
        <div className={styles.actions}>
          <Link to="/backtest">
            전략 검증하기
            <ArrowRight size={16} aria-hidden="true" />
          </Link>
          <Link to="/home">시장 흐름 보기</Link>
        </div>
        <p className={styles.scopeNote}>
          이 화면은 사용자별 기록을 보호하는 진입점이며, 기록 목록은 spec 006 범위입니다.
        </p>
      </main>
    </div>
  )
}

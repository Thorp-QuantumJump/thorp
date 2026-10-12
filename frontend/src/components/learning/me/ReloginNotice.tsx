import { Link } from 'react-router-dom'
import { useLoginReturn } from '@/auth/useLoginReturn'
import styles from './ReloginNotice.module.css'

/**
 * 로그인이 만료된 동안 배우기 목록과 '나의 학습' 화면의 기록 자리(013 research R33 `expired` 줄, 014 FR-012).
 *
 * <p>비로그인처럼 기록을 보이지 않고, 로그인 안내 자리에 재로그인 안내를 둔다. 다시 로그인하면 014의 `useLoginReturn`이
 * 적어 둔 이 화면으로 돌아온다(FR-020, research R34). 만료를 빈 기록이나 '안 읽음'으로 보이지 않는다(014 FR-012).
 * 글 끝의 같은 안내('…이 글로 돌아옵니다')는 `ArticleEnd`가 둔다.
 */
export function ReloginNotice() {
  const rememberReturn = useLoginReturn()
  return (
    <p className={styles.notice} data-relogin-notice="">
      <span>로그인이 만료되었습니다. 다시 로그인하면 이 화면으로 돌아옵니다.</span>
      <Link className={styles.link} to="/login" onClick={rememberReturn}>
        다시 로그인
      </Link>
    </p>
  )
}

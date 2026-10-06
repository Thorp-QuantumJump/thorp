import { Link } from 'react-router-dom'
import type { PendingSocialState } from '@/auth/usePendingSocial'
import { AuthStatus } from './AuthStatus'
import formStyles from './AuthForm.module.css'

/**
 * 대기 상태를 아직 못 읽었거나 쓸 수 없을 때의 화면 내용.
 *
 * <p>만료·이미 사용은 오류가 아니라 "처음부터 다시"로 안내한다(US6 시나리오 6). 보여 줄 대기 상태가
 * 없으므로 입력 칸을 그리지 않는다.
 */
export function SocialPendingFallback({
  state,
  onRetry,
}: {
  state: Exclude<PendingSocialState, { status: 'ready' }>
  onRetry: () => void
}) {
  if (state.status === 'loading') {
    return <AuthStatus message="소셜 로그인 진행 상태를 확인하고 있습니다." tone="info" />
  }
  if (state.status === 'missing') {
    return (
      <>
        <AuthStatus
          message="소셜 로그인 시간이 지났습니다. 처음부터 다시 시도해 주세요."
          tone="error"
        />
        <Link className={formStyles.submitButton} to="/login">
          로그인 화면으로
        </Link>
      </>
    )
  }
  return (
    <>
      <AuthStatus message={state.message} tone="error" />
      <button type="button" className={formStyles.secondaryButton} onClick={onRetry}>
        다시 확인하기
      </button>
    </>
  )
}

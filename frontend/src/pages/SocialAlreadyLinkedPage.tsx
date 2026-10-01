import { UserCheck } from 'lucide-react'
import { Link, useSearchParams } from 'react-router-dom'
import { SocialAuthLayout } from '@/components/auth/SocialAuthLayout'
import { readProviderParam, socialProviderNames } from '@/components/auth/socialProviders'
import formStyles from '@/components/auth/AuthForm.module.css'
import styles from './SocialNoticePage.module.css'

/**
 * 기존 계정에 같은 제공자의 다른 계정이 이미 연결돼 있을 때의 안내(`/auth/social/already-linked`, US7 시나리오 8).
 *
 * <p>연결 확인을 시작하지 않는다 — 한 계정에는 제공자마다 하나만 연결되므로 확인해도 연결할 수 없다.
 * 이미 연결된 그 계정이나 다른 로그인 방법으로 들어가도록 안내한다. 아무것도 만들거나 연결하지 않았다.
 */
export function SocialAlreadyLinkedPage() {
  const [searchParams] = useSearchParams()
  const provider = readProviderParam(searchParams.get('provider'))
  const providerName = provider ? socialProviderNames[provider] : '소셜'

  return (
    <SocialAuthLayout
      eyebrow="ALREADY LINKED"
      title={`이미 다른 ${providerName} 계정과 연결돼 있어요.`}
      description="이 Thorp 계정에는 다른 소셜 계정이 연결되어 있어, 방금 로그인한 계정을 더 연결할 수 없습니다."
      panelTitle="다른 방법으로 로그인"
    >
      <div className={styles.notice}>
        <UserCheck className={styles.icon} size={28} aria-hidden="true" />
        <p>
          처음 연결한 {providerName} 계정으로 로그인하거나, 이메일과 비밀번호로 로그인해 주세요.
        </p>
      </div>
      <div className={styles.actions}>
        <Link className={formStyles.submitButton} to="/login">
          로그인 화면으로
        </Link>
      </div>
    </SocialAuthLayout>
  )
}

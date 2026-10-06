import { MailWarning } from 'lucide-react'
import { Link, useSearchParams } from 'react-router-dom'
import { startSocialLogin } from '@/api/socialAuth'
import { SocialAuthLayout } from '@/components/auth/SocialAuthLayout'
import { ProviderMark } from '@/components/auth/SocialLoginButtons'
import { readProviderParam, socialProviderNames } from '@/components/auth/socialProviders'
import formStyles from '@/components/auth/AuthForm.module.css'
import socialStyles from '@/components/auth/SocialLoginButtons.module.css'
import styles from './SocialNoticePage.module.css'

/**
 * 제공자에게서 이메일을 받지 못했을 때의 거절 화면(`/auth/social/email-required`, FR-022).
 *
 * <p>계정을 만들거나 로그인하지 않았다. 다음 행동은 셋이다 — 이메일 제공에 다시 동의하는 소셜 로그인,
 * 이메일로 가입하기, 로그인 화면으로 돌아가기(SC-014).
 */
export function SocialEmailRequiredPage() {
  const [searchParams] = useSearchParams()
  const provider = readProviderParam(searchParams.get('provider'))
  const providerName = provider ? socialProviderNames[provider] : '소셜'

  return (
    <SocialAuthLayout
      eyebrow="EMAIL REQUIRED"
      title="이메일 정보가 필요해요."
      description="Thorp는 계정을 구분하기 위해 이메일이 필요합니다. 이메일을 받지 못해 가입이나 로그인을 진행하지 않았습니다."
      panelTitle="이메일 제공 동의"
    >
      <div className={styles.notice}>
        <MailWarning className={styles.icon} size={28} aria-hidden="true" />
        <p>
          {providerName} 로그인에서 <strong>이메일 제공</strong>에 동의해 주세요. 동의 화면을 다시
          열어 드립니다.
        </p>
      </div>
      <div className={styles.actions}>
        {provider ? (
          <button
            type="button"
            className={socialStyles.button}
            data-provider={provider}
            onClick={() => startSocialLogin(provider, { reprompt: true })}
          >
            <ProviderMark provider={provider} />
            <span>다시 동의하고 {providerName}로 계속하기</span>
          </button>
        ) : null}
        <Link className={formStyles.secondaryButton} to="/signup">
          이메일로 가입하기
        </Link>
        <Link className={styles.textLink} to="/login">
          로그인 화면으로 돌아가기
        </Link>
      </div>
    </SocialAuthLayout>
  )
}

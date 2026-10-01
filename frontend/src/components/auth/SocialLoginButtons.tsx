import { useEffect, useState } from 'react'
import { fetchSocialProviders, startSocialLogin } from '@/api/socialAuth'
import type { SocialProviderId } from '@/types/auth'
import { socialProviderIds } from '@/types/auth'
import { socialProviderNames } from './socialProviders'
import styles from './SocialLoginButtons.module.css'

type SocialLoginButtonsProps = {
  /** 버튼 묶음 위 구분선의 문구. 로그인·가입 화면이 각자 정한다. */
  dividerLabel?: string
  disabled?: boolean
}

/**
 * 카카오·네이버·Google 로그인 버튼(spec 014 FR-020).
 *
 * <p>서버가 켜 둔 제공자만 그린다 — 자격 증명이 없는 제공자의 버튼을 누르면 404로 끝나기 때문이다.
 * 목록을 받기 전과 받지 못했을 때는 아무것도 그리지 않는다. 이메일 로그인은 그대로 쓸 수 있다.
 *
 * <p>모양은 각 제공자의 디자인 가이드를 따른다(카카오 노랑, 네이버 초록, Google 흰 바탕).
 */
export function SocialLoginButtons({ dividerLabel = '또는', disabled }: SocialLoginButtonsProps) {
  const [providers, setProviders] = useState<SocialProviderId[]>([])

  useEffect(() => {
    const controller = new AbortController()
    void fetchSocialProviders(controller.signal).then((enabled) => {
      if (!controller.signal.aborted) setProviders(enabled)
    })
    return () => controller.abort()
  }, [])

  if (providers.length === 0) return null

  // 서버 응답 순서가 아니라 화면이 정한 순서로 그린다.
  const ordered = socialProviderIds.filter((id) => providers.includes(id))

  return (
    <div className={styles.group}>
      <p className={styles.divider}>
        <span>{dividerLabel}</span>
      </p>
      <ul className={styles.list}>
        {ordered.map((provider) => (
          <li key={provider}>
            <button
              type="button"
              className={styles.button}
              data-provider={provider}
              disabled={disabled}
              onClick={() => startSocialLogin(provider)}
            >
              <ProviderMark provider={provider} />
              <span>{socialProviderNames[provider]}로 계속하기</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** 제공자 표식. 장식이라 보조 기술에는 숨기고 버튼 문구가 이름을 전한다. */
export function ProviderMark({ provider }: { provider: SocialProviderId }) {
  if (provider === 'kakao') {
    return (
      <svg className={styles.mark} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path
          fill="currentColor"
          d="M12 3.5c-5.25 0-9.5 3.3-9.5 7.38 0 2.62 1.75 4.92 4.38 6.23l-.92 3.37c-.08.3.26.54.52.37l4.03-2.66c.49.05.99.08 1.49.08 5.25 0 9.5-3.3 9.5-7.39S17.25 3.5 12 3.5Z"
        />
      </svg>
    )
  }
  if (provider === 'naver') {
    return (
      <svg className={styles.mark} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path fill="currentColor" d="M15.56 12.84 8.2 2.5H2.5v19h5.94V11.16l7.36 10.34h5.7v-19h-5.94Z" />
      </svg>
    )
  }
  return (
    <svg className={styles.mark} viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path
        fill="#4285F4"
        d="M23.52 12.27c0-.85-.08-1.67-.22-2.45H12v4.64h6.46a5.52 5.52 0 0 1-2.4 3.62v3h3.88c2.27-2.09 3.58-5.17 3.58-8.81Z"
      />
      <path
        fill="#34A853"
        d="M12 24c3.24 0 5.96-1.07 7.94-2.92l-3.88-3c-1.07.72-2.45 1.15-4.06 1.15-3.12 0-5.77-2.11-6.71-4.95H1.28v3.1A12 12 0 0 0 12 24Z"
      />
      <path
        fill="#FBBC05"
        d="M5.29 14.28a7.2 7.2 0 0 1 0-4.56v-3.1H1.28a12 12 0 0 0 0 10.76l4.01-3.1Z"
      />
      <path
        fill="#EA4335"
        d="M12 4.77c1.76 0 3.34.61 4.59 1.8l3.44-3.44A11.97 11.97 0 0 0 12 0 12 12 0 0 0 1.28 6.62l4.01 3.1C6.23 6.88 8.88 4.77 12 4.77Z"
      />
    </svg>
  )
}

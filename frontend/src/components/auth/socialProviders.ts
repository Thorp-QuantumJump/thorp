import type { SocialProviderId } from '@/types/auth'

/** 화면에 쓰는 제공자 이름. 버튼 문구와 안내 문장이 같은 이름을 쓴다. */
export const socialProviderNames: Record<SocialProviderId, string> = {
  kakao: '카카오',
  naver: '네이버',
  google: 'Google',
}

export function readProviderParam(value: string | null): SocialProviderId | null {
  return value === 'kakao' || value === 'naver' || value === 'google' ? value : null
}

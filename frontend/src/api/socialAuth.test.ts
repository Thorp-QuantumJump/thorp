import { describe, expect, it } from 'vitest'
import { socialLoginStartUrl } from './socialAuth'

/** 소셜 로그인 시작 주소(contracts `startSocialLogin`). 인가 코드나 token은 이 주소에 실리지 않는다. */
describe('socialLoginStartUrl', () => {
  it('API 기준 주소 아래의 시작 경로를 만든다', () => {
    expect(socialLoginStartUrl('kakao')).toBe('/api/auth/oauth2/authorization/kakao')
  })

  it('이메일 재동의와 연결 확인은 쿼리로 싣는다', () => {
    expect(socialLoginStartUrl('naver', { reprompt: true })).toBe(
      '/api/auth/oauth2/authorization/naver?reprompt=true',
    )
    expect(socialLoginStartUrl('google', { intent: 'link' })).toBe(
      '/api/auth/oauth2/authorization/google?intent=link',
    )
  })
})

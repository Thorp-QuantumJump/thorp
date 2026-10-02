import { beforeEach, describe, expect, it } from 'vitest'
import { saveReturnTarget } from '@/auth/returnTarget'
import { socialLoginStartUrl } from './socialAuth'

/** 소셜 로그인 시작 주소(contracts `startSocialLogin`). 인가 코드나 token은 이 주소에 실리지 않는다. */
describe('socialLoginStartUrl', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('적어 둔 복귀 위치를 returnTo로 실어 서버가 콜백 뒤 바로 그 자리로 보내게 한다', () => {
    saveReturnTarget('/results/BT-1?tab=cost#summary')

    expect(socialLoginStartUrl('kakao')).toBe(
      '/api/auth/oauth2/authorization/kakao?returnTo=%2Fresults%2FBT-1%3Ftab%3Dcost%23summary',
    )
    // 가입이 필요한 경우 가입 뒤 복귀는 아직 저장소 값을 쓰므로 여기서 지우지 않는다.
    expect(window.sessionStorage.getItem('thorp.auth.returnTarget')).toBe(
      '/results/BT-1?tab=cost#summary',
    )
  })

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

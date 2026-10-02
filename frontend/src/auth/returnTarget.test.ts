import { beforeEach, describe, expect, it } from 'vitest'
import {
  DEFAULT_RETURN_TARGET,
  consumeReturnTarget,
  peekReturnTarget,
  saveReturnTarget,
} from './returnTarget'

describe('returnTarget', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
  })

  it('내 기록의 path, query, hash를 탭 새로고침 동안 보존하고 한 번만 소비한다', () => {
    saveReturnTarget('/records/analysis-42?view=trades#loss')

    expect(consumeReturnTarget()).toBe('/records/analysis-42?view=trades#loss')
    expect(consumeReturnTarget()).toBe(DEFAULT_RETURN_TARGET)
  })

  it('읽기만 하는 조회는 값을 남기고, 없으면 null이다', () => {
    expect(peekReturnTarget()).toBeNull()

    saveReturnTarget('/records/analysis-42?view=trades#loss')

    expect(peekReturnTarget()).toBe('/records/analysis-42?view=trades#loss')
    expect(consumeReturnTarget()).toBe('/records/analysis-42?view=trades#loss')
  })

  it.each([
    'https://evil.example/records',
    '//evil.example/records',
    'records/analysis-42',
    '/login',
    '/login?next=/records',
    '/signup',
    '/signup/complete',
    // 소셜 로그인 뒤 화면은 대기 상태에 묶여 있어 돌아갈 자리가 아니다(spec 014).
    '/signup/social',
    '/auth/social/link',
    '/auth/social/complete',
  ])('외부·상대·인증 화면 복귀 위치 %s를 기본 화면으로 대체한다', (target) => {
    saveReturnTarget(target)

    expect(consumeReturnTarget()).toBe(DEFAULT_RETURN_TARGET)
  })

  it('저장소에 주입된 안전하지 않은 값도 소비 시 다시 검증하고 제거한다', () => {
    window.sessionStorage.setItem('thorp.auth.returnTarget', '//evil.example')

    expect(consumeReturnTarget()).toBe(DEFAULT_RETURN_TARGET)
    expect(window.sessionStorage.length).toBe(0)
  })
})

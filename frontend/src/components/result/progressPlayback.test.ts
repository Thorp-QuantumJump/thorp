import { describe, expect, it } from 'vitest'
import { drainCount } from './progressPlayback'

describe('진행 곡선 재생 속도(drainCount)', () => {
  it('대기열이 비어 있으면 아무것도 내보내지 않는다', () => {
    expect(drainCount(0, false)).toBe(0)
    expect(drainCount(-1, false)).toBe(0)
  })

  it('대기열이 줄도록 적어도 한 점은 내보낸다', () => {
    // 1/12 는 0 으로 내림되므로, 올림 없이 두면 곡선이 영영 멈춘다.
    expect(drainCount(1, false)).toBe(1)
    expect(drainCount(5, false)).toBe(1)
  })

  it('남은 양에 비례해 속도를 올린다 — 많이 쌓이면 빨리 따라잡는다', () => {
    const few = drainCount(12, false)
    const many = drainCount(48, false)
    expect(many).toBeGreaterThan(few)
  })

  it('밀린 정도가 묶음 셋을 넘으면 한 번에 비운다', () => {
    // 계산이 화면보다 훨씬 앞서 있다. 천천히 그리면 끝난 결과를 기다리게 만든다.
    expect(drainCount(60, false)).toBe(60)
    expect(drainCount(200, false)).toBe(200)
  })

  it('실행이 끝나면 더 기다릴 묶음이 없으므로 남은 것을 모두 내보낸다', () => {
    expect(drainCount(7, true)).toBe(7)
  })

  it('한 번에 내보내는 수가 대기열을 넘지 않는다', () => {
    for (const queued of [1, 2, 3, 11, 19, 20, 59]) {
      expect(drainCount(queued, false)).toBeLessThanOrEqual(queued)
    }
  })
})

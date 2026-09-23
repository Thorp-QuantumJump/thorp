import axios from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchStrategyRegistry, normalizeStrategyRegistryError } from './strategyRegistry'
import { apiClient } from './client'

vi.mock('./client', () => ({
  apiClient: { get: vi.fn() },
}))

const get = vi.mocked(apiClient.get)

/**
 * 취소를 실패로 잘못 읽지 않는지 고정한다.
 *
 * <p><b>이 검사는 판별 순서를 지킨다.</b> axios 1.x에서 `CanceledError`는 `AxiosError`를 상속하므로
 * `isCancel`과 `isAxiosError`가 <b>둘 다 참</b>이고 `response`도 없다. 그래서
 * `normalizeStrategyRegistryError`가 `isAxiosError`를 먼저 보면 취소가 응답 없는 오류로 흘러들어
 * "서버에 연결할 수 없습니다"가 된다. 사용자는 자기가 부르지도 않은 실패 안내를 받고, 실패를 세는
 * 호출자는 재시도까지 권한다.
 *
 * <p>순서를 뒤집어도 타입 검사와 lint는 통과하므로, 그 순서를 지키는 것은 이 검사뿐이다.
 */
describe('normalizeStrategyRegistryError', () => {
  it('취소를 네트워크 실패와 구분한다', () => {
    const canceled = normalizeStrategyRegistryError(new axios.CanceledError('조회를 끊었다'))

    expect(canceled.kind).toBe('canceled')
    expect(canceled.canceled).toBe(true)
  })

  it('응답이 없는 실패는 연결 문제로 읽는다', () => {
    // 취소와 마찬가지로 `response`가 없고 `isAxiosError`도 참이다. 두 경우가 갈리는 것은
    // 판별 순서뿐이므로, 위 검사와 이 검사가 함께 있어야 순서가 고정된다.
    const network = normalizeStrategyRegistryError(
      new axios.AxiosError('연결 실패', 'ERR_NETWORK'),
    )

    expect(network.kind).toBe('network')
    expect(network.canceled).toBe(false)
  })

  it('서버가 이유를 말한 실패는 그 코드와 상태를 그대로 싣는다', () => {
    const response = {
      status: 503,
      data: { success: false, data: null, error: { code: 'REGISTRY_UNAVAILABLE', message: '전략 설정을 불러오지 못했습니다.' } },
    }
    const failure = normalizeStrategyRegistryError(
      new axios.AxiosError('실패', 'ERR_BAD_RESPONSE', undefined, undefined, response as never),
    )

    expect(failure.kind).toBe('api')
    expect(failure.is('REGISTRY_UNAVAILABLE')).toBe(true)
    expect(failure.status).toBe(503)
    // 서버가 보낸 문장을 일반 문구로 바꾸지 않는다.
    expect(failure.message).toBe('전략 설정을 불러오지 못했습니다.')
  })
})

describe('fetchStrategyRegistry', () => {
  beforeEach(() => {
    get.mockReset()
  })

  it('조회를 끊으면 취소로 전한다', async () => {
    get.mockRejectedValue(new axios.CanceledError('조회를 끊었다'))

    await expect(fetchStrategyRegistry(3)).rejects.toMatchObject({ kind: 'canceled' })
  })

  it('섹터 수를 주면 질의에 싣고, 주지 않으면 싣지 않는다', async () => {
    get.mockResolvedValue({
      data: { success: true, data: { registryVersion: 'V1', provisional: false, analysisConditions: {}, strategies: [] }, error: null },
    })

    await fetchStrategyRegistry(3)
    expect(get.mock.calls[0][0]).toBe('/strategies/registry?universeSize=3')

    await fetchStrategyRegistry()
    expect(get.mock.calls[1][0]).toBe('/strategies/registry')
  })

  it('200 으로 온 실패 껍데기를 성공으로 읽지 않는다', async () => {
    // 껍데기를 믿고 `data`를 그대로 쓰면 `null`이 화면까지 내려간다.
    get.mockResolvedValue({
      data: { success: false, data: null, error: { code: 'REGISTRY_UNAVAILABLE', message: '전략 설정을 불러오지 못했습니다.' } },
    })

    await expect(fetchStrategyRegistry()).rejects.toMatchObject({
      kind: 'api',
      code: 'REGISTRY_UNAVAILABLE',
    })
  })
})

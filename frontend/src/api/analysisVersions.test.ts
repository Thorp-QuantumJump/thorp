import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchAnalysisVersions } from './analysisVersions'
import { apiClient } from './client'

describe('규칙·비용 버전 조회(T039)', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('004와 조회 표면을 정하기 전에는 값을 지어내지 않고 null이며 서버에 묻지 않는다', async () => {
    const getSpy = vi.spyOn(apiClient, 'get')

    await expect(fetchAnalysisVersions()).resolves.toBeNull()
    expect(getSpy).not.toHaveBeenCalled()
  })
})

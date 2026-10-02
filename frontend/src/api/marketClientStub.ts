/** Node 단독 API 테스트에서 axios 대신 사용하는 최소 클라이언트다. */
export const apiClient = {
  async get<T>(_endpoint: string, _config?: { signal?: AbortSignal }): Promise<{ data: T }> {
    void _endpoint
    void _config
    throw new Error('테스트에서 apiClient.get 구현을 지정해야 합니다.')
  },
}

import { useCallback, useEffect, useState } from 'react'
import { fetchCurriculum, type CurriculumResult } from '@/api/learning'

/** 커리큘럼 조회의 상태. 받는 중이거나, 받았거나(`loaded`), 받지 못했다(`failed`). */
export type CurriculumState = { status: 'loading' } | CurriculumResult

/**
 * 배우기 커리큘럼(`GET /learning/curriculum`, 013 계약 §1). 배우기 목록, '나의 학습' 화면, 회원정보의 '학습 기록'
 * 구역이 함께 쓴다. 첫 단계를 공개했는지(`isFirstStageReleased`)도 이 응답으로 안다.
 *
 * <p>화면을 열 때 한 번 받고, 사용자가 '다시 시도'를 누를 때(`retry`) 다시 받는다. 조용히 재시도하지 않는다. 화면을 떠나면
 * 요청을 거둔다. `enabled`가 거짓이면(개발용 미리보기처럼 서버를 부르지 않는 화면) 부르지 않고 받는 중으로 남는다.
 */
export function useCurriculum({ enabled = true }: { enabled?: boolean } = {}) {
  const [state, setState] = useState<CurriculumState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!enabled) return
    const controller = new AbortController()
    setState({ status: 'loading' })
    void fetchCurriculum(controller.signal).then((result) => {
      if (!controller.signal.aborted) setState(result)
    })
    return () => controller.abort()
  }, [enabled, attempt])

  const retry = useCallback(() => setAttempt((count) => count + 1), [])
  return { state, retry }
}

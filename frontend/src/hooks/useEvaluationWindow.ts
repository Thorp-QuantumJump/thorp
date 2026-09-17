import { useEffect, useState } from 'react'
import type {
  AnalysisSupport,
  EvaluationWindowState,
} from '@/components/strategy/analysisSupport'
import type { EvaluationWindowQuery } from '@/types/evaluationWindow'

type Loaded = { key: string; state: EvaluationWindowState }

// 렌더마다 새 객체를 돌려주면 이 상태에 기대는 판정 메모가 매번 다시 계산된다.
const LOADING: EvaluationWindowState = { status: 'loading' }
const UNCOMPUTABLE: EvaluationWindowState = { status: 'uncomputable' }

function keyOf(query: EvaluationWindowQuery): string {
  return `${query.sectors.join(',')}|${query.maxWarmupTradingDays}`
}

/**
 * 고른 섹터 집합의 공통 평가 기간을 받아 둔다(T042).
 *
 * <p><b>조회는 조회 내용이 바뀔 때만 나간다.</b> 의존성은 질의 객체의 참조가 아니라 섹터 목록과 상한을 이은
 * 글이다. 그래서 등록표를 다시 받아 질의 객체가 새로 만들어져도 내용이 같으면 다시 묻지 않고, 상세 설정의
 * 값을 바꾸는 동안에는 요청이 나가지 않는다(T058, research R7-1). 워밍업 판정은 이 상태로
 * `judgeWarmupFromWindow`가 화면 안에서 한다.
 *
 * <p><b>늦게 온 응답이 새 섹터 집합을 판정하지 않는다.</b> 받은 상태를 그 조회의 열쇠와 함께 담아 두고,
 * 지금 열쇠와 다르면 `loading`으로 읽는다. 이전 조회는 중단한다.
 *
 * @param fetchEvaluationWindow 통로의 조회 함수. 렌더 사이에 안정된 참조여야 한다(`AnalysisSupport`)
 * @param query 보낼 조회. 조건이 갖춰지지 않았으면 `null`, 상한을 계산하지 못했으면 `'uncomputable'`
 */
export function useEvaluationWindow(
  fetchEvaluationWindow: AnalysisSupport['fetchEvaluationWindow'],
  query: EvaluationWindowQuery | 'uncomputable' | null,
): EvaluationWindowState {
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const request = query !== null && query !== 'uncomputable' ? query : null
  const key = request ? keyOf(request) : null

  useEffect(() => {
    if (request === null || key === null) return
    const controller = new AbortController()
    let active = true
    fetchEvaluationWindow(request, controller.signal)
      .then((window) => {
        if (active) setLoaded({ key, state: { status: 'loaded', window } })
      })
      .catch(() => {
        if (active) setLoaded({ key, state: { status: 'unavailable' } })
      })
    return () => {
      active = false
      controller.abort()
    }
    // 질의 객체의 참조는 보지 않는다. 내용은 `key`가 대신한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetchEvaluationWindow, key])

  if (query === 'uncomputable') return UNCOMPUTABLE
  if (key === null || loaded === null || loaded.key !== key) return LOADING
  return loaded.state
}

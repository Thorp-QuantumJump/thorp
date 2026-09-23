import type { ResultTimeseriesPoint } from '@/types/result'

/**
 * 실행 중 진행 시계열 구독(`GET /results/{token}/progress`, spec 004 R-05).
 *
 * <p><b>`apiClient`를 쓰지 않는다.</b> 그쪽은 요청 하나에 응답 하나인 경로의 것이고 응답 봉투를 벗기는
 * 일을 한다. 여기서는 한 연결에 이벤트가 수십 번 흐르므로 봉투가 없고, 브라우저의 `EventSource`가
 * 연결과 재접속을 맡는다.
 *
 * <p><b>형식이 틀린 토큰은 연결하지 않는다.</b> 같은 이유로 `api/result.ts`도 요청하지 않는다 —
 * 주소창에서 온 `..`나 `/`를 경로에 넣으면 브라우저가 경로를 정리해 다른 자원을 부를 수 있다.
 */

const publicTokenPattern = /^[0-9a-f]{32}$/

/** `apiClient`와 같은 기준 경로를 쓴다. `EventSource`는 절대·상대 주소를 그대로 받는다 */
const basePath = (import.meta.env.VITE_API_BASE_URL ?? '/api') + '/results'

/** 서버가 `progress` 이벤트로 보내는 본문. 소수는 문자열이다(최종 시계열 응답과 같은 표기) */
export type ProgressEvent = {
  stage: string
  completedTradingDays: number
  totalTradingDays: number
  lastTradingDate: string
  /** 이번 구간에서 <b>새로 계산한</b> 점만 온다. 앞서 받은 점은 다시 오지 않는다 */
  points: ResultTimeseriesPoint[]
}

export type ProgressSubscriber = {
  onProgress: (event: ProgressEvent) => void
  /** 실행이 끝났다. 인자는 종결 상태 이름이다 */
  onFinished: (status: string) => void
  /** 연결이 끊겼다. `EventSource`가 스스로 다시 붙으므로 화면은 곡선을 지우지 않는다 */
  onError: () => void
}

/**
 * 구독을 걸고 해제 함수를 돌려준다. 토큰 형식이 틀리면 연결하지 않고 아무것도 하지 않는 해제 함수를
 * 돌려준다 — 부르는 쪽에 분기를 만들지 않으려는 것이고, 그 토큰은 상세 조회가 이미 400으로 안내한다.
 */
export function subscribeProgress(token: string, subscriber: ProgressSubscriber): () => void {
  if (!publicTokenPattern.test(token)) return () => {}
  // `EventSource`가 없는 환경(jsdom 등)에서는 구독하지 않는다. 곡선이 자라지 않을 뿐이고, 진행 정도와
  // 최종 결과는 상태 폴링이 그대로 가져온다 — 여기서 예외를 올리면 결과 화면 전체가 뜨지 못한다.
  if (typeof EventSource === 'undefined') return () => {}

  const source = new EventSource(`${basePath}/${token}/progress`)

  source.addEventListener('progress', (event) => {
    const parsed = parseProgress((event as MessageEvent<string>).data)
    // 읽을 수 없는 본문은 버린다. 곡선에 지어낸 점을 넣지 않는다.
    if (parsed) subscriber.onProgress(parsed)
  })

  source.addEventListener('finished', (event) => {
    subscriber.onFinished((event as MessageEvent<string>).data)
    // 종결 뒤에는 더 올 것이 없다. 닫지 않으면 EventSource 가 다시 붙으려 한다.
    source.close()
  })

  source.addEventListener('error', () => subscriber.onError())

  return () => source.close()
}

/**
 * 이벤트 본문을 읽는다. 모양이 계약과 다르면 `null`이다.
 *
 * 점 하나라도 날짜나 자산가치가 비어 있으면 그 점만 버리지 않고 이벤트 전체를 버린다 — 구간 안에서
 * 일부만 그리면 곡선에 없는 계단이 생기고, 그것이 계산 결과인지 전달 사고인지 화면이 구분할 수 없다.
 */
function parseProgress(raw: string): ProgressEvent | null {
  let body: unknown
  try {
    body = JSON.parse(raw)
  } catch {
    return null
  }
  if (typeof body !== 'object' || body === null) return null
  const candidate = body as Partial<ProgressEvent>
  if (!Array.isArray(candidate.points)) return null
  if (typeof candidate.completedTradingDays !== 'number') return null
  if (typeof candidate.totalTradingDays !== 'number') return null
  const usable = candidate.points.every(
    (point) => typeof point?.date === 'string' && typeof point?.equity === 'string',
  )
  if (!usable) return null
  return {
    stage: typeof candidate.stage === 'string' ? candidate.stage : '',
    completedTradingDays: candidate.completedTradingDays,
    totalTradingDays: candidate.totalTradingDays,
    lastTradingDate: typeof candidate.lastTradingDate === 'string' ? candidate.lastTradingDate : '',
    points: candidate.points,
  }
}

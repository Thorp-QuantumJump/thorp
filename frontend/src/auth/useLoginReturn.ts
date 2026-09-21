import { useCallback } from 'react'
import { useLocation } from 'react-router-dom'
import { saveReturnTarget } from './returnTarget'

/**
 * 로그인으로 떠나기 직전에 지금 있던 자리를 적어 둔다.
 *
 * <p><b>복귀 장치 자체는 이미 있었고 부르는 곳만 없었다.</b> `saveReturnTarget`을 부르던 곳은
 * `ProtectedRoute` 하나뿐이라, 보호된 주소에 직접 들어간 경우에만 돌아올 자리가 남았다. 헤더나
 * 화면 안의 로그인 링크로 떠나면 아무것도 적히지 않아 로그인 뒤 기본 화면으로 떨어졌다.
 *
 * <p>질의 문자열과 조각까지 함께 적는다 — 설정 조합 자동 비교처럼 <b>무엇을 볼지가 주소에 들어
 * 있는 화면</b>은 경로만 적어 돌아가면 대상을 잃고 "탐색 대상이 없습니다"를 만난다.
 *
 * <p>인증 화면(`/login`·`/signup`)에서 부를 필요는 없다. `normalizeReturnTarget`이 그 주소를
 * 걸러 내므로 적어 봐야 기본값이 되고, 앞서 적어 둔 자리를 지우기만 한다.
 */
export function useLoginReturn(): () => void {
  const location = useLocation()
  const target = `${location.pathname}${location.search}${location.hash}`

  return useCallback(() => saveReturnTarget(target), [target])
}

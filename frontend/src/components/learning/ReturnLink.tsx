import { Link } from 'react-router-dom'
import { resolveReturnTarget } from './returnTargets'
import styles from './ReturnLink.module.css'

interface ReturnLinkProps {
  /** 글 화면의 `location.state`. 모양을 믿지 않고 `resolveReturnTarget`이 판정한다 */
  state: unknown
}

/**
 * 다른 화면에서 온 글의 '○○(으)로 돌아가기'(013 FR-009, term-help-ui §3).
 *
 * <p><b>보조 연결이다.</b> 글 화면의 행동 버튼은 '이 예시로 전략 진단하기' 하나라서(원칙 II) 같은 크기의
 * 버튼으로 두지 않는다.
 *
 * <p><b>경로로 이동한다.</b> `history.back()`에 맡기면 글 안에서 다른 글로 옮겨 다닌 만큼 뒤로 가야 하고,
 * 바깥에서 들어온 탭이면 앱을 떠난다(research R11). 쿼리 문자열은 받은 그대로 들고 돌아간다. 입력값은
 * 싣지 않는다 — 떠난 화면이 각자의 규칙으로 되살린다.
 *
 * <p>배우기 메뉴로 직접 들어오거나 새로고침해 state가 없으면 아무것도 그리지 않는다(spec Edge Cases).
 */
export function ReturnLink({ state }: ReturnLinkProps) {
  const target = resolveReturnTarget(state)
  if (!target) return null

  return (
    <Link className={styles.link} to={target.path}>
      <span aria-hidden="true">←</span> {target.label}
    </Link>
  )
}

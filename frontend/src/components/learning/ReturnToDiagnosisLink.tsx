import { Link } from 'react-router-dom'
import { normalizeReturnTarget } from '@/auth/returnTarget'
import type { LearningReturnState } from '@/types/learning'
import styles from './ReturnToDiagnosisLink.module.css'

/** 도움말을 붙이는 진단 화면. 이 밖의 경로로는 돌아가기 연결을 만들지 않는다(013 term-help-ui §3). */
const RETURN_PATHS = ['/diagnosis/sectors', '/strategy']
const RETURN_PATH_PREFIXES = ['/results/']

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isAllowedPath(path: string): boolean {
  return (
    RETURN_PATHS.includes(path) ||
    RETURN_PATH_PREFIXES.some((prefix) => path.startsWith(prefix) && path.length > prefix.length)
  )
}

/**
 * 글 화면이 받은 navigation state에서 돌아갈 자리를 꺼낸다(013 term-help-ui §3, research R11).
 *
 * <p><b>세 조건을 모두 만족할 때만 값을 돌려준다.</b> state 모양이 맞고, 경로가 허용 목록에 들고,
 * 014의 `normalizeReturnTarget`이 경로를 바꾸지 않아야 한다. state는 누구나 실어 보낼 수 있으므로
 * 받은 경로를 그대로 믿으면 앱 밖이나 인증 화면으로 보내는 연결이 생긴다.
 */
export function resolveLearningReturn(state: unknown): LearningReturnState | null {
  if (!isRecord(state) || !isRecord(state.learningReturn)) return null
  const { path, label } = state.learningReturn
  if (typeof path !== 'string' || typeof label !== 'string') return null
  if (!isAllowedPath(path) || normalizeReturnTarget(path) !== path) return null
  return { path, label }
}

interface ReturnToDiagnosisLinkProps {
  /** 글 화면의 `location.state`. 모양을 믿지 않고 `resolveLearningReturn`이 판정한다 */
  state: unknown
}

/**
 * '하던 진단으로 돌아가기'(013 FR-009, term-help-ui §3).
 *
 * <p><b>보조 연결이다.</b> 글 화면의 행동 버튼은 '이 예시로 전략 진단하기' 하나라서(원칙 II) 같은 크기의
 * 버튼으로 두지 않는다.
 *
 * <p><b>경로로 이동한다.</b> `history.back()`에 맡기면 글 안에서 다른 글로 옮겨 다닌 만큼 뒤로 가야 하고,
 * 바깥에서 들어온 탭이면 앱을 떠난다. 입력값은 싣지 않는다 — 진단 화면이 각자의 탭 세션 규칙으로
 * 되살린다.
 *
 * <p>배우기 메뉴로 직접 들어오거나 새로고침해 state가 없으면 아무것도 그리지 않는다(spec Edge Cases).
 */
export function ReturnToDiagnosisLink({ state }: ReturnToDiagnosisLinkProps) {
  const target = resolveLearningReturn(state)
  if (!target) return null

  return (
    <Link className={styles.link} to={target.path}>
      <span aria-hidden="true">←</span>
      {' 하던 진단으로 돌아가기 '}
      <span className={styles.label}>({target.label})</span>
    </Link>
  )
}

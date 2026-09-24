import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { fetchCaseWindow } from '@/api/regimeLink'
import { useAuth } from '@/auth/auth-context'
import { AppHeader } from '@/components/navigation/AppHeader'
import { useLoginRedirect } from '@/hooks/useLoginRedirect'
import { buildSectorSelectionPath, parseCaseId } from '@/marketflow/handoff'
import styles from './RegimeLinkStartPage.module.css'

/**
 * 사례에서 들어오는 011 진입 화면이다(진입 계약 §2). 010 사례 상세와 투자 대상 고르기의 '지금과 비슷했던
 * 과거 시장'이 이리로 보낸다.
 *
 * <p>하는 일은 셋뿐이다. ① 익명이면 로그인으로 보내고 같은 주소로 돌아오게 한다, ② `case`가 읽을 수 있는
 * 값이고 실제로 있는 사례인지 확인한다, ③ 확인되면 002(섹터·비중)로 넘긴다. <b>화면을 그리는 것이
 * 목적이 아니라 이어 주는 것이 목적이다.</b>
 *
 * <p><b>사례 없이 011을 열지 않는다.</b> `case`가 없거나 형식이 틀리거나 010 표시 후보에 없으면
 * 사례를 다시 고르는 투자 대상 고르기로 안내하고 이유를 한 문장으로 말한다(진입 계약 §5).
 */

const CASE_MISSING =
  '전략을 비교할 과거 사례가 주소에 없습니다. 투자 대상 고르기에서 과거 사례를 먼저 고른 뒤 다시 시도해 주세요.'
const CASE_MALFORMED =
  '주소에 담긴 사례를 읽을 수 없습니다. 투자 대상 고르기에서 과거 사례를 먼저 고른 뒤 다시 시도해 주세요.'
const CASE_NOT_FOUND =
  '그 사례를 더는 찾을 수 없습니다. 투자 대상 고르기에서 과거 사례를 다시 고른 뒤 이어가 주세요.'
const CASE_UNAVAILABLE =
  '사례를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.'

type Phase = { status: 'checking' } | { status: 'blocked'; message: string }

export function RegimeLinkStartPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const auth = useAuth()
  const { redirectToLogin } = useLoginRedirect()

  const caseId = params.get('case')
  const sectorCode = params.get('sector')
  /** 형식만 본다. 있는 사례인지는 서버가 답한다. 의존성으로 쓸 수 있게 불린 하나로 좁힌다. */
  const caseMalformed = caseId !== null && caseId !== '' && parseCaseId(caseId) === null

  const [phase, setPhase] = useState<Phase>({ status: 'checking' })

  // 익명이면 로그인 뒤 같은 주소로 돌아온다. 주소를 인자로 넘기지 않는다(014 방식).
  useEffect(() => {
    if (auth.status === 'anonymous' || auth.status === 'expired') redirectToLogin()
  }, [auth.status, redirectToLogin])

  useEffect(() => {
    if (auth.status !== 'authenticated') return
    if (caseId === null || caseId === '') {
      setPhase({ status: 'blocked', message: CASE_MISSING })
      return
    }
    if (caseMalformed) {
      setPhase({ status: 'blocked', message: CASE_MALFORMED })
      return
    }

    const controller = new AbortController()
    setPhase({ status: 'checking' })
    fetchCaseWindow(caseId, controller.signal)
      .then(() => {
        if (controller.signal.aborted) return
        // 확인된 사례만 다음 화면으로 넘긴다. 뒤로 가기가 이 화면으로 되돌아오지 않게 대체한다.
        void navigate(buildSectorSelectionPath(caseId, sectorCode), { replace: true })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        const status = typeof error === 'object' && error !== null ? (error as { status?: number }).status : null
        setPhase({
          status: 'blocked',
          message: status === 404 || status === 400 ? CASE_NOT_FOUND : CASE_UNAVAILABLE,
        })
      })
    return () => controller.abort()
  }, [auth.status, caseId, caseMalformed, navigate, sectorCode])

  if (auth.status === 'anonymous' || auth.status === 'expired') {
    return (
      <div className={styles.page}>
        <AppHeader />
        <main className={styles.main}>
          <p role="status">전략 비교를 시작하려면 로그인이 필요합니다. 로그인 화면으로 이동하고 있습니다.</p>
        </main>
      </div>
    )
  }

  return (
    <div className={styles.page}>
      <AppHeader />
      <main className={styles.main}>
        <h1 className={styles.title}>이 기간으로 전략 비교</h1>
        {phase.status === 'checking' ? (
          <p role="status">고른 사례를 확인하고 있습니다.</p>
        ) : (
          <div className={styles.blocked} role="alert">
            <p>{phase.message}</p>
            {/* 사례를 고르는 자리는 투자 대상 고르기의 '지금과 비슷했던 과거 시장'이다(002 FR-018). */}
            <Link to="/diagnosis/sectors">과거 사례 다시 고르기</Link>
          </div>
        )}
      </main>
    </div>
  )
}

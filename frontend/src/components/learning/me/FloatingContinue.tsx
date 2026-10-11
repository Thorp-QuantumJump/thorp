import { useEffect, useState, type RefObject } from 'react'
import { Link } from 'react-router-dom'
import type { ContinueReading } from '@/types/learningMe'
import { articlePath } from '../learningPaths'
import { continueLinkState } from './continueLink'
import styles from './FloatingContinue.module.css'

interface FloatingContinueProps {
  continueReading: ContinueReading
  /** 요약 패널의 '이어서 읽기' 연결. 이 연결이 화면 밖으로 나가면 단추가 나타난다 */
  targetRef: RefObject<HTMLElement | null>
  /** 로그인 상태를 다시 확인하는 중이라 숨긴다(research R33) */
  concealed: boolean
  linkState?: unknown
}

/**
 * 커리큘럼 보기의 떠 있는 '이어서 읽기'(013 FR-020, design-notes §3.1, 목업 `fab`). 화면 오른쪽 아래에 있다.
 *
 * <p>요약 패널의 '이어서 읽기'가 화면에 보이는 동안에는 숨긴다. 같은 단추가 두 번 보이지 않게 하려는 것이다. 공개된 글을
 * 모두 읽었으면 그리지 않는다. 기록을 받지 못했으면 부르는 쪽이 그리지 않는다. 깜빡이거나 맥동하지 않고, 나타날 때의
 * 짧은 움직임은 움직임 줄이기 설정이 아닐 때만 있다. 화면 크기를 재는 `IntersectionObserver`가 없는 환경에서는
 * 패널의 연결이 있으니 그리지 않는다.
 */
export function FloatingContinue({
  continueReading,
  targetRef,
  concealed,
  linkState,
}: FloatingContinueProps) {
  const { kind, articleId, number, percent } = continueReading
  // 패널의 연결은 이어 읽을 글이 바뀌면 다시 그려지므로 그때 다시 잰다.
  const offScreen = useOffScreen(targetRef, articleId)
  if (kind === 'ALL_DONE' || articleId === null || !offScreen || concealed) return null
  const filled = kind === 'RESUME' ? Math.max(0, Math.min(100, percent ?? 0)) : 0
  return (
    <Link
      className={styles.fab}
      to={articlePath(articleId)}
      state={continueLinkState(kind, linkState)}
    >
      <svg viewBox="0 0 26 26" aria-hidden="true" focusable="false">
        <circle className={styles.track} cx="13" cy="13" r="10.5" />
        {filled > 0 ? (
          <circle
            className={styles.arc}
            cx="13"
            cy="13"
            r="10.5"
            strokeWidth="3"
            strokeLinecap="round"
            pathLength={100}
            strokeDasharray={`${filled} 100`}
            transform="rotate(-90 13 13)"
          />
        ) : null}
      </svg>
      <span>이어서 읽기{number ? ` ${number}` : ''}</span>
    </Link>
  )
}

/** 대상이 화면 밖에 있는가. 잴 수 없으면 화면 안에 있는 것으로 본다. */
function useOffScreen(targetRef: RefObject<HTMLElement | null>, watchKey: string | null): boolean {
  const [offScreen, setOffScreen] = useState(false)
  useEffect(() => {
    const target = targetRef.current
    setOffScreen(false)
    if (!target || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      const entry = entries[entries.length - 1]
      if (entry) setOffScreen(!entry.isIntersecting)
    })
    observer.observe(target)
    return () => observer.disconnect()
  }, [targetRef, watchKey])
  return offScreen
}

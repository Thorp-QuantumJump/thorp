import { type ReactNode, useRef } from 'react'
import { Button } from '@/components/ui/button'
import type { ResourceState } from '@/hooks/useResource'
import styles from './resultDetail.module.css'

/**
 * 열 때 받는 자원 한 자리. 불러오는 중·실패·받은 내용을 같은 모양으로 보인다.
 *
 * <p>받지 못하면 빈 표 대신 사유와 '다시 불러오기'를 둔다(원칙 VI). 다시 받는 동안에는 앞선 내용을 남겨 두고,
 * 앞선 내용이 없으면 누른 버튼이 사라지므로 초점을 이 자리로 옮긴다 — 키보드 사용자가 화면 처음으로 밀려나지
 * 않는다(FR-022).
 */
export function ResourceArea<T>({
  resource,
  loadingText,
  children,
}: {
  resource: ResourceState<T> & { retry: () => void }
  loadingText: string
  children: (data: T) => ReactNode
}) {
  const areaRef = useRef<HTMLDivElement>(null)

  const retry = () => {
    areaRef.current?.focus()
    resource.retry()
  }

  // 앞선 내용과 새 내용을 **같은 자리**에서 그린다. 자리가 다르면 React가 표와 버튼을 새로 만들어 초점이 사라진다.
  const shown =
    resource.status === 'loaded'
      ? resource.data
      : resource.status === 'loading'
        ? resource.stale
        : undefined

  return (
    <div ref={areaRef} tabIndex={-1} className={styles.area}>
      {resource.status === 'loading' ? (
        <p className={styles.copy} role="status">
          {loadingText}
        </p>
      ) : null}
      {resource.status === 'failed' ? (
        <div className={styles.row} role="alert">
          <p className={styles.alert}>{resource.message}</p>
          <Button type="button" variant="outline" size="sm" onClick={retry}>
            다시 불러오기
          </Button>
        </div>
      ) : null}
      {shown !== undefined ? children(shown) : null}
    </div>
  )
}

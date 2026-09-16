import { useEffect, useRef } from 'react'
import styles from './RegistryUnavailableNotice.module.css'

interface RegistryUnavailableNoticeProps {
  /** 서버가 보낸 문장. 없으면 기본 안내를 쓴다. 화면이 문구를 지어내지 않는다. */
  message?: string
  onRetry: () => void
  /** 다시 불러오는 중이면 버튼을 잠근다. */
  retrying?: boolean
  /**
   * 지금까지 조회가 실패한 횟수.
   *
   * 둘 이상이면 사용자가 '다시 시도'를 눌렀는데도 실패한 것이므로 초점을 버튼으로 되돌린다.
   * 첫 실패에는 옮기지 않는다 — 화면에 막 들어온 사람의 초점을 빼앗는 셈이 된다(NFR-001).
   */
  failureCount?: number
}

const DEFAULT_MESSAGE = '전략 설정을 불러오지 못했습니다.'

/**
 * 등록표를 받지 못했을 때 카드 자리에 놓이는 안내(FR-018).
 *
 * <p><b>임시 값이나 예비 등록표로 대신 동작하는 경로를 두지 않는다.</b> 이 컴포넌트가 보이는 동안
 * 전략을 하나도 고를 수 없고 '분석하기'와 '설정 조합 자동 비교' 어느 쪽도 누를 수 없다. 대신
 * 채워 주면 사용자는 등록되지 않은 설정으로 분석한 결과를 등록된 것으로 믿게 된다.
 *
 * <p>실패를 <code>role="alert"</code>로 알리고, 다시 시도한 뒤에도 실패하면 안내를 읽은 다음
 * 초점이 '다시 시도' 버튼으로 돌아온다. 성공했을 때 초점을 새 카드 영역으로 옮기는 것은 카드
 * 영역을 소유한 <code>StrategyCardList</code>가 한다.
 */
export function RegistryUnavailableNotice({
  message,
  onRetry,
  retrying = false,
  failureCount = 1,
}: RegistryUnavailableNoticeProps) {
  const retryButtonRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (failureCount > 1 && !retrying) {
      retryButtonRef.current?.focus()
    }
  }, [failureCount, retrying])

  return (
    <div className={styles.notice} role="alert">
      <p className={styles.message}>{message ?? DEFAULT_MESSAGE}</p>
      <p className={styles.detail}>
        설정을 불러오기 전에는 전략을 고를 수 없습니다. 잠시 후 다시 시도해 주세요.
      </p>
      <button
        ref={retryButtonRef}
        type="button"
        className={styles.retry}
        onClick={onRetry}
        disabled={retrying}
      >
        {retrying ? '다시 불러오는 중' : '다시 시도'}
      </button>
    </div>
  )
}

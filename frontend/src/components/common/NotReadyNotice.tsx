/** 아직 줄 것이 없는 상태에서 사용자가 누를 수 있는 행동 하나를 정의한다. */
export type NotReadyActionSpec = {
  /** 버튼에 보이는 이름이다. */
  label: string
  /** 버튼을 눌렀을 때 실행한다. */
  onAction: () => void
}

type NotReadyNoticeProps = {
  /** 언제나 보이는 한 문장이다. */
  message: string
  /** 주지 않으면 버튼을 그리지 않는다. */
  action?: NotReadyActionSpec
  /** 놓이는 자리의 여백과 정렬은 쓰는 쪽이 정한다. */
  className?: string
}

/**
 * 아직 줄 것이 없는 상태를 한 문장으로 알린다.
 *
 * 아직 만들지 않은 메뉴, 대응표 미발행(409 `SECTOR_MAP_NOT_READY`), 네트워크 실패가 모두 이
 * 컴포넌트 하나를 쓴다. 표시를 한 곳에 모아 두어야 세 경우를 같은 기준으로 셀 수 있다.
 * 다시 눌러도 달라지지 않는 상태에는 행동을 주지 않으며, 행동을 주지 않으면 버튼은 그려지지
 * 않는다. 이 컴포넌트는 예외를 던지지 않고 언제나 정상 화면을 그린다.
 */
export function NotReadyNotice({ message, action, className }: NotReadyNoticeProps) {
  return (
    <div className={className} role="status" aria-live="polite">
      <p>{message}</p>
      {action ? (
        <button type="button" onClick={action.onAction}>
          {action.label}
        </button>
      ) : null}
    </div>
  )
}

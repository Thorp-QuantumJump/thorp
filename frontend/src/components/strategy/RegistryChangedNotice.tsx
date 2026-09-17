import styles from './RegistryChangedNotice.module.css'

/** 바뀐 등록표를 다시 받는 과정의 어디쯤인가. 카드 목록의 조회 상태에서 온다. */
export type RegistryReloadPhase = 'reloading' | 'reloaded' | 'failed'

interface RegistryChangedNoticeProps {
  phase: RegistryReloadPhase
  /** 바뀐 등록표로 다시 판정한 뒤 기본값 복원을 기다리는 값의 수. 이 화면에 안내가 보이는 것만 센다 */
  pendingRestoreCount: number
  /** 바뀐 등록표에서 전략 자체가 사라져 지운 설정의 안내. 되돌릴 자리가 없어 이 알림이 대신 전한다 */
  removedMessages: readonly string[]
}

const TITLE = '확인하신 사이에 등록표가 바뀌었습니다. 분석 요청은 보내지 않았습니다.'

function detailOf(phase: RegistryReloadPhase, pendingRestoreCount: number): string {
  if (phase === 'reloading') return '바뀐 등록표를 불러오는 중입니다.'
  if (phase === 'failed') {
    return '바뀐 등록표를 불러오지 못했습니다. 전략 카드 자리에서 다시 불러온 뒤 설정을 확인해 주세요.'
  }
  if (pendingRestoreCount > 0) {
    return '바뀐 등록표에서 쓸 수 없게 된 값이 있습니다. 아래 안내에서 기본값을 복원한 뒤 분석하기를 다시 눌러 주세요.'
  }
  return '지금 설정값은 바뀐 등록표에서도 쓸 수 있어 그대로 두었습니다. 확인한 뒤 분석하기를 다시 눌러 주세요.'
}

/**
 * 분석 요청이 등록표 버전 변경(409 `REGISTRY_VERSION_CHANGED`)으로 거절되었음을 알린다(research R9,
 * quickstart 10).
 *
 * <p><b>다시 보내지 않는다.</b> 사용자가 본 적 없는 등록표로 요청이 나가면 거절이든 통과든 사용자가
 * 확인하지 않은 규칙의 결과다. 바뀐 등록표로 설정을 다시 판정한 결과를 보이고, 보낼지는 사용자가 다시
 * 정한다.
 *
 * <p><b>값을 고치지 않는다.</b> 다시 판정해 유효한 값은 그대로 두고, 쓸 수 없게 된 값은 비운 채 기존
 * 무효 안내가 이유와 복원 버튼을 보인다(`useStrategySettings`의 재검증, FR-017). 이 알림은 그 사실을
 * 요약할 뿐 복원을 대신하지 않는다.
 */
export function RegistryChangedNotice({
  phase,
  pendingRestoreCount,
  removedMessages,
}: RegistryChangedNoticeProps) {
  return (
    <div className={styles.notice} role="status" data-phase={phase}>
      <p className={styles.title}>{TITLE}</p>
      <p className={styles.detail}>{detailOf(phase, pendingRestoreCount)}</p>
      {phase === 'reloaded' && removedMessages.length > 0 ? (
        <ul className={styles.removed}>
          {removedMessages.map((message) => (
            <li key={message}>{message}</li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}

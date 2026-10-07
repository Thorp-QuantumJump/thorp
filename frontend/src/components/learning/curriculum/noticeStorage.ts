/**
 * 닫은 공개 안내를 탭 동안 기억한다(013 FR-031 '공개 안내', research R27).
 *
 * <p><b>학습 기록이 아니라 화면 상태다.</b> 로그인 여부와 관계없이 그 탭의 `sessionStorage`에만 두고 서버나
 * 계정에 보내지 않는다. 그 탭에서는 새로 고침·같은 탭 안의 이동·로그인 왕복 뒤에도 다시 보이지 않고, 새 탭에서는
 * 다시 보인다. 안내 한 줄은 변경 기록의 `entryKey`로 가리킨다. 저장 방식은 002
 * `diagnosis/selectionStorage.ts`와 같다.
 */

export const DISMISSED_NOTICES_STORAGE_KEY = 'thorp.learning.dismissedNotices'

/** 내용 형식의 번호다. 형식을 바꾸면 올린다. */
export const DISMISSED_NOTICES_PAYLOAD_VERSION = 1

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/**
 * 닫은 안내의 `entryKey`를 읽는다. 없거나 읽을 수 없거나 저장소가 막혀 있으면 빈 목록이다.
 *
 * 읽지 못하면 닫았던 안내가 다시 보일 뿐 사용자의 기록을 잃는 것이 아니므로 따로 알리지 않는다.
 */
export function readDismissedNotices(): string[] {
  let raw: string | null
  try {
    raw = window.sessionStorage.getItem(DISMISSED_NOTICES_STORAGE_KEY)
  } catch {
    return []
  }
  if (raw === null) return []

  let payload: unknown
  try {
    payload = JSON.parse(raw)
  } catch {
    return []
  }
  if (
    !isRecord(payload) ||
    payload.version !== DISMISSED_NOTICES_PAYLOAD_VERSION ||
    !Array.isArray(payload.entryKeys)
  ) {
    return []
  }
  return payload.entryKeys.filter((key): key is string => typeof key === 'string')
}

/**
 * 안내 줄을 닫은 것으로 기억한다. 앞서 닫은 줄은 그대로 두고 새 줄을 더한다. 기억한 뒤의 목록을 돌려주므로
 * 저장소가 막혀 있어도 화면은 그 목록으로 이 화면 안에서 안내를 내릴 수 있다.
 */
export function dismissNotices(entryKeys: readonly string[]): string[] {
  const next = [...new Set([...readDismissedNotices(), ...entryKeys])]
  try {
    window.sessionStorage.setItem(
      DISMISSED_NOTICES_STORAGE_KEY,
      JSON.stringify({ version: DISMISSED_NOTICES_PAYLOAD_VERSION, entryKeys: next }),
    )
  } catch {
    // 저장소가 막힌 환경에서는 이 화면 안에서만 내린다. 다시 열면 안내가 보인다.
  }
  return next
}

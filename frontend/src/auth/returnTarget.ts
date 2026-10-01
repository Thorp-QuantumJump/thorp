/**
 * 돌아갈 곳을 모를 때만 쓰는 자리다.
 *
 * <p><b>거의 쓰이지 않아야 정상이다.</b> 로그인으로 들어가는 통로는 모두 떠나온 자리를
 * {@link saveReturnTarget}으로 적어 두므로, 여기까지 내려오는 것은 주소창에 `/login`을 직접 친
 * 경우나 저장소가 막힌 경우뿐이다. 그 드문 경우에 시장 흐름으로 보내면 로그인해서 하려던 일과
 * 무관한 화면에 떨어지므로, 진단 흐름의 첫 단계로 보낸다. 내 기록은 아직 목록이 없어(spec 006)
 * 빈 화면을 보여 주게 되므로 기본값으로 두지 않는다.
 */
export const DEFAULT_RETURN_TARGET = '/diagnosis/sectors'

const RETURN_TARGET_KEY = 'thorp.auth.returnTarget'
// `/auth/social`은 소셜 로그인 뒤 화면들이다(spec 014). 대기 상태에 묶인 화면이라 로그인 뒤 돌아갈 자리가 아니다.
const AUTH_PATHS = ['/login', '/signup', '/auth/social']

function isAuthPath(pathname: string): boolean {
  return AUTH_PATHS.some((path) => pathname === path || pathname.startsWith(`${path}/`))
}

/** 외부 이동과 인증 화면 순환을 막고 앱 내부 상대 경로만 허용한다. */
export function normalizeReturnTarget(target: unknown): string {
  if (
    typeof target !== 'string' ||
    !target.startsWith('/') ||
    target.startsWith('//') ||
    target.includes('\\')
  ) {
    return DEFAULT_RETURN_TARGET
  }

  try {
    const url = new URL(target, window.location.origin)
    if (url.origin !== window.location.origin || isAuthPath(url.pathname)) {
      return DEFAULT_RETURN_TARGET
    }
    return `${url.pathname}${url.search}${url.hash}`
  } catch {
    return DEFAULT_RETURN_TARGET
  }
}

/** 현재 탭에서 로그인 뒤 돌아갈 위치를 저장한다. */
export function saveReturnTarget(target: unknown): void {
  const normalized = normalizeReturnTarget(target)

  try {
    if (normalized === DEFAULT_RETURN_TARGET && target !== DEFAULT_RETURN_TARGET) {
      window.sessionStorage.removeItem(RETURN_TARGET_KEY)
      return
    }
    window.sessionStorage.setItem(RETURN_TARGET_KEY, normalized)
  } catch {
    // 저장소가 차단된 환경에서는 안전한 기본 화면 복귀로 자연스럽게 대체한다.
  }
}

/** 저장된 위치를 다시 검증하고 한 번만 사용한 뒤 즉시 제거한다. */
export function consumeReturnTarget(): string {
  try {
    const target = window.sessionStorage.getItem(RETURN_TARGET_KEY)
    window.sessionStorage.removeItem(RETURN_TARGET_KEY)
    return normalizeReturnTarget(target)
  } catch {
    return DEFAULT_RETURN_TARGET
  }
}

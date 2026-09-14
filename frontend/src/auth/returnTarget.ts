export const DEFAULT_RETURN_TARGET = '/home'

const RETURN_TARGET_KEY = 'thorp.auth.returnTarget'
const AUTH_PATHS = ['/login', '/signup']

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

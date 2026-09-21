/**
 * 로그인 화면에 쳐 둔 이메일을 화면을 떠나도 잃지 않게 해 주는 자리다.
 *
 * <p><b>가입 화면과 같은 이유로 사라졌다.</b> 이메일은 컴포넌트 안에만 있어서, 비밀번호가
 * 기억나지 않아 확인하러 나갔다 오거나 새로고침 한 번이면 빈칸으로 돌아왔다. 가입을 막 마치고
 * 온 경우에는 특히 나쁘다 — 채워 준 주소가 `location.state`에 실려 오는데 그 값은 화면을
 * 한 번 벗어나면 사라지므로, 방금 만든 계정 주소를 사용자가 다시 쳐야 했다.
 *
 * <p><b>비밀번호는 적지 않는다.</b> 여기서 되살릴 값은 '누구로 들어가려던 참이었나'뿐이다.
 *
 * <p>탭 수명({@link window.sessionStorage})으로 두고 로그인에 성공하면 지운다 — 공용 PC에서
 * 다음 사람의 로그인 화면에 앞사람 주소가 남아 있으면 안 된다.
 */

const LOGIN_EMAIL_KEY = 'thorp.auth.loginEmail'

/** 쳐 둔 이메일을 적어 둔다. 빈 값이면 적지 않고 지운다. */
export function saveLoginEmail(email: string): void {
  try {
    if (!email) {
      window.sessionStorage.removeItem(LOGIN_EMAIL_KEY)
      return
    }
    window.sessionStorage.setItem(LOGIN_EMAIL_KEY, email)
  } catch {
    // 저장소가 막힌 환경에서는 예전처럼 빈칸으로 시작한다. 로그인 자체는 그대로 된다.
  }
}

/** 적어 둔 이메일을 읽는다. 없으면 빈 문자열이다. */
export function readLoginEmail(): string {
  try {
    return window.sessionStorage.getItem(LOGIN_EMAIL_KEY) ?? ''
  } catch {
    return ''
  }
}

/** 로그인에 성공했을 때 지운다. */
export function clearLoginEmail(): void {
  try {
    window.sessionStorage.removeItem(LOGIN_EMAIL_KEY)
  } catch {
    // 지우지 못해도 다음 저장이 덮어쓴다.
  }
}

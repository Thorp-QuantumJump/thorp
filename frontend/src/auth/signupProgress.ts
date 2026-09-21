/**
 * 회원가입 도중 화면을 떠났다 돌아와도 인증을 다시 받지 않게 해 주는 자리다.
 *
 * <p><b>인증번호는 이미 사용자 메일함에 가 있다.</b> 화면 상태만 컴포넌트 안에 있어서, 약관을
 * 보러 나갔다 오거나 새로고침 한 번에 전부 사라지고 멀쩡히 살아 있는 번호를 두고 다시 받아야
 * 했다. 서버가 보낸 번호의 수명과 화면이 기억하는 기간이 어긋나 있던 것이라, 화면 쪽을 번호
 * 쪽에 맞춘다.
 *
 * <p><b>비밀번호와 인증번호 자체는 적지 않는다.</b> 다시 받게 만드는 원인은 '어느 주소로 보냈고
 * 어디까지 끝났는가'이지 자격 증명이 아니다. 적어 둘 이유가 없는 것을 적으면 저장소를 읽을 수
 * 있는 다른 코드에 그만큼을 내주게 된다.
 *
 * <p>탭 수명({@link window.sessionStorage})으로 둔다 — 공용 PC에서 창을 닫은 뒤에도 남의 가입
 * 정보가 입력칸에 떠 있으면 안 된다.
 */

const SIGNUP_PROGRESS_KEY = 'thorp.auth.signupProgress'

/** 화면을 떠났다 돌아왔을 때 되살릴 값. 자격 증명은 여기 넣지 않는다. */
export type SignupProgress = {
  /** 사용자가 친 그대로의 이메일. 정규화한 값만 두면 입력칸 내용이 바뀌어 보인다. */
  email: string
  /** 인증번호를 실제로 보낸 주소. `null`이면 아직 받은 적이 없다. */
  requestedEmail: string | null
  /** 확인까지 끝난 주소. `null`이면 아직 확인 전이다. */
  verifiedEmail: string | null
  /** 1 계정 정보, 2 프로필 정보. */
  step: 1 | 2
  nickname: string
  phoneNumber: string
}

function isString(value: unknown): value is string {
  return typeof value === 'string'
}

/** 저장해 둔 값이 지금 쓸 수 있는 모양인지 본다. 손상됐거나 판이 다르면 없는 것으로 친다. */
function parse(raw: string | null): SignupProgress | null {
  if (raw === null) return null

  try {
    const value: unknown = JSON.parse(raw)
    if (typeof value !== 'object' || value === null) return null

    const candidate = value as Record<string, unknown>
    const { email, requestedEmail, verifiedEmail, step, nickname, phoneNumber } = candidate

    if (!isString(email) || !isString(nickname) || !isString(phoneNumber)) return null
    if (requestedEmail !== null && !isString(requestedEmail)) return null
    if (verifiedEmail !== null && !isString(verifiedEmail)) return null
    if (step !== 1 && step !== 2) return null

    return { email, requestedEmail, verifiedEmail, step, nickname, phoneNumber }
  } catch {
    return null
  }
}

/** 지금까지의 진행을 적어 둔다. */
export function saveSignupProgress(progress: SignupProgress): void {
  try {
    window.sessionStorage.setItem(SIGNUP_PROGRESS_KEY, JSON.stringify(progress))
  } catch {
    // 저장소가 막힌 환경에서는 예전처럼 매번 새로 시작한다. 가입 자체는 그대로 된다.
  }
}

/** 적어 둔 진행을 읽는다. 없거나 못 믿을 값이면 `null`이다. */
export function readSignupProgress(): SignupProgress | null {
  try {
    return parse(window.sessionStorage.getItem(SIGNUP_PROGRESS_KEY))
  } catch {
    return null
  }
}

/** 가입이 끝났거나 처음부터 다시 할 때 지운다. */
export function clearSignupProgress(): void {
  try {
    window.sessionStorage.removeItem(SIGNUP_PROGRESS_KEY)
  } catch {
    // 지우지 못해도 다음 저장이 덮어쓴다.
  }
}

import type { AuthApiErrorCode, AuthFieldError } from './auth'
import type { LearningApiErrorCode } from './learning'
import type { LearningAdminApiErrorCode } from './learningAdmin'
import type { LearningMeApiErrorCode } from './learningMe'
import type { MyRecordApiErrorCode } from './myRecords'
import type { SectorApiErrorCode } from './sector'

/**
 * 백엔드가 **모든 응답에 두르는 공통 형식**이다. 특정 도메인의 자료 구조가 아니다.
 *
 * 원본은 `backend/.../common/web/response/ApiResponse.java`와 그 안의 `ErrorResponse`다.
 * 성공이면 `{ success: true, data, error: null }`, 실패면 `{ success: false, data: null, error }`.
 *
 * ## 의존 방향
 *
 * **공용 파일이 도메인 파일을 안다. 그 반대가 아니다.** `types/auth.ts`와 `types/sector.ts`는
 * 이 파일을 가져오지 않는다.
 *
 * 이 방향을 고른 까닭은 아는 오류 이름의 목록이 각 도메인에 흩어져 있기 때문이다. 반대로 두면
 * (도메인이 공용을 가져오면) 공용 파일이 도메인의 이름 목록을 스스로 다시 적어야 하고, 같은
 * 목록이 두 곳에 생겨 한쪽만 늘어난 채로 갈라진다 — 이 구조를 만들게 한 결함이 정확히 그것이었다.
 * 둘 다 가져오면 순환 참조가 되어 지금보다 나빠진다.
 *
 * 새 도메인이 오류 이름을 더할 때 고치는 파일은 자기 도메인 파일과 이 파일 둘이다.
 * **인증 파일은 고치지 않는다.**
 */

/**
 * 지금 **알고 있는** 서버 오류 신호 이름이다. 도메인이 늘면 그 도메인의 union을 여기에 잇는다.
 *
 * 화면이 "이 오류인가?"를 물을 때 쓴다. 닫혀 있어서 자동완성이 되고 오타가 걸린다.
 */
export type ServiceApiErrorCode =
  | AuthApiErrorCode
  | SectorApiErrorCode
  | LearningApiErrorCode
  | LearningAdminApiErrorCode
  | LearningMeApiErrorCode
  | MyRecordApiErrorCode

/**
 * 서버가 실제로 보내는 오류 이름이다. **열려 있다.**
 *
 * 목록을 닫아 두면 모르는 이름이 왔을 때 봉투를 읽는 쪽이 그 응답을 통째로 '예상 못한 것'으로
 * 떨어뜨리고 **서버가 보낸 안내 문구가 일반 문구로 바뀐다.** 다음 도메인이 자기 이름을 목록에
 * 더하는 것을 잊어도 빌드도 검사도 아무것도 실패하지 않으므로, 그 실수는 사용자가 잘못된 안내를
 * 받고 나서야 드러난다. `src/api/paramSearch.ts`가 같은 까닭으로 오류 이름을 열린 문자열로 두고
 * "모르는 코드를 아는 코드로 접으면 화면이 잘못된 안내를 하게 된다"고 적어 두었다.
 *
 * `string & Record<never, never>`는 문자열을 모두 받으면서도 위 union의 자동완성을 살려 두는
 * 표기다. 그냥 `string`으로 두면 알려진 이름의 자동완성이 사라진다.
 *
 * **담을 때는 열고 물을 때는 닫는다.** 값의 타입은 이것이고, 견주는 쪽은
 * `ServiceApiErrorCode`를 받는다(`api/client.ts`의 `isApiErrorCode`·`ApiClientError.is`).
 */
export type ApiErrorCode = ServiceApiErrorCode | (string & Record<never, never>)

/**
 * 실패 봉투가 실어 오는 오류 본문이다.
 *
 * `fieldErrors`는 지금 인증 입력 검증만 쓰며 그래서 필드 이름이 인증의 union이다. 공용 형식이
 * 도메인 타입 하나를 아는 셈이라 깔끔하지 않지만, 이번 작업은 자리만 옮기고 동작을 바꾸지
 * 않는다. 다른 도메인이 같은 모양을 쓰게 되면 그때 필드 이름을 넓힌다.
 */
export type ApiError = {
  code: ApiErrorCode
  message: string
  fieldErrors?: AuthFieldError[]
}

export type ApiSuccessEnvelope<T> = {
  success: true
  data: T
  error: null
}

export type ApiErrorEnvelope = {
  success: false
  data: null
  error: ApiError
}

export type ApiEnvelope<T> = ApiSuccessEnvelope<T> | ApiErrorEnvelope

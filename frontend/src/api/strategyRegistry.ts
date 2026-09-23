import axios from 'axios'
import { apiClient } from './client'
import type { ApiEnvelope } from '@/types/api'
import type { RegistryResponse } from '@/types/strategyRegistry'

/**
 * spec 003 전략·파라미터 등록표 조회 API.
 *
 * 계약은 `specs/003-strategy-settings/contracts/strategy-registry-api.md`다. 이 모듈은 주소와 응답
 * 껍데기만 알고 **전략 지식은 갖지 않는다** — 전략 ID·이름·축·기본값은 전부 응답에서 읽는다(FR-005).
 *
 * <p><b>실패를 값으로 대신 채우지 않는다.</b> 조회가 실패하면 오류를 올리고, 화면은 카드를 하나도
 * 고를 수 없는 상태로 두고 다시 시도할 방법을 보인다. 임시 값이나 예비 등록표로 대신 동작하는
 * 경로를 이 모듈에 두지 않는다(FR-018).
 */

const basePath = '/strategies/registry'

/** 003 조회에서 화면이 이름으로 묻는 오류. 목록에 없는 이름도 그대로 싣는다. */
export type StrategyRegistryErrorCode = 'REGISTRY_UNAVAILABLE'

/**
 * 실패의 성격. `REGISTRY_UNAVAILABLE`과 네트워크 실패를 구분하려고 둔다.
 *
 * 서버가 이유를 말한 실패(`api`)와 서버에 닿지도 못한 실패(`network`)는 사용자가 할 수 있는 일이
 * 다르다. 둘을 한 문구로 묶으면 화면이 연결 문제를 등록표 문제로 잘못 안내한다.
 */
export type StrategyRegistryErrorKind = 'api' | 'network' | 'canceled' | 'unexpected'

/**
 * 화면이 분기할 수 있는 003 등록표 조회 오류.
 *
 * `code`는 서버가 준 문자열 그대로다 — 아는 이름이 아니어도 그대로 싣는다. 모르는 코드를 아는
 * 코드로 접으면 화면이 잘못된 안내를 하게 된다(`api/paramSearch.ts`의 `ParamSearchError`와 같은 까닭).
 */
export class StrategyRegistryError extends Error {
  readonly kind: StrategyRegistryErrorKind
  readonly code: string | null
  readonly status: number | null

  constructor(
    kind: StrategyRegistryErrorKind,
    message: string,
    options: { code?: string | null; status?: number | null; cause?: unknown } = {},
  ) {
    super(message, { cause: options.cause })
    this.name = 'StrategyRegistryError'
    this.kind = kind
    this.code = options.code ?? null
    this.status = options.status ?? null
  }

  /** 화면이 자주 묻는 질문 하나를 이름으로 둔다. 오타는 여기서 걸린다. */
  is(code: StrategyRegistryErrorCode): boolean {
    return this.code === code
  }

  /** 앞선 요청을 취소해 생긴 오류인가. 사용자에게 보일 실패가 아니다. */
  get canceled(): boolean {
    return this.kind === 'canceled'
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** 응답 본문에서 `{ code, message }`를 읽는다. 모양이 다르면 `null`이다. */
function readErrorBody(body: unknown): { code: string; message: string } | null {
  if (!isRecord(body) || !isRecord(body.error)) return null
  const { code, message } = body.error
  if (typeof code !== 'string' || typeof message !== 'string') return null
  return { code, message }
}

const NETWORK_MESSAGE = '서버에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.'
const UNEXPECTED_MESSAGE = '전략 설정을 불러오지 못했습니다.'

/**
 * Axios 오류를 화면이 분기할 수 있는 003 오류로 바꾼다.
 *
 * <p><b>취소를 먼저 가려낸다. 순서를 바꾸면 안 된다.</b> axios 1.x의 `CanceledError`는
 * `AxiosError`를 상속하므로 `isCancel`과 `isAxiosError`가 <b>둘 다 참</b>이고 `response`도 없다
 * (`AbortController.abort()`가 실제로 던지는 것이 이것이며 `code`는 `ERR_CANCELED`다). 아래
 * `isAxiosError` 분기를 먼저 두면 취소가 응답 없는 실패로 흘러들어 "서버에 연결할 수 없습니다"가
 * 되고, 사용자는 자기가 부르지도 않은 실패 안내를 받는다. 실패 횟수를 세는 호출자라면 재시도까지
 * 권하게 된다.
 *
 * <p>이 순서를 뒤집어도 타입 검사와 lint 는 통과한다. 지키는 것은
 * `strategyRegistry.test.ts`의 "취소를 네트워크 실패와 구분한다"와 그 짝인 "응답이 없는 실패는
 * 연결 문제로 읽는다" 두 검사뿐이다.
 */
export function normalizeStrategyRegistryError(error: unknown): StrategyRegistryError {
  if (error instanceof StrategyRegistryError) return error

  if (axios.isCancel(error)) {
    return new StrategyRegistryError('canceled', '조회를 취소했습니다.', { cause: error })
  }

  if (axios.isAxiosError(error)) {
    if (!error.response) {
      return new StrategyRegistryError('network', NETWORK_MESSAGE, { cause: error })
    }

    const body = readErrorBody(error.response.data)
    return new StrategyRegistryError('api', body?.message ?? UNEXPECTED_MESSAGE, {
      code: body?.code ?? null,
      status: error.response.status,
      cause: error,
    })
  }

  return new StrategyRegistryError('unexpected', UNEXPECTED_MESSAGE, { cause: error })
}

/**
 * 성공 껍데기를 벗긴다. `success: false`가 200으로 오는 경우도 오류로 다룬다 — 껍데기를 믿고
 * `data`를 그대로 쓰면 `null`이 화면까지 내려간다.
 */
function unwrap(body: ApiEnvelope<RegistryResponse>): RegistryResponse {
  if (!body.success) {
    throw new StrategyRegistryError('api', body.error.message, { code: body.error.code })
  }
  if (body.data === null) {
    throw new StrategyRegistryError('unexpected', '응답 본문이 비어 있습니다.')
  }
  return body.data
}

/**
 * 등록표를 한 벌 받아 온다. 로그인은 필요하지 않다(014).
 *
 * <p><b>`universeSize`를 주면 서버가 유효성 제약을 미리 적용해</b> 지금 조건에서 고를 수 없는
 * 전략과 후보를 `selectable: false`로 표시해 준다. 주지 않으면 제약을 적용하지 않은 전체
 * 등록표가 온다. 화면이 그 판정을 대신 계산하지 않는다.
 *
 * <p>`signal`은 다시 불러올 때 앞선 요청을 끊는 용도다. 끊긴 요청은 `kind`가 `canceled`이며
 * 사용자에게 보일 실패가 아니다.
 */
export async function fetchStrategyRegistry(
  universeSize?: number,
  signal?: AbortSignal,
): Promise<RegistryResponse> {
  const params = new URLSearchParams()
  if (universeSize !== undefined) params.set('universeSize', String(universeSize))
  const query = params.toString()

  try {
    const response = await apiClient.get<ApiEnvelope<RegistryResponse>>(
      query ? `${basePath}?${query}` : basePath,
      { signal },
    )
    return unwrap(response.data)
  } catch (error) {
    throw normalizeStrategyRegistryError(error)
  }
}

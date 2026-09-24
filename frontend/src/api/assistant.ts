import axios from 'axios'
import { ApiClientError, apiClient, runCsrfProtectedMutation } from './client'
import type { AssistantAnswer, AssistantCapabilities, AssistantContext } from '@/types/assistant'
import type { ResultEnvelope } from '@/types/result'

/**
 * 화면 맥락 도우미(spec 015) 호출. 계약 §1.
 *
 * <p>화면이 판단에 쓰는 것은 상태 코드·오류 코드·`retryAfterSeconds`다. 429는 다시 가능한 시점을 보이고,
 * 503은 고정 문구와 재시도 버튼이다. 취소된 요청(`AbortSignal`)은 오류로 바꾸지 않고 그대로 던진다 —
 * 화면이 떠난 요청의 답을 새 화면에 보이지 않기 위한 취소이지 실패가 아니다.
 */
export class AssistantError extends Error {
  readonly code: string | null
  readonly status: number | null
  readonly retryAfterSeconds: number | null

  constructor(
    message: string,
    options: {
      code?: string | null
      status?: number | null
      retryAfterSeconds?: number | null
      cause?: unknown
    } = {},
  ) {
    super(message, { cause: options.cause })
    this.name = 'AssistantError'
    this.code = options.code ?? null
    this.status = options.status ?? null
    this.retryAfterSeconds = options.retryAfterSeconds ?? null
  }

  /** 모델 장애·시간 초과·연결 실패처럼 사용자가 다시 시도해 볼 만한 실패 */
  get retryable(): boolean {
    return this.status === null || this.status === 503
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function readErrorBody(
  body: unknown,
): { code: string; message: string; retryAfterSeconds: number | null } | null {
  if (!isRecord(body) || !isRecord(body.error)) return null
  const { code, message, details } = body.error
  if (typeof code !== 'string' || typeof message !== 'string') return null
  const retryAfter = isRecord(details) ? details.retryAfterSeconds : undefined
  return {
    code,
    message,
    retryAfterSeconds: typeof retryAfter === 'number' ? retryAfter : null,
  }
}

const unreachableMessage = '서버에 연결할 수 없습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.'
const unexpectedMessage = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'

export function normalizeAssistantError(error: unknown): AssistantError {
  if (error instanceof AssistantError) return error
  if (error instanceof ApiClientError && axios.isAxiosError(error.cause)) {
    return normalizeAssistantError(error.cause)
  }
  if (axios.isAxiosError(error)) {
    if (!error.response) return new AssistantError(unreachableMessage, { cause: error })
    const body = readErrorBody(error.response.data)
    return new AssistantError(body?.message ?? unexpectedMessage, {
      code: body?.code ?? null,
      status: error.response.status,
      retryAfterSeconds: body?.retryAfterSeconds ?? null,
      cause: error,
    })
  }
  if (error instanceof ApiClientError) {
    return new AssistantError(error.message, { code: error.code, cause: error })
  }
  return new AssistantError(unexpectedMessage, { cause: error })
}

function unwrap<T>(body: ResultEnvelope<T>): T {
  if (!body.success || body.data === null) {
    throw new AssistantError(body.success ? '응답 본문이 비어 있습니다.' : body.error.message, {
      code: body.success ? null : body.error.code,
    })
  }
  return body.data
}

/** 플래그와 무관하게 항상 200이다. 실패하면 꺼진 것으로 본다 — 버튼이 없는 쪽이 안전하다. */
export async function fetchAssistantCapabilities(): Promise<AssistantCapabilities> {
  try {
    const response = await apiClient.get<ResultEnvelope<AssistantCapabilities>>(
      '/assistant/capabilities',
    )
    return unwrap(response.data)
  } catch {
    return { enabled: false, scopes: [] }
  }
}

export async function askAssistant(
  question: string,
  context: AssistantContext,
  signal?: AbortSignal,
): Promise<AssistantAnswer> {
  try {
    const response = await runCsrfProtectedMutation(() =>
      apiClient.post<ResultEnvelope<AssistantAnswer>>(
        '/assistant/questions',
        { question, context },
        { signal },
      ),
    )
    return unwrap(response.data)
  } catch (error) {
    if (axios.isCancel(error) || (error instanceof ApiClientError && axios.isCancel(error.cause))) {
      throw error
    }
    throw normalizeAssistantError(error)
  }
}

export function isAssistantRequestCancelled(error: unknown): boolean {
  return axios.isCancel(error) || (error instanceof ApiClientError && axios.isCancel(error.cause))
}

import axios from 'axios'
import { apiClient } from './client'
import type { ApiEnvelope } from '@/types/api'
import type { LearningTermEntry } from '@/types/learning'

/**
 * 진단 화면 용어 도움말의 조회 통로(013 term-help-ui §1, 003 FR-016).
 *
 * <p><b>013이 구현되면 이 파일만 바꾼다.</b> 003 T057a가 도움말 조회를 이 한 파일에 격리했고, 실제
 * 구현은 013이 소유한다. 002·003·005 화면은 이 함수만 부르고 주소나 HTTP 코드를 알지 않는다 —
 * `hooks/useSectorSelection.ts`가 002 경계를 한 파일에 모은 것과 같은 방식이다.
 *
 * <p><b>예외를 던지지 않고 세 상태 중 하나로 돌려준다.</b> '설명 준비 중'과 '불러오지 못함'은 사용자가
 * 할 수 있는 일이 달라 다른 문장이 필요하고(013 FR-010), 그 구분을 부르는 화면마다 다시 하면
 * 한쪽만 틀린다.
 *
 * <p><b>재시도하지 않는다.</b> 다시 시도는 사용자가 누를 때 다시 부른다(013 research R10).
 */

export type GlossaryLookup =
  | { status: 'ready'; entry: LearningTermEntry }
  /** 404 `LEARNING_TERM_NOT_READY`. 게시된 설명이 없다 */
  | { status: 'notReady' }
  /** 그 밖의 모든 실패. 취소도 여기에 든다 — 부른 쪽이 이미 결과를 버린 요청이다 */
  | { status: 'failed' }

const NOT_READY_CODE = 'LEARNING_TERM_NOT_READY'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** 설명 한 건의 모양이 계약과 맞는가. 빠진 필드를 빈 글로 채워 보이지 않는다. */
function isTermEntry(value: unknown): value is LearningTermEntry {
  return (
    isRecord(value) &&
    typeof value.termId === 'string' &&
    typeof value.label === 'string' &&
    typeof value.sentence === 'string' &&
    typeof value.articleId === 'string' &&
    typeof value.articleTitle === 'string'
  )
}

/**
 * 오류 본문의 코드를 읽는다. 계약 예시는 `{ code, message }`이고 공통 봉투는 `{ error: { code } }`라
 * 둘 다 받는다.
 */
function readErrorCode(body: unknown): string | null {
  if (!isRecord(body)) return null
  if (typeof body.code === 'string') return body.code
  if (isRecord(body.error) && typeof body.error.code === 'string') return body.error.code
  return null
}

/**
 * 용어 설명 한 건을 받아 온다.
 *
 * @param termId 표시 이름이 아니라 고정 식별자다(013 data-model §8)
 * @param signal 도움말을 닫거나 다시 열 때 앞선 요청을 끊는 용도
 */
export async function fetchGlossaryEntry(
  termId: string,
  signal?: AbortSignal,
): Promise<GlossaryLookup> {
  try {
    const response = await apiClient.get<ApiEnvelope<LearningTermEntry> | LearningTermEntry>(
      `/learning/terms/${encodeURIComponent(termId)}`,
      { signal },
    )
    const body = response.data
    const entry = isRecord(body) && 'success' in body ? body.data : body
    return isTermEntry(entry) ? { status: 'ready', entry } : { status: 'failed' }
  } catch (error) {
    if (
      axios.isAxiosError(error) &&
      !axios.isCancel(error) &&
      error.response?.status === 404 &&
      readErrorCode(error.response.data) === NOT_READY_CODE
    ) {
      return { status: 'notReady' }
    }
    return { status: 'failed' }
  }
}

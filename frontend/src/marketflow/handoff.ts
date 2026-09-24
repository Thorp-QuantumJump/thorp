import type { MarketFlowFieldId } from '@/types/marketFlow'

/**
 * 시장 흐름에서 바깥 화면으로 넘기는 값을 만든다.
 *
 * 010(경제환경 검색)·011(전략 비교)의 목적지 주소를 여기서 만든다. 주소를 만드는 자리를 한 곳에
 * 두어 두 벌의 계약이 생기지 않게 한다. 011은 진입 계약(`contracts/entry-handoff.md` §2)이 주소를
 * 정했고, 010은 화면이 분야·기준월을 주소에서 읽는다.
 *
 * 값을 만들 수 없으면 `null`이다. 빈 문자열이나 대체값으로 채우지 않는다(원칙 VI).
 */

/** 010으로 넘기는 값. 분야 하나와 그 분야의 기준월 T_g다(FR-009). */
export type SimilarPeriodHandoff = {
  fieldId: MarketFlowFieldId
  /** 그 분야의 기준월 `YYYY-MM`. 분야마다 다를 수 있다. */
  referenceMonth: string
}

/**
 * **섹터를 받지 않는다.** 이것이 FR-016의 "섹터를 골라도 넘기는 분야·기준월은 달라지지
 * 않는다"를 지키는 방식이다. 인자에 자리가 없으면 실수로도 실을 수 없다.
 */
export function buildSimilarPeriodHandoff(field: {
  fieldId: MarketFlowFieldId
  referenceMonth: string | null
}): SimilarPeriodHandoff | null {
  if (field.referenceMonth === null) return null
  return { fieldId: field.fieldId, referenceMonth: field.referenceMonth }
}

/**
 * 010 검색 화면의 주소다.
 *
 * 분야와 기준월을 **주소에** 싣는다. 상태로 넘기면 새로고침에 사라져 사용자가 같은 화면을 다시
 * 열 수 없고, 주소를 건네 같은 검색을 보여줄 수도 없다. 011이 `?case=`를 주소로 다니는 것과
 * 같은 방식이다.
 *
 * 값을 만들 수 없으면 `null`이라 부르는 쪽이 진입점을 열지 않는다.
 */
export function buildRegimeSearchPath(handoff: SimilarPeriodHandoff | null): string | null {
  if (handoff === null) return null
  const query = new URLSearchParams({ field: handoff.fieldId, month: handoff.referenceMonth })
  return `/regime-search?${query.toString()}`
}

/**
 * 사례 식별자를 만든다. `{010 searchId}~{endMonth}`다(011 계약 §0).
 *
 * 010의 `searchId`가 곧 011이 말하는 `snapshotKey`다. **011은 그 키를 분해하지 않는다** — 조각의 뜻은
 * 010이 소유한다. 서버는 **마지막** `~`로 나눠 읽으므로 키 안에 구분자가 있으면 값을 만들지 않는다.
 */
export function buildCaseId(searchId: string, endMonth: string): string | null {
  if (searchId === '' || searchId.includes('~')) return null
  if (!/^\d{4}-\d{2}$/.test(endMonth)) return null
  return `${searchId}~${endMonth}`
}

/**
 * 011 시작 화면의 주소다(진입 계약 §2).
 *
 * `sector`는 002 화면의 **초기 선택**으로만 쓰이며 사례 순위와 무관하다(FR-005). 010 사례 상세에서
 * 들어오는 길은 섹터를 알 수 없어 싣지 않는다.
 */
export function buildRegimeLinkStartPath(input: {
  caseId: string
  sectorCode?: string | null
}): string {
  const query = new URLSearchParams({ case: input.caseId })
  if (input.sectorCode) query.set('sector', input.sectorCode)
  return `/regime-link/start?${query.toString()}`
}

/**
 * `caseId`를 되돌린다. 모양이 아니면 `null`이고 부르는 쪽이 안내로 바꾼다(011 계약 §0).
 *
 * **마지막 `~`로 나눈다** — 서버와 같은 규약이다. 앞에서 나누면 snapshot 키가 잘린다.
 */
export function parseCaseId(value: string | null | undefined): { snapshotKey: string; endMonth: string } | null {
  if (typeof value !== 'string' || value === '') return null
  const at = value.lastIndexOf('~')
  if (at <= 0 || at === value.length - 1) return null
  const endMonth = value.slice(at + 1)
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(endMonth)) return null
  return { snapshotKey: value.slice(0, at), endMonth }
}

/**
 * 011 시작 화면이 002로 넘길 주소다(진입 계약 §2).
 *
 * `case`는 003까지 주소로 다니고, `sector`는 002의 초기 선택으로만 쓰인다.
 */
export function buildSectorSelectionPath(caseId: string, sectorCode: string | null): string {
  const query = new URLSearchParams({ case: caseId })
  if (sectorCode) query.set('sector', sectorCode)
  return `/diagnosis/sectors?${query.toString()}`
}

/**
 * 002가 003으로 넘어갈 때 사례를 주소에 유지한다(진입 계약 §2).
 *
 * <p><b>선택 인계 방식은 그대로 둔다.</b> 섹터·비중은 지금처럼 같은 탭의 `sessionStorage`로 넘기고
 * `case`만 주소에 남긴다. 새 진단을 시작할 때 이전 사례의 기간 제약이 말없이 남지 않게 하려는
 * 것이며, 새로고침에도 남아야 하므로 상태가 아니라 주소다.
 */
export function appendCaseToPath(path: string, caseId: string | null): string {
  if (caseId === null || caseId === '') return path
  return `${path}?${new URLSearchParams({ case: caseId }).toString()}`
}

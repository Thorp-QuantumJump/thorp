/**
 * 시장 흐름에서 바깥 화면으로 넘기는 값을 만든다.
 *
 * 011(전략 비교)의 목적지 주소를 여기서 만든다. 주소를 만드는 자리를 한 곳에 두어 두 벌의 계약이
 * 생기지 않게 한다. 011은 진입 계약(`contracts/entry-handoff.md` §2)이 주소를 정했다. 010(경제환경 검색)의
 * 내용은 시장 흐름의 분야 상세 안에서 바로 보이므로(009 FR-019, 2026-10-07) 010으로 넘기는 주소는 없다.
 *
 * 값을 만들 수 없으면 `null`이다. 빈 문자열이나 대체값으로 채우지 않는다(원칙 VI).
 */

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

/**
 * 010 화면이 쓰는 고정 문구를 한곳에 모은다.
 *
 * <p>spec FR-022 ②는 사례·검색 상태·안내 영역의 문장이 <b>전부</b> 사전 계산 산출물이거나
 * 계약이 고정한 문구여야 하고, 화면 코드에 하드코딩된 결과 문구가 0건이어야 한다고 정한다.
 * 확률 변환·원인 단정·추천은 표현이 무한해 목록으로 막을 수 없으므로 <b>지어낼 자리를 없애는</b>
 * 쪽으로 막는다 — 그래서 결과 문장은 API 응답에서만 오고, 응답이 주지 않는 나머지 문장은
 * 화면 파일이 아니라 이 파일에만 둔다. 검사는 `indicatorAgnostic.test.tsx`가 한다.
 */

/** 기준월을 직접 바꿔 이전 검색과 달라졌을 때(contracts/regime-search-api.md §5, spec FR-031). */
export const baselineMonthChangedNote = (baselineMonth: string) =>
    `기준월을 ${baselineMonth}로 바꾸어 새 검색을 열었습니다.`

/** 기준월은 같고 검색 방식만 바뀌었을 때(contracts/regime-search-api.md §5, spec FR-031). */
export const searchModeChangedNote = '검색 방식을 바꾸어 새 검색을 열었습니다.'

/** 008이 예시 자료로 도는 동안 모든 검색 결과에 붙는다(spec FR-038, 008 FR-022). */
export const sampleDataNote = '이 결과는 아직 확정되지 않은 예시 자료로 계산되었습니다.'

/** 동일가중이 설계 선택임을 밝힌다(spec FR-010). 중요성이 같다는 뜻으로 적지 않는다. */
export const equalWeightNote = '분야별 거리를 같은 비중으로 합치는 것은 Thorp의 설계 선택입니다.'

/** 저장된 척도가 없어 손계산 대조를 할 수 없는 지표에 붙는다(spec FR-028). */
export const scaleMissingNote = '공통 척도 정보를 확인할 수 없습니다.'

/** 시대별 보조 설명이 없는 지표에 붙는다 — 사례와 순위는 그대로다(spec FR-017). */
export const eraPositionMissingNote = '당시 평소 대비 위치를 계산할 수 없습니다.'

/** 무엇을 고르면 무엇을 볼 수 있는지 안내한다 — 결과를 서술하지 않는다. */
export const pageLead = '경제 분야를 고르면 과거의 가까운 관측 구간을 살펴볼 수 있습니다.'

/** 분야 목록을 불러오는 동안 화면이 자기 상태를 말한다. */
export const loadingFields = '검색할 분야를 불러오는 중입니다.'

/** 검색 결과를 불러오는 동안 화면이 자기 상태를 말한다. */
export const loadingCases = '과거 사례를 불러오는 중입니다.'

/** 사례 상세를 불러오는 동안 화면이 자기 상태를 말한다. */
export const loadingCaseDetail = '사례 상세를 불러오는 중입니다.'

/** 조회 실패는 빈 결과가 아니다 — 빈 상태와 다른 화면으로 알린다(spec FR-034). */
export const loadErrorTitle = '조회에 실패했습니다'

/** 다른 검색의 사례 상세를 섞어 보이지 않는다(spec FR-029). */
export const detailMismatchMessage = '요청한 사례 상세와 응답이 일치하지 않습니다.'

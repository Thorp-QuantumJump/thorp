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

/**
 * v3 종합의 블록 구성과 동일가중이 설계 선택임을 밝힌다(spec 010 v3 개정안 §3.2·§5.2).
 * 통계적 요인이나 KDI 공식 판단처럼 읽히지 않게 쓴다.
 */
export const blockWeightNote =
    '지표를 경제 흐름별 블록으로 묶고, 블록 안 지표와 블록 사이를 같은 비중으로 합치는 것은 Thorp의 설계 선택입니다.'

/** 당시 맥락 링크가 순위의 근거로 읽히지 않게 한다(spec 010 v3 개정안 §4). */
export const contextNote = '같은 기간에 나온 공식 발간물과 사건입니다. 검색에 쓰지 않았고, 사례를 고른 이유가 아닙니다.'

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

/**
 * 사전 계산 결과가 아직 없을 때(spec FR-030).
 *
 * <p>계산된 기준월이 하나도 없으면 고를 분야가 없다. 빈 선택 상자만 보이면 사용자는 자기가
 * 잘못 눌렀다고 읽는다 — 화면이 왜 비었는지 말해야 한다(원칙 VI). 배치를 어떻게 돌리는지는
 * 운영의 일이라 화면에 적지 않는다.
 */
export const baselinesNotReady =
    '아직 비교할 과거 자료가 준비되지 않았습니다. 자료가 들어오면 분야를 골라 검색할 수 있습니다.'

/** 조회 실패는 빈 결과가 아니다 — 빈 상태와 다른 화면으로 알린다(spec FR-034). */
export const loadErrorTitle = '조회에 실패했습니다'

/** 다른 검색의 사례 상세를 섞어 보이지 않는다(spec FR-029). */
export const detailMismatchMessage = '요청한 사례 상세와 응답이 일치하지 않습니다.'

/** 내 기록의 '다시 보기'로 저장된 검색을 열었을 때(006 research R17, FR-006 "동일 버전 조회"). */
export const savedSearchNote = '저장된 검색을 다시 봅니다(동일 버전 조회). 새로 검색하면 최신 판이 열립니다.'

/** 로그인 사용자의 검색을 내 기록에 남기지 못했을 때(006 T037). 검색 결과는 그대로다 — 기록만 남지 않았다. */
export const recordFailedNote = '이 검색을 내 기록에 남기지 못했습니다. 결과는 그대로 볼 수 있습니다.'

/** 교정된 결과에서 따로 여는 목록의 이름. 서버가 `referenceLabel`을 주지 않을 때만 쓴다(spec FR-021). */
export const referenceLabel = '기준 미충족 참고 사례'

/** 참고 사례를 열었을 때 붙는 안내 — 기준을 넘지 못했다는 사실만 말하고 등급을 매기지 않는다(FR-018·FR-021). */
export const referenceNote = 'Thorp가 정한 유사성 기준을 넘지 못한, 가까운 순서의 참고 사례입니다.'


/*
  아래는 시장 흐름 분야 상세 안의 '지금과 비슷했던 과거' 줄과 종합 비교 목록이 쓴다(009 FR-019). 그 자리도 010 결과를
  보이는 영역이라 같은 경계(FR-022 ②)를 따른다.
*/

/** 분야 검색의 유사성이 그 분야 지표만으로 잰 것임을 밝힌다(009 SC-006, 010 U01). 경제 전체가 같았다고 읽히지 않게 한다. */
export const fieldOnlySimilarityNote = (fieldName: string) =>
    `같은 지표의 3개월 경로를 달 순서대로 맞대어 잰 거리입니다. ${fieldName} 분야만의 유사성이며 경제 전체가 같았다는 뜻이 아닙니다.`

/** 010이 비교한 기준월이 그래프의 대상월과 다를 때(009 FR-009). 맞는 달에만 당시 값을 겹친다는 사실을 함께 적는다. */
export const overlayBaselineNote = (baselineMonth: string) =>
    `과거 비교는 ${baselineMonth} 기준입니다. 그래프와 달이 맞는 자리에만 당시 값을 겹칩니다.`

/** 보통 범위 띠가 무엇인지 밝힌다(009 FR-019, spec FR-028의 공통 척도). 좋고 나쁨의 기준으로 읽히지 않게 쓴다. */
export const usualRangeNote = (window: string) =>
    `${window} 자료의 중앙값 ± 산포입니다. 지금과 과거 사례를 같은 잣대로 재는 공통 척도이며, 좋고 나쁨의 기준이 아닙니다.`

/** 분야 검색을 받지 못했을 때. 분야 지표는 막히지 않는다는 사실을 함께 알린다(009 FR-019, spec FR-034). */
export const pastCasesLoadFailed = '과거 사례를 불러오지 못했습니다. 분야 지표는 그대로 볼 수 있습니다.'

/** 종합 검색을 받지 못했을 때(spec FR-034). */
export const compositeLoadFailed = '과거 사례를 불러오지 못했습니다.'

/** 고른 사례의 지표별 당시 경로를 받는 동안(009 FR-019). */
export const loadingPastPath = '당시 경로를 불러오는 중입니다.'

/** 고른 사례의 지표별 당시 경로를 받지 못했을 때. 지금 경로는 그대로 보인다(009 FR-019, spec FR-034). */
export const pastPathLoadFailed = '당시 경로를 불러오지 못했습니다.'

/** 상세를 열 수 없는 사례를 골랐을 때(spec FR-029). 순위와 문장은 그대로 보인다. */
export const pastPathUnavailable = '이 사례는 지표별 당시 경로를 볼 수 없습니다.'

/** 종합 비교를 고른 상자의 머리 문장(009 FR-019, spec US3). 무엇을 볼 수 있는지만 말하고 결과를 서술하지 않는다. */
export const compositeLead = '일곱 분야를 함께 견준 가까운 과거입니다. 분야 하나의 지표와 견주려면 분야 메뉴에서 고르세요.'

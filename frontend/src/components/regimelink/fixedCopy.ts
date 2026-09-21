/**
 * 011 결과 화면의 고정 문구를 한곳에 모은다.
 *
 * 010 `components/regime/fixedCopy.ts`와 같은 이유다 — 결과 문장을 화면 컴포넌트가 지어내지
 * 못하게 자리를 하나로 줄인다(spec FR-014·FR-016, SC-007). 금지 표현 검사(T081)는 이 파일도
 * 포함해 `components/regimelink/`를 통째로 훑는다.
 */

/** 모든 응답의 `basis.kind`가 `HISTORICAL_REVISION`일 때만 보인다(FR-015). */
export const basisNote = '최신 개정자료를 이용한 역사 탐색'

/** 결과 영역 머리의 고정 문구(T064). 사후 구간을 매매 재현으로 읽지 않게 한다. */
export const historicalOnlyNote =
  '역사 설명용 기준 — 실제 발표 직후 매매를 재현한 것이 아닙니다.'

/** 현금 시작 시나리오가 연속 운용과 다른 이유(FR-010). */
export const freshCashNote = '보유 상태와 거래 시점이 달라 연속 운용 결과와 다릅니다.'

/** KOSPI 두 값에 붙는 표기(FR-013). 차이·승패를 말하지 않는다. */
export const kospiLabel = '참고 정보'

/** 회전율의 단위를 밝힌다 — 구간 길이가 달라도 같은 자로 놓인다. */
export const turnoverBasisNote = '회전율은 두 구간 모두 연환산 값입니다.'

/** 손실 기여의 뜻(T061). 이익 종목도 같은 자리에 들어간다. */
export const lossContributionNote =
  '구간 손익을 종목별로 나눈 값입니다. 이익이 난 종목도 함께 들어갑니다.'

/** 21거래일 보조 평가가 길이가 다른 사례를 놓는 공통 자임을 밝힌다(T073). */
export const auxiliaryWindowNote =
  '사례마다 사후 구간 길이가 달라 21거래일 값을 공통 자로 함께 봅니다.'

/** 필터 뒤 남는 사례가 없을 때. 대체 사례를 만들지 않는다(FR-019). */
/** FR-018의 한 문장. 결과 옆에 두고 전체 기간 결과 비교는 계속 보인다(T073). */
export const fewCasesNote = '비교할 수 있는 사례가 적습니다. 전체 기간 결과와 함께 봅니다.'

export const postIncompleteAsideNote =
  '이 사례는 사후 구간이 아직 다 차지 않아 21거래일 값을 공통 자로 함께 봅니다.'

export const unstableSearchNote = '검색 상태가 확정되지 않아 가까운 순서만 제공됩니다.'

export const emptyFilteredNote =
  '선택한 ETF로 볼 수 있는 사례가 없습니다. 전체 경제환경 탐색으로 전환하면 판정과 함께 모두 볼 수 있습니다.'

/** 전체 탐색으로 전환하는 버튼의 이름. */
export const fullSearchToggleLabel = '전체 경제환경 탐색으로 전환'

export const filteredViewLabel = '선택 ETF로 비교 가능한 사례만'

export const fullViewLabel = '전체 표시 후보'

/** 자료 부족 판정의 사유 문구(T071). '다시 시도'가 없는 자리다. */
export const dataInsufficientReason: Record<string, string> = {
  UNLISTED: '이 구간에 상장되지 않은 종목이 있어 결과를 낼 수 없습니다.',
  WARMUP_SHORT: '워밍업에 필요한 거래일이 모자라 결과를 낼 수 없습니다.',
  SECTOR_UNMAPPED: '이 실행의 섹터와 종목 대응을 확인할 수 없어 결과를 낼 수 없습니다.',
}

export const dataInsufficientTitle = '자료 부족'

/** 사후 자료가 21거래일에 못 미치는 자리(T071). 수치를 내지 않는다. */
export const postIncompleteTitle = '사후 자료 미완성'

export const postIncompleteReason: Record<string, string> = {
  RECENT: '관측 종료 이후 거래일이 아직 쌓이는 중입니다.',
  DELISTED: '구간 도중 상장이 폐지되어 거래일이 더 쌓이지 않습니다.',
}

export const outOfPeriodTitle = '분석 기간 밖'

export const outOfPeriodNote = '이 사례의 사후 구간이 분석 기간에 들어 있지 않습니다.'

export const fullPeriodConfirmMessage = '이 사례를 보려면 전체 기간으로 다시 계산합니다.'

/** 체결 기록이 없는 옛 실행(T092). 값을 추정해 채우지 않는다. */
export const fillsMissingConfirmMessage = '이 결과는 사후 구간을 보려면 다시 계산합니다.'

export const confirmAcceptLabel = '다시 계산하고 보기'

export const confirmRejectLabel = '그대로 두기'

export const failureTitle = '결과를 낼 수 없습니다'

/** 결정적 실패의 두 번째 실패에서 '다시 시도' 대신 보이는 안내(T071). */
export const notRetryableNote =
  '일시적인 문제가 아닙니다. 이 사례의 전략 결과는 지금 제공할 수 없습니다.'

export const retryLabel = '다시 시도'

/** 시장 흐름에서 막 접수한 기준 실행을 기다리는 동안(T048). 005와 같은 방식으로 보인다. */
export const baseRunPendingTitle = '분석을 계산하는 중입니다'

/** 기준 실행이 실패·취소로 끝났을 때. 사실만 말하고 결과 주소로 보낸다(T048). */
export const baseRunFailedNote = '이 분석은 끝나지 않았습니다. 결과 화면에서 상태를 확인해 주세요.'

export const pendingTitle = '계산을 기다리는 중입니다'

export const runningTitle = '계산하는 중입니다'

export const fullPeriodResultLabel = '전체 기간 결과 보기'

export const fullPeriodSummaryTitle = '전체 기간 결과'

export const newsPanelTitle = '당시 뉴스'

export const newsLoadFailedNote = '당시 뉴스를 불러오지 못했습니다. 전략 결과는 그대로 볼 수 있습니다.'

export const scenarioToggleLabel = '그날 현금으로 새로 시작'

export const baseTokenMissingNote =
  '어느 분석의 결과를 볼지 알 수 없습니다. 진단을 처음부터 시작해 주세요.'

export const startDiagnosisLabel = '진단 시작하기'

export const snapshotNotFoundNote = '이 검색에서 그 사례를 찾을 수 없어 분야 선택으로 돌아갑니다.'

export const expiredNote =
  '이 결과 주소는 만료되었습니다. 로그인하면 기록에서 다시 열 수 있습니다.'

export const fieldPickerTitle = '경제 분야를 고르세요'

export const compositeOptionLabel = 'Thorp 종합 비교'

export const baselineMonthLabel = '기준월'

export const loadingAvailability = '사례 목록을 불러오는 중입니다.'

export const loadFailedTitle = '조회에 실패했습니다'

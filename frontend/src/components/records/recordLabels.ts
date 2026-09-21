import type { MyRecordKind, MyRecordListItem, MyRecordStatus } from '@/types/myRecords'

/**
 * 목록이 쓰는 이름·문장. 상태 이름은 서버 값을 옮긴 것이고, 경제환경 상태는 010의 뜻을 그대로 둔다 —
 * '계산 불가'를 실패로 부르지 않는다(FR-004).
 */

export const KIND_LABEL: Record<MyRecordKind, string> = {
  ANALYSIS: '분석',
  SEARCH_BUNDLE: '탐색 묶음',
  REGIME_SEARCH: '경제환경',
}

export const STATUS_LABEL: Record<MyRecordStatus, string> = {
  COMPLETED: '완료',
  RUNNING: '진행 중',
  FAILED: '실패',
  CANCELLED: '취소',
  PARTIAL: '부분 완료',
  NOT_COMPUTABLE: '계산 불가',
  NEAREST_ONLY: '가까운 순서만',
  THRESHOLD_APPLIED: '문턱 적용',
  WITHDRAWN: '더 볼 수 없음',
}

/** 상태 필터 선택지. 기본 보기(완료·문턱 적용)가 앞이다. */
export const STATUS_OPTIONS: MyRecordStatus[] = [
  'COMPLETED',
  'THRESHOLD_APPLIED',
  'RUNNING',
  'PARTIAL',
  'FAILED',
  'CANCELLED',
  'NEAREST_ONLY',
  'NOT_COMPUTABLE',
  'WITHDRAWN',
]

/** 정렬 선택지. 수익률·하락 폭·비용은 없다(research R7, quickstart S13-4). */
export const SORT_OPTIONS = [
  { value: 'SAVED_AT_DESC', label: '저장일 최신순' },
  { value: 'SAVED_AT_ASC', label: '저장일 오래된순' },
  { value: 'NAME_ASC', label: '이름순' },
] as const

export const WITHDRAWN_REASON = '검색 결과가 철회되어 더 볼 수 없습니다.'
export const REOPEN_NOT_YET = "'설정 바꿔 비교'는 다음 묶음에서 연결됩니다."

/** 서버가 준 ISO 시각을 사용자 지역 날짜·시각으로. 지어내지 않고 못 읽으면 원문을 둔다. */
export function formatSavedAt(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

/** '다시 보기' 목적지. 재계산하지 않고 저장된 것을 연다(FR-006). 열 수 없으면 null이다. */
export function reviewHref(item: MyRecordListItem): string | null {
  switch (item.kind) {
    case 'ANALYSIS':
      return `/results/${item.resultToken}?from=records&recordId=${encodeURIComponent(item.recordId)}`
    case 'SEARCH_BUNDLE':
      return `/param-search?searchId=${encodeURIComponent(item.searchId)}`
    case 'REGIME_SEARCH':
      return item.regime.withdrawn
        ? null
        : `/regime-search?searchId=${encodeURIComponent(item.regime.snapshotKey)}`
  }
}

import type { ValidationLabel } from '@/types/paramSearch'

/**
 * 검증 라벨을 화면 문구로 바꾼다.
 *
 * **`UNVALIDATED`는 "통과"가 아니라 "말할 수 없음"이다**(FR-015, 원칙 VII). 평가 분할이 등록되기
 * 전까지 모든 결과가 이 값이므로, 문구가 안심시키는 쪽으로 기울면 화면 전체가 사실과 달라진다.
 */
export function validationLabelText(label: ValidationLabel): string {
  switch (label) {
    case 'SEARCH_RESULT':
      return '탐색 구간 결과'
    case 'HELD_OUT_RESULT':
      return '검증 구간 결과'
    case 'UNVALIDATED':
      return '미검증'
  }
}

/** 사람이 읽는 정수. 자릿수가 큰 후보 수·조합 수가 화면에서 흐트러지지 않게 한다. */
export function formatCount(value: number): string {
  return value.toLocaleString('ko-KR')
}

/** 초 단위 한도를 분·초로 읽는다. 60초 미만은 초로 둔다. */
export function formatSeconds(seconds: number): string {
  if (seconds < 60) return `${formatCount(seconds)}초`

  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return rest === 0 ? `${formatCount(minutes)}분` : `${formatCount(minutes)}분 ${rest}초`
}

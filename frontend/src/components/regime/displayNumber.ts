/**
 * 경제환경 검색 화면의 숫자 표기(S15P21E205-447).
 *
 * API는 수기 대조를 위해 소수 12자리 문자열을 준다(spec FR-028, research R4). 화면에는 소수 첫째 자리만
 * 보이고, 정밀 값은 `title`로 남겨 필요하면 확인할 수 있게 한다. 값을 계산하지 않는다 — 표기만 바꾼다.
 */
const oneDecimal = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 1 })

function tidy(text: string): string {
  return text === '-0' ? '0' : text
}

/** 거리·z·원값 같은 수. 숫자가 아니면 받은 그대로 둔다. */
export function formatDecimal(text: string): string {
  const value = Number(text)
  return Number.isFinite(value) ? tidy(oneDecimal.format(value)) : text
}

/** 0~1 비율(기여·비중)을 백분율로. 0.396402864300 → "39.6%" */
export function formatShare(text: string): string {
  const value = Number(text)
  return Number.isFinite(value) ? `${tidy(oneDecimal.format(value * 100))}%` : text
}

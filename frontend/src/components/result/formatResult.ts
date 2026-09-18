/**
 * 결과 값의 표기. **자릿수만 다듬고 값을 다시 계산하지 않는다.**
 *
 * 응답의 비율·금액은 문자열이다. 비율은 백분율 소수 둘째 자리, 금액은 원 단위로만 보인다.
 * 읽을 수 없는 값은 0으로 바꾸지 않고 받은 문자열을 그대로 둔다 — 0으로 바꾸면 없는 결과를
 * 만들어 낸다(원칙 VI).
 */

/** `"0.166652"` → `"16.67%"`, `"-0.041757"` → `"-4.18%"` */
export function formatRatio(value: string): string {
  const ratio = Number(value)
  if (value.trim() === '' || !Number.isFinite(ratio)) return value
  const percent = (ratio * 100).toFixed(2)
  // 반올림으로 생긴 "-0.00"은 음수가 아니다.
  return `${percent === '-0.00' ? '0.00' : percent}%`
}

const moneyFormat = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 })

/** `"17078.49"` → `"17,078원"` */
export function formatMoney(value: string): string {
  const amount = Number(value)
  if (value.trim() === '' || !Number.isFinite(amount)) return value
  return `${moneyFormat.format(amount)}원`
}

const axisAmountFormat = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 })

/**
 * 그래프 세로축 눈금의 금액. `10500000` → `"1,050만원"`, `125000000` → `"1.25억원"`.
 *
 * <p>응답의 값이 아니라 축이 고른 둥근 눈금 값을 옮긴다. 좁은 여백에 들어가도록 만·억 단위로 줄여
 * 쓴다. 결과의 금액은 이 함수가 아니라 `formatMoney`로 원 단위까지 보인다.
 */
export function formatAxisAmount(value: number): string {
  if (!Number.isFinite(value)) return ''
  const size = Math.abs(value)
  if (size >= 100_000_000) return `${axisAmountFormat.format(value / 100_000_000)}억원`
  if (size >= 10_000) return `${axisAmountFormat.format(value / 10_000)}만원`
  return `${axisAmountFormat.format(value)}원`
}

/** 값이 수로 읽히고 0인가. 읽을 수 없으면 `false`다 */
export function isZeroValue(value: string): boolean {
  return value.trim() !== '' && Number(value) === 0
}

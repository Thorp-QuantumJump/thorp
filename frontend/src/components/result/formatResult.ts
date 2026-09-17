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

/** 값이 수로 읽히고 0인가. 읽을 수 없으면 `false`다 */
export function isZeroValue(value: string): boolean {
  return value.trim() !== '' && Number(value) === 0
}

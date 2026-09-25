import { formatDecimal, formatShare } from './displayNumber'

interface Props {
  /** API가 준 정밀 값(문자열). */
  value: string
  /** `share`면 0~1 비율을 백분율로 보인다. */
  as?: 'decimal' | 'share'
}

/** 소수 첫째 자리로 보이고 정밀 값은 `title`에 둔다(S15P21E205-447). */
export function Num({ value, as = 'decimal' }: Props) {
  return <span title={value}>{as === 'share' ? formatShare(value) : formatDecimal(value)}</span>
}

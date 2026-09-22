/**
 * 금액 칸의 빠른 더하기 단추를 만든다.
 *
 * <p><b>액수를 코드에 적지 않는다.</b> 등록표가 준 간격에서 1·10·100배를 뽑는다(spec 003 FR-005).
 * 지금 등록표의 시작 금액은 간격이 100만원이라 `100만원 / 1,000만원 / 1억원`이 나오고, 등록표가
 * 간격을 바꾸면 단추도 따라 바뀐다.
 */

const MULTIPLIERS = [1, 10, 100] as const

export type QuickAdd = { label: string; amount: number }

/** 세 자리마다 쉼표를 넣는다. 소수부와 음수 부호는 그대로 둔다. */
export function groupThousands(text: string): string {
  const [whole, fraction] = text.split('.')
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return fraction === undefined ? grouped : `${grouped}.${fraction}`
}

/**
 * 금액을 한국식 단위로 짧게 적는다. 단추 이름이 길면 한 줄에 들어가지 않는다.
 *
 * 딱 떨어질 때만 억·만으로 줄인다. `1억 500만`처럼 두 단위를 이어 붙이면 단추 이름이 다시 길어져
 * 줄이는 뜻이 없다.
 */
export function formatKoreanAmount(value: number): string {
  if (value >= 100_000_000 && value % 100_000_000 === 0) {
    return `${groupThousands(String(value / 100_000_000))}억원`
  }
  if (value >= 10_000 && value % 10_000 === 0) {
    return `${groupThousands(String(value / 10_000))}만원`
  }
  return `${groupThousands(String(value))}원`
}

/**
 * 간격의 1·10·100배 가운데 쓸모 있는 것만 고른다.
 *
 * 범위 전체보다 큰 액수는 누를 수 있는 자리가 없으므로 뺀다. 간격이 범위와 맞먹는 축이면
 * 단추가 하나만 남거나 아예 없을 수도 있다 — 그때는 그리지 않는다.
 */
export function buildAmountQuickAdds(step: number, span: number): QuickAdd[] {
  if (!Number.isFinite(step) || step <= 0) return []
  return MULTIPLIERS.map((factor) => step * factor)
    .filter((amount) => amount <= span)
    .map((amount) => ({ label: `+${formatKoreanAmount(amount)}`, amount }))
}

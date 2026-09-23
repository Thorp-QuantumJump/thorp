/**
 * 사례의 관측 구간 3개월을 UTC 경계로 만든다 (T088).
 *
 * 종료월을 포함한 3개월이므로 가장 긴 조합도 92일이고 012가 정한 93일 한계 안이다. 011이 구간을
 * 스스로 넓히지 않는다.
 */
export function observationRange(endMonth: string): { from: string; to: string } {
  const [year, month] = endMonth.split('-').map((part) => Number(part))
  const from = new Date(Date.UTC(year, month - 3, 1, 0, 0, 0))
  const to = new Date(Date.UTC(year, month, 1, 0, 0, 0) - 1000)
  return { from: from.toISOString(), to: to.toISOString() }
}

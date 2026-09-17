/** 표시만 담당한다. 반올림한 값으로 경로나 변화 방향을 계산하지 않는다. */
export function formatObservation(value: number | null): string {
  return value === null ? '자료 없음' : value.toLocaleString('ko-KR', { maximumFractionDigits: 10 })
}

export function formatUpdatedAt(value: string | null): string {
  if (!value) return '확인할 수 없음'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(parsed)
}

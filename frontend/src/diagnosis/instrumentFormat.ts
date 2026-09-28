import type { InstrumentProfileResult } from '@/api/sectors'
import type { InstrumentHolding } from '@/types/sector'

/**
 * 대표 상품 정보를 화면 문자열로 옮기는 규칙이다. 그리는 조각이 여럿이라 규칙을 한곳에 둔다.
 *
 * 값을 가공해 없는 정밀도를 만들지 않는다. 자릿수를 줄이거나 날짜의 시간대를 맞추는 일까지가
 * 여기서 하는 전부다.
 */

/**
 * 비중 문자열을 0과 1 사이 숫자로 옮긴다. 읽을 수 없으면 0으로 본다.
 *
 * 막대 너비와 표시 비중이 **같은 값**을 쓰도록 여기 한 번만 옮긴다. 둘이 따로 계산하면 눈에
 * 보이는 크기와 적힌 숫자가 갈라진다.
 */
export function toFraction(weight: string): number {
  const value = Number(weight)
  if (!Number.isFinite(value) || value < 0) return 0
  return Math.min(value, 1)
}

/**
 * 거래대금을 사람이 읽는 단위로 줄여 보인다.
 *
 * `357400000000`처럼 0이 열 개 넘게 붙은 숫자는 자릿수를 세어야 크기를 알 수 있다. 억과 조로
 * 줄이고 천 단위를 끊어 준다.
 *
 * **떨어지는 값에 `.0`을 붙이지 않는다.** `3574.0억`은 소수 한 자리까지 잰 값처럼 보이지만
 * 실제로는 그냥 3574억이다. 없는 정밀도를 흉내 내지 않는다.
 */
export function formatWon(amount: string): string | null {
  const value = Number(amount)
  if (!Number.isFinite(value)) return null

  const trillion = 1_000_000_000_000
  const hundredMillion = 100_000_000

  const scaled = (unit: number, suffix: string) => {
    // 소수 한 자리까지 남기되 떨어지면 그 자리를 아예 쓰지 않는다. 천 단위는 끊어 준다.
    const text = (value / unit).toLocaleString('ko-KR', { maximumFractionDigits: 1 })
    return `${text}${suffix} 원`
  }

  if (Math.abs(value) >= trillion) return scaled(trillion, '조')
  if (Math.abs(value) >= hundredMillion) return scaled(hundredMillion, '억')
  return `${Math.round(value).toLocaleString('ko-KR')}원`
}

/**
 * ISO 시각을 한국 날짜로 옮긴다. 시각까지 보일 만큼 정밀한 자료가 아니다.
 *
 * **앞 열 글자를 자르지 않는다.** 그렇게 하면 UTC 날짜가 나온다. 지금은 수집이 한국 시각
 * 15:30에 찍혀 두 날짜가 같지만, 마감이 09:00 이전으로 바뀌면 사용자에게 하루 앞선 날짜를
 * 보이게 된다. 이 저장소의 다른 화면도 시간대를 명시해 옮긴다.
 */
export function toDateText(isoInstant: string): string {
  const at = new Date(isoInstant)
  if (Number.isNaN(at.getTime())) return isoInstant

  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
    .format(at)
    .replace(/\.\s*$/, '')
    .replace(/\.\s*/g, '-')
}

export type CompositionSegment = {
  key: string
  label: string
  /** 0과 1 사이다. 막대 너비와 표시 비중이 같은 값을 쓴다. */
  fraction: number
  /** 상위 종목이 아니라 "그 외"인가. */
  isRest: boolean
}

/**
 * 상위 보유 종목과 나머지를 한 줄로 잇는다.
 *
 * **나머지를 빼놓지 않는다.** 상위 몇 개만 보이면 사용자는 그것이 전부라고 읽는다. 합이 1을
 * 넘는 자료가 오면 나머지를 만들지 않는다. 없는 여유를 지어내는 것보다 그 자리를 비우는 편이 낫다.
 */
export function buildComposition(
  holdings: readonly InstrumentHolding[],
): CompositionSegment[] {
  const segments: CompositionSegment[] = holdings.map((holding, index) => ({
    key: `${holding.name}-${index}`,
    label: holding.name,
    fraction: toFraction(holding.weight),
    isRest: false,
  }))

  const covered = segments.reduce((sum, segment) => sum + segment.fraction, 0)
  const rest = 1 - covered
  if (rest > 0.0001) {
    segments.push({ key: '__rest__', label: '그 외', fraction: rest, isRest: true })
  }

  return segments
}

/**
 * 실패한 까닭을 화면 문장으로 옮긴다.
 *
 * **없는 섹터와 망가진 자료를 같은 문장으로 알리지 않는다.** 둘 다 다시 눌러도 달라지지
 * 않지만 사용자가 해야 할 일이 다르다. 목록에 멀쩡히 보이는 대상을 열었는데 "찾을 수
 * 없습니다"라고 하면, 실제로는 서버 자료가 망가진 것인데 사용자는 자기가 잘못 눌렀다고 읽는다.
 */
export function profileFailureMessage(
  result: Extract<InstrumentProfileResult, { status: 'failed' }>,
): string {
  if (result.retryable) return '대표 상품 정보를 불러오지 못했습니다.'
  if (result.code === 'SECTOR_NOT_FOUND') return '이 섹터의 대표 상품 정보를 찾을 수 없습니다.'

  // 서버에 저장된 자료를 고쳐야 풀린다. 사용자가 다시 눌러서 될 일이 아니다.
  return '서버에 저장된 상품 자료에 문제가 있어 지금은 보여드릴 수 없습니다.'
}

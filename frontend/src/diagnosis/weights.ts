/**
 * 비중의 정수 모형과 순수 규칙이다. 화면 없이 단위 검사로 먼저 고정한다.
 *
 * **비중은 0.1%p 단위 정수다.** 100%가 `1000`이고 33.4%가 `334`다. 표시할 때만 10으로 나눈다
 * (research.md 결정 1). 소수로 다루면 `0.1 + 0.2 !== 0.3`인 부동소수 오차가 합계 판정에 그대로
 * 들어와 FR-006·FR-007이 무너진다.
 *
 * 합계 판정은 `합계 === 1000`이라는 정수 비교 하나다. **허용 오차를 두지 않는다.** 원칙 VI가
 * 임의 epsilon으로 판정을 만드는 처리를 금지한다.
 */

/** 100%에 해당하는 내부 값이다. */
export const TOTAL_WEIGHT_TENTHS = 1000

/**
 * 선택 섹터에 동일 비중을 채운다. 초깃값 자동 채움과 '동일하게 나누기'가 같은 함수를 쓴다
 * (FR-005, FR-007).
 *
 * `q = floor(1000 / n)`, `r = 1000 - q * n`을 구해 **잔여분 `r`을 표시 순서상 첫 번째 섹터에
 * 몰아준다.** FR-005가 "남는 나머지를 한 섹터에 몰아 초깃값의 합계를 정확히 100%로 맞춘다"고
 * 정한 그대로다. 그래서 어떤 `n`에서도 합계가 정확히 `1000`이다.
 *
 * **받는 배열이 표시 순서로 정렬되어 있어야 한다.** 잔여분이 붙는 자리가 화면과 계산에서
 * 갈라지지 않도록 부르는 쪽이 `sortSectorsForDisplay`의 순서를 넘긴다(research.md 결정 2).
 */
export function distributeEven(orderedCodes: readonly string[]): Record<string, number> {
  const count = orderedCodes.length
  if (count === 0) return {}

  const share = Math.floor(TOTAL_WEIGHT_TENTHS / count)
  const remainder = TOTAL_WEIGHT_TENTHS - share * count

  const weights: Record<string, number> = {}
  orderedCodes.forEach((code, index) => {
    weights[code] = index === 0 ? share + remainder : share
  })
  return weights
}

/** 입력을 받지 않은 까닭이다. 문구가 아니라 까닭을 값으로 남겨 두어 화면이 골라 쓸 수 있게 한다. */
export type WeightRejectionReason = 'notANumber' | 'negative' | 'aboveMaximum' | 'tooPrecise'

/**
 * 사용자 입력 한 건의 처리 결과다.
 *
 * - `parsed`: 값으로 받아들인다
 * - `pending`: 빈 칸이다. **0으로 보지 않고 "아직 입력 중"으로 다룬다.** 확정 시점에 이전 값을
 *   그대로 둔다(spec Edge Cases)
 * - `rejected`: 받지 않는다. **반올림하거나 상한으로 잘라 넣지 않고** 그 자리에 한 문장으로
 *   알린다(원칙 VI, FR-016)
 */
export type WeightParseResult =
  | { status: 'parsed'; weightTenths: number }
  | { status: 'pending' }
  | { status: 'rejected'; reason: WeightRejectionReason; message: string }

/**
 * 받지 않은 까닭을 알리는 문장이다. **문구는 확정된 것이 아니다.** 자리와 나타나는 조건까지가
 * 이 작업의 몫이고 문장은 UI 결정 항목이 정해질 때 채운다(research.md 「확정하지 않은 것」).
 */
const rejectionMessages: Record<WeightRejectionReason, string> = {
  notANumber: '비중은 숫자로 입력해 주세요.',
  negative: '비중은 0%보다 작을 수 없습니다.',
  aboveMaximum: '한 섹터의 비중은 100%를 넘을 수 없습니다.',
  tooPrecise: '비중은 소수 첫째 자리까지 입력할 수 있습니다.',
}

function reject(reason: WeightRejectionReason): WeightParseResult {
  return { status: 'rejected', reason, message: rejectionMessages[reason] }
}

/** 부호와 소수점만 허용한다. 지수 표기(`1e3`)나 자릿수 구분 기호는 숫자로 보지 않는다. */
const NUMERIC_SHAPE = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/

export type ParseWeightOptions = {
  /**
   * 입력 칸이 문자열을 아예 넘겨주지 못한 상태인가.
   *
   * `<input type="number">`는 숫자로 읽을 수 없는 입력에 빈 문자열을 돌려주면서 `validity.badInput`만
   * 올린다. 그 경우를 빈 칸("아직 입력 중")과 구분하지 않으면 사용자가 글자를 넣었는데 아무 말도
   * 없이 넘어간다. 판정은 화면이 아니라 여기서 한다.
   */
  hasUnparsableInput?: boolean
}

/**
 * 사용자가 친 문자열 한 건을 내부 정수로 옮긴다. data-model.md 「사용자 입력 파싱」 표 그대로다.
 *
 * | 입력 | 처리 |
 * |---|---|
 * | `33.4` | `334` |
 * | `33` | `330` |
 * | `0` | `0`. 거부하지 않는다 (FR-016, SC-010) |
 * | `33.45` | 받지 않는다. **반올림하지 않는다** |
 * | 음수·100 초과·숫자가 아닌 값 | 받지 않는다 |
 * | 빈 값 | 0으로 보지 않고 "아직 입력 중"으로 다룬다 |
 *
 * 값을 만드는 계산에 부동소수를 쓰지 않는다. 정수부와 소수 첫째 자리를 문자열에서 갈라 정수
 * 산술로만 더한다.
 */
export function parseWeightInput(
  raw: string,
  options: ParseWeightOptions = {},
): WeightParseResult {
  if (options.hasUnparsableInput) return reject('notANumber')

  const text = raw.trim()
  if (text === '') return { status: 'pending' }
  if (!NUMERIC_SHAPE.test(text)) return reject('notANumber')

  const isNegative = text.startsWith('-')
  const unsigned = text.replace(/^[+-]/, '')
  const [wholeText = '', fractionText = ''] = unsigned.split('.')

  // 끝에 붙은 0은 값을 바꾸지 않는다. `33.40`은 `33.4`와 같은 값이라 잘게 쪼갠 입력이 아니다.
  const fraction = fractionText.replace(/0+$/, '')
  if (fraction.length > 1) return reject('tooPrecise')

  const whole = wholeText === '' ? 0 : Number(wholeText)
  const weightTenths = whole * 10 + (fraction === '' ? 0 : Number(fraction))

  if (isNegative && weightTenths !== 0) return reject('negative')
  if (weightTenths > TOTAL_WEIGHT_TENTHS) return reject('aboveMaximum')

  return { status: 'parsed', weightTenths }
}

/**
 * 슬라이더가 돌려주는 값을 받는다. 숫자 칸과 **같은 규칙·같은 문장**을 쓴다.
 *
 * 슬라이더는 내부 단위(0.1%p)를 그대로 다룬다. `min=0`, `max=1000`, `step=1`이라 슬라이더가
 * 만들어 내는 값은 언제나 정수이고, **끌어서 움직이는 것만으로는 반올림이 생길 수 없다.**
 * 그래도 값이 상태로 들어가는 길목이므로 범위 검사는 똑같이 거친다.
 */
export function parseWeightSliderValue(raw: string): WeightParseResult {
  const text = raw.trim()
  if (text === '') return { status: 'pending' }
  if (!/^[+-]?\d+$/.test(text)) return reject('notANumber')

  const weightTenths = Number(text)
  if (weightTenths < 0) return reject('negative')
  if (weightTenths > TOTAL_WEIGHT_TENTHS) return reject('aboveMaximum')

  return { status: 'parsed', weightTenths }
}

/** 내부 정수를 화면에 보이는 문자열로 옮긴다. 나누기는 표시할 때 한 번뿐이다. */
export function formatWeightTenths(weightTenths: number): string {
  const sign = weightTenths < 0 ? '-' : ''
  const absolute = Math.abs(weightTenths)
  return `${sign}${Math.floor(absolute / 10)}.${absolute % 10}`
}

/** 저장소나 인계 페이로드에서 읽어 온 값이 비중으로 쓸 수 있는 값인지 본다. */
export function isValidWeightTenths(value: unknown): value is number {
  return (
    typeof value === 'number' &&
    Number.isInteger(value) &&
    value >= 0 &&
    value <= TOTAL_WEIGHT_TENTHS
  )
}

/** data-model.md 「파생값」이다. 저장하지 않고 매번 계산한다. */
export type WeightSummary = {
  totalTenths: number
  isBalanced: boolean
  /** 남은 비중. 음수면 초과다 (FR-007). */
  remainderTenths: number
  /** 다음 단계 버튼의 활성 조건이다. 버튼 자체는 M4가 만든다 (FR-004, FR-007). */
  canProceed: boolean
  /** 0%인 섹터가 하나 이상인가 (FR-016). */
  hasZeroWeight: boolean
}

/**
 * 선택 섹터와 비중에서 파생값을 구한다.
 *
 * 합계는 **선택된 코드만** 더한다. 선택에서 빠진 코드의 값이 기록에 남아 있어도 합계에 들어가지
 * 않게 하기 위해서다.
 */
export function summarizeWeights(
  selectedCodes: readonly string[],
  weightTenths: Readonly<Record<string, number>>,
): WeightSummary {
  let totalTenths = 0
  let hasZeroWeight = false

  for (const code of selectedCodes) {
    const weight = weightTenths[code] ?? 0
    totalTenths += weight
    if (weight === 0) hasZeroWeight = true
  }

  const isBalanced = totalTenths === TOTAL_WEIGHT_TENTHS

  return {
    totalTenths,
    isBalanced,
    remainderTenths: TOTAL_WEIGHT_TENTHS - totalTenths,
    canProceed: selectedCodes.length >= 2 && isBalanced,
    hasZeroWeight,
  }
}

import { type ChangeEvent, type KeyboardEvent, useId, useState } from 'react'
import {
  TOTAL_WEIGHT_TENTHS,
  formatWeightTenths,
  parseWeightInput,
  parseWeightSliderValue,
} from '@/diagnosis/weights'

/**
 * 한 섹터의 비중을 다루는 묶음이다. 섹터를 고르면 **같은 화면 그 자리에** 나타난다(FR-005).
 *
 * 수단이 둘이고 **값은 하나다.** 슬라이더는 끌어서 대충 맞추는 자리이고 숫자 칸은 정확한 값을
 * 넣는 자리다. 둘 다 같은 `valueTenths`를 보이고 같은 곳으로 값을 올린다.
 *
 * 값 검사는 `weights.ts`의 규칙만 쓴다. **컴포넌트가 따로 판정하지 않는다.** 판정이 두 곳에
 * 있으면 한쪽만 고쳐져 화면과 규칙이 갈라진다.
 *
 * `step`을 0.1로 두되 **붙여넣기처럼 `step`을 우회하는 경로도 같은 검사를 거친다**
 * (research.md 결정 1). 검사는 브라우저가 아니라 `onChange`에서 하므로 어느 경로로 값이
 * 들어와도 같은 판정을 받는다. 슬라이더는 내부 단위(0.1%p) 정수를 그대로 다뤄 끌어서 움직이는
 * 것만으로는 반올림이 생기지 않는다.
 *
 * **이 묶음은 자기 섹터의 값만 바꾼다.** 하나를 올리면 나머지가 알아서 줄어드는 방식은 이
 * 화면에서 금지다. 합계가 어긋나면 `WeightTotal`이 얼마가 모자라거나 넘치는지 알린다
 * (원칙 VI, SC-002).
 *
 * 받지 않은 입력은 **말없이 무시하지 않고** 그 자리에 한 문장으로 알린다. 받지 않는 것도
 * 사용자가 보게 되는 처리다(spec Edge Cases). 값을 반올림하거나 상한으로 잘라 넣지 않는다.
 *
 * 0%를 거부하지 않는다(FR-016, SC-010).
 */

type WeightInputProps = {
  /** 입력 칸의 이름에 쓰는 섹터 이름이다. 코드가 아니라 사람이 읽는 이름을 쓴다. */
  sectorName: string
  /** 0.1%p 단위 정수다. 표시할 때만 10으로 나눈다. */
  valueTenths: number
  /** 받아들인 값만 올라온다. 거부된 입력은 여기까지 오지 않는다. */
  onCommit: (weightTenths: number) => void
  className?: string
}

export function WeightInput({ sectorName, valueTenths, onCommit, className }: WeightInputProps) {
  const inputId = useId()
  const errorId = useId()

  /**
   * 사용자가 치고 있는 문자열이다. `null`이면 치는 중이 아니며 화면은 확정된 값을 보인다.
   *
   * 확정된 값을 문자열로 눌러 담지 않는 까닭은 `33.`처럼 아직 숫자가 되지 않은 중간 상태를
   * 사용자가 그대로 볼 수 있어야 하기 때문이다.
   */
  const [draft, setDraft] = useState<string | null>(null)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  const handleChange = (event: ChangeEvent<HTMLInputElement>) => {
    const raw = event.target.value
    setDraft(raw)

    // `<input type="number">`는 숫자로 읽을 수 없는 입력에 빈 문자열을 돌려주고 `validity.badInput`만
    // 올린다. 그 사실을 넘겨 주지 않으면 글자를 넣은 사용자가 아무 말도 듣지 못한다.
    const result = parseWeightInput(raw, {
      hasUnparsableInput: event.target.validity?.badInput === true,
    })

    if (result.status === 'parsed') {
      setErrorMessage(null)
      onCommit(result.weightTenths)
      return
    }

    // 빈 칸은 0으로 보지 않는다. 아직 입력 중이므로 이전 값을 그대로 둔 채 기다린다.
    setErrorMessage(result.status === 'rejected' ? result.message : null)
  }

  const handleSlide = (event: ChangeEvent<HTMLInputElement>) => {
    const result = parseWeightSliderValue(event.target.value)
    if (result.status !== 'parsed') {
      if (result.status === 'rejected') setErrorMessage(result.message)
      return
    }

    // 슬라이더를 움직이면 숫자 칸도 같은 값을 보여야 한다. 치던 중이었다면 그 중간 문자열을 버린다.
    setDraft(null)
    setErrorMessage(null)
    onCommit(result.weightTenths)
  }

  /** 확정 시점이다. 받지 않은 입력과 빈 칸은 이전 값으로 되돌아간다. */
  const settle = () => {
    setDraft(null)
    setErrorMessage(null)
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    // 키보드만으로 입력과 확정을 끝낼 수 있어야 한다(checklists/ux.md CHK033).
    if (event.key === 'Enter') {
      event.preventDefault()
      settle()
    }
  }

  const formatted = formatWeightTenths(valueTenths)

  return (
    <div className={className} data-slot="weight">
      <label htmlFor={inputId} data-slot="weight-label">
        {sectorName} 비중
      </label>

      <span data-slot="weight-controls">
        {/*
          화살표 키로 0.1%p씩 움직인다. 보조 기술이 읽는 이름은 숫자 칸과 같고 값은 `%`가 붙은
          형태로 읽힌다 — `334`라고 읽으면 사용자가 아는 단위와 달라진다.
        */}
        <input
          type="range"
          min={0}
          max={TOTAL_WEIGHT_TENTHS}
          step={1}
          value={valueTenths}
          onChange={handleSlide}
          aria-label={`${sectorName} 비중`}
          aria-valuetext={`${formatted}%`}
          data-slot="weight-slider"
        />

        <span data-slot="weight-number">
          <input
            id={inputId}
            type="number"
            inputMode="decimal"
            step="0.1"
            min="0"
            max="100"
            value={draft ?? formatted}
            onChange={handleChange}
            onBlur={settle}
            onKeyDown={handleKeyDown}
            aria-invalid={errorMessage === null ? undefined : true}
            aria-describedby={errorMessage === null ? undefined : errorId}
            data-slot="weight-field"
          />
          <span data-slot="weight-unit" aria-hidden="true">
            %
          </span>
        </span>
      </span>

      {errorMessage === null ? null : (
        <span id={errorId} role="alert" data-slot="weight-error">
          {errorMessage}
        </span>
      )}
    </div>
  )
}

import { CalendarDays, ChevronLeft, ChevronRight } from 'lucide-react'
import { Popover } from 'radix-ui'
import { useState, type ComponentPropsWithoutRef } from 'react'
import { MONTH_LABELS, isWithinBounds, parseIsoMonth, toIsoMonth } from './calendarModel'
import styles from './DateField.module.css'

type NativeInputProps = Omit<ComponentPropsWithoutRef<'input'>, 'type' | 'value' | 'onChange'>

export interface MonthFieldProps extends NativeInputProps {
  /** `YYYY-MM`이다. 비어 있으면 아직 고르지 않은 상태다 */
  value: string
  onChange: (value: string) => void
  /** 달력 버튼의 접근 가능한 이름에 쓴다. 예: `'기준월'` */
  label: string
  wrapClassName?: string
}

/**
 * 달 하나를 고르는 칸이다. {@link DateField}와 같은 껍데기·같은 토큰을 쓰고 격자만 4×3이다.
 *
 * <p>날짜 칸과 나란히 놓이는 자리가 있어(사례 연결의 기준월) 생김새를 따로 두지 않는다.
 * 스타일시트도 `DateField.module.css` 하나를 함께 쓴다.
 */
export function MonthField({
  value,
  onChange,
  label,
  wrapClassName,
  className,
  min,
  max,
  disabled,
  ...rest
}: MonthFieldProps) {
  const [open, setOpen] = useState(false)
  const selected = parseIsoMonth(value)
  const [year, setYear] = useState(() => selected?.year ?? new Date().getFullYear())

  const minText = typeof min === 'string' ? min : undefined
  const maxText = typeof max === 'string' ? max : undefined

  function openAt(next: boolean) {
    if (next) {
      const focus = parseIsoMonth(value)
      setYear(focus?.year ?? new Date().getFullYear())
    }
    setOpen(next)
  }

  function pick(iso: string) {
    onChange(iso)
    setOpen(false)
  }

  const hasPickableIn = (candidate: number) =>
    MONTH_LABELS.some((_, index) => isWithinBounds(toIsoMonth(candidate, index), minText, maxText))

  return (
    <span className={wrapClassName ? `${styles.wrap} ${wrapClassName}` : styles.wrap}>
      <input
        {...rest}
        type="month"
        className={className ? `${className} ${styles.input}` : styles.input}
        value={value}
        min={min}
        max={max}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      />

      <Popover.Root open={open} onOpenChange={openAt}>
        <Popover.Trigger
          type="button"
          className={styles.trigger}
          aria-label={`${label} 달력 열기`}
          disabled={disabled}
        >
          <CalendarDays size={15} strokeWidth={1.8} aria-hidden="true" />
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Content
            className={styles.panel}
            sideOffset={6}
            align="end"
            aria-label={`${label} 달력`}
          >
            <div className={styles.head}>
              <button
                type="button"
                className={styles.nav}
                aria-label="이전 해"
                disabled={!hasPickableIn(year - 1)}
                onClick={() => setYear(year - 1)}
              >
                <ChevronLeft size={15} strokeWidth={1.8} aria-hidden="true" />
              </button>
              <span className={styles.headLabel} aria-live="polite">
                {year}년
              </span>
              <button
                type="button"
                className={styles.nav}
                aria-label="다음 해"
                disabled={!hasPickableIn(year + 1)}
                onClick={() => setYear(year + 1)}
              >
                <ChevronRight size={15} strokeWidth={1.8} aria-hidden="true" />
              </button>
            </div>

            <div className={styles.months} role="grid" aria-label={`${label} 달`}>
              {MONTH_LABELS.map((name, index) => {
                const iso = toIsoMonth(year, index)
                return (
                  <button
                    key={name}
                    type="button"
                    className={styles.month}
                    role="gridcell"
                    data-selected={iso === value ? 'true' : 'false'}
                    aria-pressed={iso === value}
                    aria-label={iso}
                    disabled={!isWithinBounds(iso, minText, maxText)}
                    onClick={() => pick(iso)}
                  >
                    {name}
                  </button>
                )
              })}
            </div>

            <div className={styles.foot}>
              <span />
              <button
                type="button"
                className={styles.footButton}
                disabled={value === ''}
                onClick={() => pick('')}
              >
                비우기
              </button>
            </div>
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
    </span>
  )
}

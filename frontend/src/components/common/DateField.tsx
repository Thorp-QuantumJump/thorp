import { CalendarDays, ChevronLeft, ChevronRight } from 'lucide-react'
import { Popover } from 'radix-ui'
import { useState, type ComponentPropsWithoutRef } from 'react'
import {
  MONTH_LABELS,
  WEEKDAY_LABELS,
  YEARS_PER_PAGE,
  buildMonthGrid,
  buildYearPage,
  isMonthWithinBounds,
  isWithinBounds,
  isYearWithinBounds,
  parseIsoDate,
  shiftMonth,
  todayIsoDate,
  toIsoDate,
  yearPageStart,
} from './calendarModel'
import styles from './DateField.module.css'

type NativeInputProps = Omit<ComponentPropsWithoutRef<'input'>, 'type' | 'value' | 'onChange'>

export interface DateFieldProps extends NativeInputProps {
  /** `YYYY-MM-DD`다. 비어 있으면 아직 고르지 않은 상태다 */
  value: string
  /** 입력칸에 친 값과 달력에서 고른 값이 같은 통로로 올라온다 */
  onChange: (value: string) => void
  /** 달력 버튼의 접근 가능한 이름에 쓴다. 예: `'시작일'` */
  label: string
  /** 감싸는 자리에 붙일 클래스. 입력칸 자체의 클래스는 `className`이 그대로 받는다 */
  wrapClassName?: string
}

/** 판이 지금 보여 주는 층이다. 머리를 누를 때마다 위로 한 단씩 올라간다. */
type View = 'day' | 'month' | 'year'

/**
 * 날짜 하나를 고르는 칸이다.
 *
 * <p><b>브라우저 기본 달력을 쓰지 않는다.</b> 크롬이 그리는 달력 팝업은 페이지 바깥의 위젯이라
 * CSS가 닿지 않고, 화면의 다른 부분과 생김새가 따로 논다. 아이콘을 감추고 같은 자리에 우리 버튼을
 * 두어 {@link Popover}로 직접 그린 달력을 편다.
 *
 * <p><b>입력칸은 그대로 `type="date"`다.</b> 달력을 새로 그린다고 입력칸까지 글자 칸으로 바꾸면
 * 키보드로 날짜를 치던 사람과 자동완성이 함께 사라진다. `min`·`max`·`disabled`·`aria-*`도 브라우저가
 * 원래 하던 대로 동작한다. 달력은 같은 값을 다른 방법으로 넣는 수단일 뿐이다.
 *
 * <p><b>층은 날 → 달 → 해 셋이다.</b> 머리의 `2026년 9월`을 누르면 그 해의 달 판으로, 거기서
 * 다시 머리를 누르면 아홉 해 판(3×3)으로 올라간다. 화살표로만 옮기게 두면 2011년으로 가는 데
 * 화살표를 180번 눌러야 한다.
 *
 * <p>Popover가 키보드 열기·Esc 닫기·닫은 뒤 트리거로 초점 되돌리기를 맡는다. `<label>` 안에
 * 놓여도 안전하다 — 버튼은 상호작용 요소라 label 의 클릭 전달을 받지 않는다.
 */
export function DateField({
  value,
  onChange,
  label,
  wrapClassName,
  className,
  min,
  max,
  disabled,
  ...rest
}: DateFieldProps) {
  const [open, setOpen] = useState(false)
  const [view, setView] = useState<View>('day')
  const today = todayIsoDate()

  const anchor = parseIsoDate(value) ?? parseIsoDate(today)
  const [cursor, setCursor] = useState(() => ({
    year: anchor?.year ?? new Date().getFullYear(),
    monthIndex: anchor?.monthIndex ?? new Date().getMonth(),
  }))

  const minText = typeof min === 'string' ? min : undefined
  const maxText = typeof max === 'string' ? max : undefined

  function openAt(next: boolean) {
    // 다시 펼칠 때마다 고른 날의 달로, 그리고 날 판으로 돌아온다. 지난번 자리가 남아 있으면 헷갈린다.
    if (next) {
      const focus = parseIsoDate(value) ?? parseIsoDate(today)
      if (focus) setCursor({ year: focus.year, monthIndex: focus.monthIndex })
      setView('day')
    }
    setOpen(next)
  }

  function pick(iso: string) {
    onChange(iso)
    setOpen(false)
  }

  const cells = buildMonthGrid(cursor.year, cursor.monthIndex)
  const previousMonth = shiftMonth(cursor.year, cursor.monthIndex, -1)
  const nextMonth = shiftMonth(cursor.year, cursor.monthIndex, 1)
  const pageStart = yearPageStart(cursor.year)
  const years = buildYearPage(pageStart)

  // 넘어갈 곳에 고를 수 있는 것이 하나도 없으면 화살표를 잠근다.
  const canGoBack =
    view === 'day'
      ? isMonthWithinBounds(previousMonth.year, previousMonth.monthIndex, minText, maxText)
      : view === 'month'
        ? isYearWithinBounds(cursor.year - 1, minText, maxText)
        : isYearWithinBounds(pageStart - 1, minText, maxText)
  const canGoForward =
    view === 'day'
      ? isMonthWithinBounds(nextMonth.year, nextMonth.monthIndex, minText, maxText)
      : view === 'month'
        ? isYearWithinBounds(cursor.year + 1, minText, maxText)
        : isYearWithinBounds(pageStart + YEARS_PER_PAGE, minText, maxText)

  function step(direction: -1 | 1) {
    if (view === 'day') {
      setCursor(direction === -1 ? previousMonth : nextMonth)
    } else if (view === 'month') {
      setCursor({ ...cursor, year: cursor.year + direction })
    } else {
      setCursor({ ...cursor, year: cursor.year + direction * YEARS_PER_PAGE })
    }
  }

  const headLabel =
    view === 'day'
      ? `${cursor.year}년 ${cursor.monthIndex + 1}월`
      : view === 'month'
        ? `${cursor.year}년`
        : `${pageStart} ~ ${pageStart + YEARS_PER_PAGE - 1}`

  return (
    <span className={wrapClassName ? `${styles.wrap} ${wrapClassName}` : styles.wrap}>
      <input
        {...rest}
        type="date"
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
                aria-label={view === 'day' ? '이전 달' : view === 'month' ? '이전 해' : '이전 9년'}
                disabled={!canGoBack}
                onClick={() => step(-1)}
              >
                <ChevronLeft size={15} strokeWidth={1.8} aria-hidden="true" />
              </button>
              <button
                type="button"
                className={styles.headLabel}
                // 해 판이 맨 위다. 더 올라갈 곳이 없으면 누를 수 있게 두지 않는다.
                disabled={view === 'year'}
                aria-label={view === 'year' ? headLabel : `${headLabel} — 더 넓게 고르기`}
                onClick={() => setView(view === 'day' ? 'month' : 'year')}
              >
                {headLabel}
              </button>
              <button
                type="button"
                className={styles.nav}
                aria-label={view === 'day' ? '다음 달' : view === 'month' ? '다음 해' : '다음 9년'}
                disabled={!canGoForward}
                onClick={() => step(1)}
              >
                <ChevronRight size={15} strokeWidth={1.8} aria-hidden="true" />
              </button>
            </div>

            {view === 'day' ? (
              <>
                <div className={styles.weekdays} aria-hidden="true">
                  {WEEKDAY_LABELS.map((name, index) => (
                    <span key={name} className={styles.weekday} data-weekend={weekendOf(index)}>
                      {name}
                    </span>
                  ))}
                </div>

                <div className={styles.grid} role="grid" aria-label={`${label} 날짜`}>
                  {cells.map((cell, index) => (
                    <button
                      key={cell.iso}
                      type="button"
                      className={styles.day}
                      role="gridcell"
                      data-outside={cell.outside ? 'true' : 'false'}
                      data-today={cell.iso === today ? 'true' : 'false'}
                      data-selected={cell.iso === value ? 'true' : 'false'}
                      data-weekend={weekendOf(index % 7)}
                      aria-current={cell.iso === today ? 'date' : undefined}
                      aria-pressed={cell.iso === value}
                      aria-label={cell.iso}
                      disabled={!isWithinBounds(cell.iso, minText, maxText)}
                      onClick={() => pick(cell.iso)}
                    >
                      {cell.day}
                    </button>
                  ))}
                </div>
              </>
            ) : null}

            {view === 'month' ? (
              <div className={styles.months} role="grid" aria-label={`${label} 달`}>
                {MONTH_LABELS.map((name, index) => (
                  <button
                    key={name}
                    type="button"
                    className={styles.month}
                    role="gridcell"
                    data-selected={index === cursor.monthIndex ? 'true' : 'false'}
                    aria-label={`${cursor.year}년 ${name}`}
                    disabled={!isMonthWithinBounds(cursor.year, index, minText, maxText)}
                    onClick={() => {
                      setCursor({ ...cursor, monthIndex: index })
                      setView('day')
                    }}
                  >
                    {name}
                  </button>
                ))}
              </div>
            ) : null}

            {view === 'year' ? (
              <div className={styles.years} role="grid" aria-label={`${label} 해`}>
                {years.map((year) => (
                  <button
                    key={year}
                    type="button"
                    className={styles.month}
                    role="gridcell"
                    data-selected={year === cursor.year ? 'true' : 'false'}
                    aria-label={`${year}년`}
                    disabled={!isYearWithinBounds(year, minText, maxText)}
                    onClick={() => {
                      setCursor({ ...cursor, year })
                      setView('month')
                    }}
                  >
                    {year}
                  </button>
                ))}
              </div>
            ) : null}

            <div className={styles.foot}>
              <button
                type="button"
                className={styles.footButton}
                disabled={!isWithinBounds(today, minText, maxText)}
                onClick={() => pick(today)}
              >
                오늘
              </button>
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

/** 주말만 글자색을 달리한다. 열 번호가 곧 요일이다. */
function weekendOf(columnIndex: number): 'sun' | 'sat' | undefined {
  if (columnIndex === 0) return 'sun'
  if (columnIndex === 6) return 'sat'
  return undefined
}

export { toIsoDate }

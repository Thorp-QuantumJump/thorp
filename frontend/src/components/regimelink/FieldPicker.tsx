import type { RegimeBaselineMonths } from '@/types/regime'
import {
  baselineMonthLabel,
  compositeOptionLabel,
  fieldPickerTitle,
} from './fixedCopy'

/** 고른 검색 대상. 분야가 기본이고 종합은 사용자가 고를 때만 열린다. */
export type FieldChoice =
  | { mode: 'field'; field: string; baselineMonth: string }
  | { mode: 'composite'; baselineMonth: string }

interface FieldPickerProps {
  baselines: RegimeBaselineMonths
  value: FieldChoice | null
  onChange: (choice: FieldChoice) => void
}

/**
 * 분야·기준월 선택 (T053).
 *
 * **분야 이름과 순서를 화면에 박지 않는다.** 010 `baseline-months` 응답의 등록표를 그대로 그린다.
 * 종합 비교는 목록의 마지막 선택지이며 기본값이 아니다(spec §8.1, 010 FR).
 */
export function FieldPicker({ baselines, value, onChange }: FieldPickerProps) {
  const selectedId = value === null ? '' : value.mode === 'composite' ? '__composite__' : value.field

  function selectField(id: string) {
    if (id === '__composite__') {
      onChange({ mode: 'composite', baselineMonth: baselines.composite ?? '' })
      return
    }
    const field = baselines.byField.find((item) => item.id === id)
    if (!field) return
    onChange({ mode: 'field', field: field.id, baselineMonth: field.baselineMonth })
  }

  return (
    <section aria-labelledby="regime-link-field-title">
      <h2 id="regime-link-field-title">{fieldPickerTitle}</h2>
      <ul>
        {baselines.byField.map((field) => (
          <li key={field.id}>
            <button
              type="button"
              aria-pressed={selectedId === field.id}
              onClick={() => selectField(field.id)}
            >
              {field.name}
            </button>
          </li>
        ))}
        {baselines.composite !== null && (
          <li>
            <button
              type="button"
              aria-pressed={selectedId === '__composite__'}
              onClick={() => selectField('__composite__')}
            >
              {compositeOptionLabel}
            </button>
          </li>
        )}
      </ul>
      {value !== null && (
        <label>
          {baselineMonthLabel}
          <input
            type="month"
            value={value.baselineMonth}
            onChange={(event) => onChange({ ...value, baselineMonth: event.target.value })}
          />
        </label>
      )}
    </section>
  )
}

import type {
  AxisValue,
  SearchSpaceSelection,
  SpaceAxis,
  StrategySpaceSummary,
} from '@/types/paramSearch'
import { axisValueKey, formatCount } from '@/utils/paramSearchLabels'
import styles from './SpaceEditor.module.css'

interface SpaceEditorProps {
  spaceSummary: StrategySpaceSummary[]
  /** 지금 좁혀 둔 범위. 비어 있으면 등록표 전체 범위다. */
  value: SearchSpaceSelection
  onChange: (next: SearchSpaceSelection) => void
}

/** 이 축에서 지금 고른 값들. 고른 적이 없으면 등록된 후보값 전부다. */
function selectedValues(
  value: SearchSpaceSelection,
  strategyId: string,
  axis: SpaceAxis,
): AxisValue[] {
  const picked = value[strategyId]?.[axis.axis]
  return picked ?? axis.values
}

/** 허용 범위를 사람이 읽는 한 줄로. 값이 없는 항목은 적지 않는다. */
function describeRange(axis: SpaceAxis): string | null {
  const range = axis.allowedRange
  if (!range) return null

  const parts: string[] = []
  if (range.min !== null && range.max !== null) parts.push(`${range.min}~${range.max}`)
  else if (range.min !== null) parts.push(`${range.min} 이상`)
  else if (range.max !== null) parts.push(`${range.max} 이하`)
  if (range.step !== null) parts.push(`간격 ${range.step}`)

  return parts.length === 0 ? null : parts.join(' · ')
}

/**
 * 탐색 범위 상세 변경(FR-004·FR-025·FR-027).
 *
 * <p><b>화면이 아는 것은 모양뿐이고 값은 전부 `spaceSummary`에서 읽는다.</b> 전략 이름·축 라벨·단위·
 * 기본 시작값·후보값·허용 범위 어느 것도 이 파일에 없다. 그래서 등록표에 전략이나 축이 늘어도 이
 * 컴포넌트를 고치지 않는다. 같은 이유로 <b>수수료·세금·평가 기간이 탐색 변수로 나타나지 않는다</b> —
 * 등록표가 축으로 주지 않는 한 여기에 나올 길이 없다(FR-027).
 *
 * <p><b>범위 밖 값은 입력할 수 없다.</b> 자유 입력을 두지 않고 등록된 후보값만 켜고 끈다 —
 * `allowedRange`는 표시용이며 입력 허용 범위가 아니다(그 안이라도 등록되지 않은 값은 400이다).
 *
 * <p>기본 시작값에 '추천'·'최적' 문구를 붙이지 않는다. 그것은 등록된 출발점이지 좋은 값이 아니다(원칙 III).
 */
export function SpaceEditor({ spaceSummary, value, onChange }: SpaceEditorProps) {
  // 한 축이라도 좁혔으면 전략의 축을 전부 실어 보낸다 — 일부만 보내면 나머지 축의 뜻이 모호해진다.
  const emit = (strategyId: string, axisName: string, nextValues: AxisValue[]) => {
    const space = spaceSummary.find((item) => item.strategyId === strategyId)
    if (!space) return

    const next: SearchSpaceSelection = { ...value }
    const axes: Record<string, AxisValue[]> = {}
    let narrowed = false

    for (const axis of space.axes) {
      const picked =
        axis.axis === axisName ? nextValues : selectedValues(value, strategyId, axis)
      axes[axis.axis] = picked
      if (picked.length !== axis.values.length) narrowed = true
    }

    // 아무것도 좁히지 않았으면 항목을 지운다. 전부 고른 것과 보내지 않는 것은 같은 뜻이다.
    if (narrowed) next[strategyId] = axes
    else delete next[strategyId]

    onChange(next)
  }

  const toggle = (strategyId: string, axis: SpaceAxis, candidate: AxisValue) => {
    const current = selectedValues(value, strategyId, axis)
    // 견주기는 참조가 아니라 열쇠로 한다 — 객체형 후보는 응답을 다시 받을 때마다 새 객체다.
    const candidateKey = axisValueKey(candidate)
    const currentKeys = new Set(current.map(axisValueKey))
    const isOn = currentKeys.has(candidateKey)

    // 마지막 하나는 끄지 못한다. 축이 비면 그 전략은 돌릴 조합이 없다.
    if (isOn && current.length === 1) return

    // 등록표가 준 순서를 지켜 고른다. 순서가 곧 그리드 좌표의 뜻이다(FR-029).
    const next = axis.values.filter((item) => {
      const key = axisValueKey(item)
      return key === candidateKey ? !isOn : currentKeys.has(key)
    })

    emit(strategyId, axis.axis, next)
  }

  if (spaceSummary.length === 0) {
    return <p className={styles.empty}>변경할 탐색 범위가 없습니다.</p>
  }

  return (
    <div className={styles.editor}>
      <p className={styles.guide}>
        등록표에 있는 후보값만 켜고 끌 수 있습니다. 끈 값은 이번 탐색에서 제외됩니다.
      </p>

      {spaceSummary.map((space) => (
        <section key={space.strategyId} className={styles.strategy}>
          <header className={styles.strategyHeader}>
            <h3>{space.strategyName}</h3>
            <span>등록 조합 {formatCount(space.combinations)}개</span>
          </header>

          {space.axes.length === 0 ? (
            <p className={styles.noAxis}>탐색 축이 없는 전략입니다. 조합은 1개입니다.</p>
          ) : (
            space.axes.map((axis) => {
              const current = selectedValues(value, space.strategyId, axis)
              const currentKeys = new Set(current.map(axisValueKey))
              const defaultKey =
                axis.defaultValue === null ? null : axisValueKey(axis.defaultValue)
              const range = describeRange(axis)

              return (
                <fieldset key={axis.axis} className={styles.axis}>
                  <legend className={styles.axisLegend}>
                    {axis.label}
                    {axis.unit ? <span className={styles.unit}>{axis.unit}</span> : null}
                  </legend>

                  <div className={styles.values}>
                    {axis.values.map((candidate, index) => {
                      const key = axisValueKey(candidate)
                      const isOn = currentKeys.has(key)
                      const isDefault = defaultKey !== null && defaultKey === key
                      // 값이 아니라 등록표가 적어 둔 문구를 그린다. 값은 요청에만 쓴다.
                      const text = axis.valueLabels[index] ?? key

                      return (
                        <button
                          key={key}
                          type="button"
                          className={styles.value}
                          data-on={isOn}
                          aria-pressed={isOn}
                          onClick={() => toggle(space.strategyId, axis, candidate)}
                        >
                          {text}
                          {isDefault ? (
                            <span className={styles.defaultMark}>기본 시작값</span>
                          ) : null}
                        </button>
                      )
                    })}
                  </div>

                  <p className={styles.axisMeta}>
                    {`${formatCount(current.length)}/${formatCount(axis.values.length)}개 선택`}
                    {range ? ` · 등록 허용 범위 ${range}` : null}
                  </p>
                </fieldset>
              )
            })
          )}
        </section>
      ))}
    </div>
  )
}

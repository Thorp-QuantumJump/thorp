import { useEffect, useRef } from 'react'
import { RefreshCw } from 'lucide-react'
import type { ParamSearchPreview, SpaceAxis, StrategySpaceSummary } from '@/types/paramSearch'
import { axisValueKey, formatCount } from '@/utils/paramSearchLabels'
import styles from './RegistryChangedDialog.module.css'

interface RegistryChangedDialogProps {
  open: boolean
  /** 실행을 보냈던 시점의 미리보기. */
  previous: ParamSearchPreview
  /** 409를 받고 다시 부른 미리보기. 아직 오지 않았으면 `null`이다. */
  next: ParamSearchPreview | null
  loadingNext: boolean
  /** 다시 부르기가 실패했을 때의 문장. */
  reloadError: string | null
  onConfirm: () => void
  onCancel: () => void
  onReload: () => void
}

/**
 * 축 후보값을 사람이 읽는 한 줄로. 등록표가 적어 둔 문구를 쓴다 — 값을 그대로 찍으면 객체형
 * 후보가 `[object Object]`로 나온다.
 */
function joinLabels(axis: SpaceAxis): string {
  return axis.values.map((value, index) => axis.valueLabels[index] ?? axisValueKey(value)).join(', ')
}

/**
 * 후보값이 달라졌는지 견주는 열쇠. **문구가 아니라 값으로 본다** — 등록표 판이 올라가며 문구만
 * 다듬어진 경우를 "후보가 바뀌었다"고 알리면 확인할 것이 없는 경고가 된다.
 */
function joinKeys(axis: SpaceAxis): string {
  return axis.values.map(axisValueKey).join(', ')
}

function byStrategyId(spaces: StrategySpaceSummary[]) {
  return new Map(spaces.map((space) => [space.strategyId, space]))
}

/**
 * 무엇이 바뀌었는지 한 줄씩. 화면이 "달라졌습니다"로 끝내면 사용자는 무엇을 확인해야 할지 모른다.
 *
 * 전략 이름·축 라벨은 응답에서 읽고, 사라진 전략은 이전 응답의 이름으로 적는다.
 */
function describeChanges(
  previous: ParamSearchPreview,
  next: ParamSearchPreview,
): string[] {
  const before = byStrategyId(previous.spaceSummary)
  const after = byStrategyId(next.spaceSummary)
  const lines: string[] = []

  for (const [strategyId, beforeSpace] of before) {
    const afterSpace = after.get(strategyId)
    if (!afterSpace) {
      lines.push(`${beforeSpace.strategyName}: 등록표에서 빠졌습니다`)
      continue
    }

    const beforeAxes = new Map(beforeSpace.axes.map((axis) => [axis.axis, axis]))
    for (const afterAxis of afterSpace.axes) {
      const beforeAxis = beforeAxes.get(afterAxis.axis)
      if (!beforeAxis) {
        lines.push(
          `${afterSpace.strategyName} · ${afterAxis.label}: 축이 추가되었습니다 (${joinLabels(afterAxis)})`,
        )
        continue
      }
      if (joinKeys(beforeAxis) !== joinKeys(afterAxis)) {
        lines.push(
          `${afterSpace.strategyName} · ${afterAxis.label}: ${joinLabels(beforeAxis)} → ${joinLabels(afterAxis)}`,
        )
      }
      beforeAxes.delete(afterAxis.axis)
    }
    for (const removed of beforeAxes.values()) {
      lines.push(`${beforeSpace.strategyName} · ${removed.label}: 축이 빠졌습니다`)
    }

    if (beforeSpace.combinations !== afterSpace.combinations) {
      lines.push(
        `${afterSpace.strategyName}: 조합 ${formatCount(beforeSpace.combinations)}개 → ${formatCount(afterSpace.combinations)}개`,
      )
    }
  }

  for (const [strategyId, afterSpace] of after) {
    if (!before.has(strategyId)) {
      lines.push(`${afterSpace.strategyName}: 등록표에 추가되었습니다`)
    }
  }

  if (previous.workload.validCombinations !== next.workload.validCombinations) {
    lines.push(
      `실행 예정 후보 ${formatCount(previous.workload.validCombinations)}개 → ${formatCount(next.workload.validCombinations)}개`,
    )
  }

  return lines
}

/**
 * 등록표 버전이 달라졌을 때의 확인(FR-028, `409 REGISTRY_VERSION_CHANGED`).
 *
 * <p><b>사용자가 확인하기 전에는 실행을 다시 보내지 않는다.</b> 미리보기를 본 뒤 실행을 누르는
 * 사이에 등록표가 바뀌면 화면이 보여 준 조합과 실제로 돌 조합이 달라진다 — 조용히 다시 보내면
 * 사용자는 자기가 확인하지 않은 탐색의 결과를 받는다.
 *
 * <p>그래서 <b>바뀐 후보값과 조합 수를 보이고</b> 확인을 받는다. 캐시된 요약이나 기본값으로
 * 대체해 실행하지 않는다 — 다시 부르기가 실패하면 실행 버튼 자체를 열지 않는다.
 */
export function RegistryChangedDialog({
  open,
  previous,
  next,
  loadingNext,
  reloadError,
  onConfirm,
  onCancel,
  onReload,
}: RegistryChangedDialogProps) {
  const closeRef = useRef<HTMLButtonElement>(null)

  // 열려 있는 동안만 Escape 를 받는다.
  useEffect(() => {
    if (!open) return

    const handleEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || event.isComposing) return
      event.preventDefault()
      onCancel()
    }

    document.addEventListener('keydown', handleEscape)
    return () => document.removeEventListener('keydown', handleEscape)
  }, [open, onCancel])

  // 대화상자가 열리면 포커스를 안으로 들인다.
  useEffect(() => {
    if (open) closeRef.current?.focus()
  }, [open])

  if (!open) return null

  const changes = next ? describeChanges(previous, next) : []

  return (
    <div className={styles.overlay}>
      <div
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby="registry-changed-title"
      >
        <h2 id="registry-changed-title">등록표가 바뀌었습니다</h2>
        <p className={styles.lead}>
          확인 화면을 보는 사이에 전략·파라미터 등록표가 바뀌어 실행하지 않았습니다. 바뀐 내용을
          확인한 뒤 다시 실행해 주세요.
        </p>

        <dl className={styles.versions}>
          <div>
            <dt>확인한 버전</dt>
            <dd>{previous.registryVersion}</dd>
          </div>
          <div>
            <dt>현재 버전</dt>
            <dd>{next ? next.registryVersion : '확인 중'}</dd>
          </div>
        </dl>

        {loadingNext && (
          <p className={styles.status} role="status">
            바뀐 내용을 다시 불러오는 중입니다.
          </p>
        )}

        {reloadError && (
          <p className={styles.error} role="alert">
            {reloadError}
          </p>
        )}

        {next && (
          <div className={styles.changes}>
            <h3>바뀐 내용</h3>
            {changes.length === 0 ? (
              <p className={styles.status}>
                버전만 올라갔고 후보값과 조합 수는 그대로입니다.
              </p>
            ) : (
              <ul>
                {changes.map((line) => (
                  <li key={line}>{line}</li>
                ))}
              </ul>
            )}
          </div>
        )}

        <div className={styles.actions}>
          <button
            type="button"
            ref={closeRef}
            className={styles.secondary}
            onClick={onCancel}
          >
            닫기
          </button>
          {reloadError ? (
            <button type="button" className={styles.primary} onClick={onReload}>
              <RefreshCw size={15} aria-hidden="true" />
              다시 불러오기
            </button>
          ) : (
            <button
              type="button"
              className={styles.primary}
              onClick={onConfirm}
              disabled={!next || loadingNext}
            >
              확인하고 실행
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

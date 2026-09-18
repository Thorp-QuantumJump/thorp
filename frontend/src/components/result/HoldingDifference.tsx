import type { ResultConfigItem, ResultWeights } from '@/types/result'
import { formatRatio } from './formatResult'
import type { SectorLabels } from './sectorLabels'
import styles from './resultDetail.module.css'

/**
 * 비교 대상과 실제 보유 섹터가 달라졌을 때의 설명(FR-008, T067).
 *
 * <p>실제로 담은 섹터가 다르면 한쪽에만 있던 섹터(선정 효과)와, 함께 담은 섹터의 마지막 리밸런싱 실제 비중(배분
 * 효과)을 두 결과의 값 그대로 나란히 둔다. **차이를 계산하지 않는다** — 섹터 집합을 비교하는 것 말고는 004가 준
 * 값을 자릿수만 다듬어 보인다(원칙 VI). 보유 섹터가 같으면 아무것도 그리지 않는다.
 */

export type HoldingSide = {
  name: string
  items: readonly ResultConfigItem[]
  weights: ResultWeights
}

/** 기간 중 한 번이라도 실제 비중이 0보다 컸던 섹터. 비중 기록은 종목 식별자로 오므로 결과의 섹터 후보로 옮긴다. */
function heldSectors(side: HoldingSide): Set<string> {
  const held = new Set<string>()
  for (const point of side.weights.points) {
    for (const [instrumentId, weight] of Object.entries(point.actual)) {
      if (!(Number(weight) > 0)) continue
      const item = side.items.find((candidate) => candidate.instrumentId === instrumentId)
      held.add(item?.sectorId ?? instrumentId)
    }
  }
  return held
}

/** 마지막 리밸런싱의 섹터별 실제 비중 문자열. 기록이 없으면 `null`이다. */
function lastActualBySector(side: HoldingSide): { date: string; weights: Map<string, string> } | null {
  const last = side.weights.points.at(-1)
  if (!last) return null
  const weights = new Map<string, string>()
  for (const [instrumentId, weight] of Object.entries(last.actual)) {
    const item = side.items.find((candidate) => candidate.instrumentId === instrumentId)
    weights.set(item?.sectorId ?? instrumentId, weight)
  }
  return { date: last.date, weights }
}

function joinSectors(codes: readonly string[], labels: SectorLabels): string {
  return codes.length === 0 ? '없음' : codes.map((code) => labels.sector(code)).join(', ')
}

export function HoldingDifference({
  base,
  target,
  labels,
}: {
  base: HoldingSide
  target: HoldingSide
  labels: SectorLabels
}) {
  const baseHeld = heldSectors(base)
  const targetHeld = heldSectors(target)
  const onlyBase = [...baseHeld].filter((code) => !targetHeld.has(code))
  const onlyTarget = [...targetHeld].filter((code) => !baseHeld.has(code))
  if (onlyBase.length === 0 && onlyTarget.length === 0) return null

  const common = [...baseHeld].filter((code) => targetHeld.has(code))
  const baseLast = lastActualBySector(base)
  const targetLast = lastActualBySector(target)

  return (
    <section className={styles.item} aria-labelledby="holding-difference-title">
      <h3 id="holding-difference-title" className={styles.itemTitle}>
        보유 섹터 차이
      </h3>
      <p className={styles.copy}>
        두 결과가 실제로 담은 섹터가 다릅니다. 담은 섹터가 달라 생긴 차이(선정 효과)와 같은 섹터를 담은 비중이
        달라 생긴 차이(배분 효과)를 나눠 보입니다. 두 결과의 값을 나란히 둘 뿐 차이를 따로 계산하지 않습니다.
      </p>

      <h4 className={styles.subTitle}>선정 효과: 한쪽에만 있던 섹터</h4>
      <dl className={styles.facts}>
        <dt>기준({base.name})에만 있던 섹터</dt>
        <dd>{joinSectors(onlyBase, labels)}</dd>
        <dt>비교 대상({target.name})에만 있던 섹터</dt>
        <dd>{joinSectors(onlyTarget, labels)}</dd>
      </dl>

      <h4 className={styles.subTitle}>배분 효과: 함께 담은 섹터의 비중</h4>
      {common.length === 0 ? (
        <p className={styles.copy}>두 결과가 함께 담은 섹터가 없습니다.</p>
      ) : (
        <div className={styles.tableScroll}>
          <table className={styles.table}>
            <caption>마지막 리밸런싱의 실제 비중</caption>
            <thead>
              <tr>
                <th scope="col">섹터</th>
                <th scope="col">
                  기준 {base.name}
                  {baseLast ? ` (${baseLast.date})` : ''}
                </th>
                <th scope="col">
                  비교 대상 {target.name}
                  {targetLast ? ` (${targetLast.date})` : ''}
                </th>
              </tr>
            </thead>
            <tbody>
              {common.map((code) => {
                const baseWeight = baseLast?.weights.get(code)
                const targetWeight = targetLast?.weights.get(code)
                return (
                  <tr key={code}>
                    <td>{labels.sector(code)}</td>
                    <td>{baseWeight === undefined ? '없음' : formatRatio(baseWeight)}</td>
                    <td>{targetWeight === undefined ? '없음' : formatRatio(targetWeight)}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

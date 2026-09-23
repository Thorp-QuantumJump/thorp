import { Link } from "react-router-dom";
import { summarizeWeights } from "@/diagnosis/weights";
import styles from "./DetailSettingsPanel.module.css";
import { WeightInput } from "./WeightInput";
import { WeightTotal } from "./WeightTotal";

/** 표 한 줄. 섹터 이름은 002의 섹터 목록에서, 비중은 이 화면이 확정한 값에서 온다. */
export type TargetWeightRow = {
  sectorId: string;
  name: string;
  /** 0~1000 정수(0.1%p). `1000`이 100%다 */
  weightTenths: number;
};

/** 투자 대상을 고르는 앞 단계의 주소. 대상을 바꾸려면 그리로 돌아간다. */
export const SECTOR_WEIGHT_STEP_PATH = "/diagnosis/sectors";

interface TargetWeightEditorProps {
  /** 앞 단계에서 고른 대상과 지금 비중. 앞 단계의 선택을 읽지 못했으면 `null`이다 */
  rows: readonly TargetWeightRow[] | null;
  onChange: (sectorId: string, weightTenths: number) => void;
  /** '동일하게 나누기'. 고른 대상 전체를 같은 비중으로 다시 채운다 */
  onDistributeEven: () => void;
}

/**
 * 고른 투자 대상에 얼마씩 줄지 정한다(FR-003).
 *
 * <p><b>무엇을 담을지는 여기서 고치지 않는다.</b> 대상을 고르는 일은 앞 단계가 소유하고, 이
 * 화면은 그 대상에 붙는 값만 다룬다. 두 가지를 한자리에서 다루면 사용자가 무엇을 하는 중인지
 * 흐려진다 — 앞 단계가 "무엇을", 이 화면이 "얼마나"다.
 *
 * <p><b>비중은 '고정 비중' 전략에만 쓰인다.</b> 자동 배분 전략은 이 값을 읽지 않으므로 그
 * 전략을 고른 사용자에게는 이 영역 자체가 나타나지 않는다. 그 판정은 등록표가 내려 준
 * `targetWeightSource`가 하며 이 컴포넌트는 알지 못한다.
 *
 * <p><b>합계를 자동으로 맞추지 않는다.</b> 한 값을 올렸다고 나머지가 저절로 줄면, 사용자가
 * 방금 정한 다른 값이 말없이 달라진다. 모자라거나 넘친 몫은 합계 표시가 알리고, 되돌리는
 * 수단으로 '동일하게 나누기' 하나를 둔다(SC-002).
 */
export function TargetWeightEditor({
  rows,
  onChange,
  onDistributeEven,
}: TargetWeightEditorProps) {
  const summary = summarizeWeights(
    (rows ?? []).map((row) => row.sectorId),
    Object.fromEntries((rows ?? []).map((row) => [row.sectorId, row.weightTenths])),
  );

  return (
    <section className={styles.weights} aria-labelledby="target-weights-title">
      <h3 id="target-weights-title" className={styles.subTitle}>
        자산 배분
      </h3>

      {rows === null ? (
        <p className={styles.note}>앞 단계에서 고른 투자 대상을 읽지 못했습니다.</p>
      ) : (
        <>
          <ul className={styles.weightList} role="list">
            {rows.map((row) => (
              <li key={row.sectorId} className={styles.weightRow}>
                <WeightInput
                  sectorName={row.name}
                  valueTenths={row.weightTenths}
                  onCommit={(weightTenths) => onChange(row.sectorId, weightTenths)}
                />
              </li>
            ))}
          </ul>

          <WeightTotal
            className={styles.weightTotal}
            totalTenths={summary.totalTenths}
            remainderTenths={summary.remainderTenths}
            isBalanced={summary.isBalanced}
            onDistributeEven={onDistributeEven}
          />

          {/*
            0%를 넣은 사용자가 "그 대상이 빠졌다"고 오해하는 것을 막는다(FR-016). 0%인 대상을
            선택에서 빼지 않는다 — 자동 배분 전략은 그 대상을 여전히 후보로 삼는다(SC-010).
          */}
          {summary.hasZeroWeight ? (
            <p className={styles.note}>
              비중이 0%인 대상은 '고정 비중'에는 담기지 않지만, 자동 배분 전략의 후보 집합에는
              그대로 남습니다.
            </p>
          ) : null}
        </>
      )}

      <p className={styles.note}>
        담을 대상 자체를 바꾸려면{" "}
        <Link to={SECTOR_WEIGHT_STEP_PATH}>투자 대상 고르기로 돌아가세요</Link>.
      </p>
    </section>
  );
}

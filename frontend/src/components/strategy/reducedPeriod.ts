import type { PeriodReduction } from "./analysisSupport";

export type ReducedPeriodInput = {
  /** 워밍업을 채운 뒤 평가할 수 있는 공통 범위 */
  window: { start: string; end: string };
  reduction: PeriodReduction;
  /** `reduction.bindingCandidate`를 사용자가 읽을 이름. 찾지 못하면 호출자가 식별자를 그대로 넘긴다 */
  bindingName: string;
  /** 지금 설정으로 계산한 워밍업(거래일) */
  warmupTradingDays: number;
  /** 워밍업을 이루는 항목. 없으면 워밍업이라고만 적는다 */
  causes: readonly WarmupCause[];
};

/**
 * 워밍업을 이루는 항목 하나와 지금 값(T066의 "현재 관측 기간·최근 제외 기간").
 *
 * 이름과 단위는 등록표 `warmupRule.sum`이 가리키는 축의 `label`·`unit`을 그대로 넘긴다. 화면이 "관측 기간"
 * 같은 낱말을 전략마다 적어 두지 않는다(FR-005).
 */
export type WarmupCause = { label: string; value: number; unit?: string };

/**
 * 평가할 수 있는 범위가 그만큼인 까닭(SC-004의 '원인'). 분석 조건의 범위 안내 옆에서 펼쳐 본다.
 *
 * <p><b>"줄었다"고 말하지 않는다.</b> 사용자는 더 긴 범위를 고른 적이 없다. 우리가 정해 주는 범위이므로
 * 빼앗긴 것처럼 말하면 겪지 않은 일을 겪은 것으로 읽힌다(2026-09-22, `S15P21E205-406`).
 *
 * <p>자료가 언제부터 있는지와, 지금 설정이 요구하는 워밍업이 무엇으로 이루어졌는지를 적는다. 둘 다 사용자가
 * 바꿀 수 있는 것과 바꿀 수 없는 것을 가르는 값이라, 기간을 늘리려면 무엇을 건드려야 하는지가 이 문장에서 나온다.
 */
export function describeReducedCause({
  reduction,
  window,
  bindingName,
  warmupTradingDays,
  causes,
}: ReducedPeriodInput): string {
  const parts = causes.map(
    (item) => `${item.label} ${item.value}${item.unit ?? ""}`,
  );
  const cause =
    parts.length > 0
      ? `지금 설정의 ${parts.join("·")}(워밍업 ${warmupTradingDays}거래일)`
      : `워밍업 ${warmupTradingDays}거래일`;
  return (
    `${bindingName}의 자료가 ${reduction.startWithoutWarmup}부터 있고 ${cause}을 먼저 채워야 해서 ` +
    `평가는 ${window.start}부터 시작합니다.`
  );
}

import type { StrategyView } from "@/types/strategyRegistry";

/**
 * 워밍업과 최소 평가 기간을 함께 본 판정(FR-011, FR-011a).
 *
 * <p><b>이 카드는 판정하지 않는다.</b> 계산에는 001이 돌려주는 공통 평가 가능 범위와 사용자가 고른
 * 최소 평가 기간이 필요하고, 그 둘은 카드가 아는 값이 아니다. 카드는 결과와 문장을 받아 보이기만
 * 한다.
 *
 * <p><b>판정하지 못한 상태에 `ok`를 쓰지 않는다.</b> 그 둘은 다른 사실이고, 접으면 화면이 하지 않은
 * 판정의 결과를 말하게 된다(원칙 IV). 001의 평가 기간 조회가 아직 붙지 않은 동안 호출자는
 * `unknown`을 넘긴다.
 */
export type WarmupVerdict =
  /** 워밍업을 채우고도 평가할 구간이 충분히 남는다. */
  | { state: "ok" }
  /** 고를 수 있으나 평가 구간이 짧아진다. 카드는 적지 않고 고른 뒤 분석 기간 행이 기간을 말한다. */
  | { state: "reduced"; message: string }
  /** 워밍업조차 채우지 못하거나 남는 기간이 최소 평가 기간에 못 미친다. */
  | { state: "blocked"; message: string }
  /** 판정에 필요한 값을 아직 받지 못했다. **고를 수 없다는 뜻이 아니다.** */
  | { state: "unknown"; message: string };

/** 계약이 이유를 싣기로 해 놓고 싣지 않았을 때 쓰는 문장. 이유를 지어내지 않는다. */
const REASON_MISSING = "고를 수 없는 이유를 확인하지 못했습니다.";

/**
 * 안내의 무게. `blocking`은 고를 수 없다는 뜻이고, `pending`은 판정하지 못했을 뿐 고를 수 있다는 뜻이다. 둘을
 * 같은 경고색으로 그리면 하지 않은 판정(막힘)을 화면이 말하게 된다(원칙 IV).
 */
export type NoticeTone = "blocking" | "pending";

export type CardNotice = { key: string; text: string; tone: NoticeTone };

/** 목록 아래 한 곳에 모아 적은 안내. 카드는 그 번호로 가리키고, 보조기술에는 id로 문장이 읽힌다. */
export type SharedNoticeRef = { id: string; number: number; tone: NoticeTone };

/**
 * 카드에 붙일 안내를 모은다.
 *
 * <p>고를 수 없는 이유는 둘이고 출처가 다르다. 등록표가 판정한 유효성 제약과, 화면이 계산한 워밍업
 * 판정이다. <b>둘 다 걸렸으면 둘 다 보인다</b> — 하나만 보이면 그것을 고쳐도 여전히 고를 수 없는
 * 카드를 사용자가 마주하게 된다.
 *
 * <p><b>'고를 수 없음' 같은 딱지를 앞에 붙이지 않는다.</b> 사유 문장이 이미 무엇이 막혔는지 말하고,
 * 딱지는 기능이 꺼진 것 같은 인상만 남긴다. 상태는 문장으로 전달된다(NFR-002).
 */
export function collectNotices(
  strategy: StrategyView,
  warmup: WarmupVerdict,
): CardNotice[] {
  const notices: CardNotice[] = [];

  if (!strategy.selectable) {
    // 계약 §1은 `selectable`이 false면 `unselectableReason`을 반드시 싣는다. 비어 있다면 그것은
    // 계약 위반이고, **화면이 숨겨서 넘기지 않는다**. 다만 전략 하나의 결함으로 등록표 전체를
    // 버리지는 않는다 — 나머지 전략은 정상인데 함께 막으면 FR-018이 막으려던 상황보다 넓게 막게 된다.
    notices.push({
      key: "registry",
      text: strategy.unselectableReason ?? REASON_MISSING,
      tone: "blocking",
    });
  }

  // 'reduced'는 카드에 적지 않는다. 고를 수 있다는 뜻이라 선택을 가르지 않고, 평가 기간은 고른 뒤
  // 분석 기간 행이 말한다 — 목록에 같은 말을 다섯 벌 늘어놓으면 정작 고를 수 없는 카드의
  // 사유가 묻힌다(2026-09-22, `S15P21E205-406`). 'blocked'·'unknown'은 고를 수 있는지를 가르므로 남긴다.
  if (warmup.state === "blocked" || warmup.state === "unknown") {
    notices.push({
      key: "warmup",
      text: warmup.message,
      tone: warmup.state === "blocked" ? "blocking" : "pending",
    });
  }

  return notices;
}


import type { StrategyView } from "@/types/strategyRegistry";
import styles from "./StrategyCard.module.css";

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
  /** 고를 수 있으나 평가 구간이 짧아진다. 카드는 적지 않고 고른 뒤 오른쪽 안내가 기간을 말한다. */
  | { state: "reduced"; message: string }
  /** 워밍업조차 채우지 못하거나 남는 기간이 최소 평가 기간에 못 미친다. */
  | { state: "blocked"; message: string }
  /** 판정에 필요한 값을 아직 받지 못했다. **고를 수 없다는 뜻이 아니다.** */
  | { state: "unknown"; message: string };

interface StrategyCardProps {
  strategy: StrategyView;
  selected: boolean;
  /** 워밍업 판정. 계산은 호출자가 하고 카드는 결과만 받는다. */
  warmup: WarmupVerdict;
  /** 고를 수 있는 카드를 눌렀을 때만 불린다. */
  onSelect: () => void;
  /**
   * 라디오 그룹의 탭 차례를 목록이 정한다(roving tabindex). 탭 한 번으로 그룹에 들어오고
   * 그 안은 화살표로 옮겨 다닌다 — 전략이 늘어도 탭 횟수가 늘지 않는다.
   */
  tabIndex: number;
}

/** 계약이 이유를 싣기로 해 놓고 싣지 않았을 때 쓰는 문장. 이유를 지어내지 않는다. */
const REASON_MISSING = "고를 수 없는 이유를 확인하지 못했습니다.";

type CardNotice = { key: string; text: string };

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
function collectNotices(
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
    });
  }

  // 'reduced'는 카드에 적지 않는다. 고를 수 있다는 뜻이라 선택을 가르지 않고, 평가 기간은 고른 뒤
  // 오른쪽 안내가 한 줄로 말한다 — 목록에 같은 말을 다섯 벌 늘어놓으면 정작 고를 수 없는 카드의
  // 사유가 묻힌다(2026-09-22, `S15P21E205-406`). 'blocked'·'unknown'은 고를 수 있는지를 가르므로 남긴다.
  if (warmup.state === "blocked" || warmup.state === "unknown") {
    notices.push({ key: "warmup", text: warmup.message });
  }

  return notices;
}

/**
 * 전략 선택 목록의 항목 하나(FR-001).
 *
 * <p><b>이름과 한 줄 설명, 그리고 고를 수 없을 때의 사유만 보인다.</b> 축 라벨·기본값·허용 범위는
 * 등록표 응답에 함께 실려 오지만 이 항목은 그것을 그리지 않는다 — 왼쪽은 빠르게 견주는 자리이고,
 * 고른 전략의 자세한 내용은 오른쪽 패널이 맡는다.
 *
 * <p><b>순위·추천 문구·우승 배지를 두지 않는다.</b> 다섯 방식이 퀀트 알고리즘 전체이거나 그들
 * 사이에 우열이 있다는 인상을 주는 표시를 넣지 않는다(FR-001).
 *
 * <p><b>고를 수 없는 항목도 초점을 받고 읽힌다.</b> `disabled` 대신 `aria-disabled`를 쓰는 까닭이다 —
 * `disabled`를 걸면 키보드 사용자가 닿지 못해 <b>왜</b> 고를 수 없는지 읽을 방법이 사라진다
 * (NFR-001). 글자를 흐리게 지워 버리지도 않는다. 눌러도 선택되지 않게 막는 것은 처리부에서 한다.
 *
 * <p><b>상태를 색으로만 알리지 않는다.</b> 선택 불가와 기간 축소는 문장으로 함께 전달하고,
 * 스크린리더에는 `aria-checked`·`aria-disabled`·`aria-describedby`로 전달한다(NFR-002).
 */
export function StrategyCard({
  strategy,
  selected,
  warmup,
  onSelect,
  tabIndex,
}: StrategyCardProps) {
  const notices = collectNotices(strategy, warmup);

  // 워밍업을 판정하지 못한 것은 고를 수 없다는 뜻이 아니다. 등록표가 이미 내린 판정을 그대로 두고,
  // 판정하지 못했다는 사실만 문장으로 알린다. 실행 직전의 최종 판정은 서버가 다시 한다(FR-006).
  const selectable = strategy.selectable && warmup.state !== "blocked";
  const noticeId =
    notices.length > 0
      ? `strategy-card-notice-${strategy.strategyId}`
      : undefined;
  const descriptionId = `strategy-card-description-${strategy.strategyId}`;
  const describedBy = noticeId ? `${descriptionId} ${noticeId}` : descriptionId;

  return (
    <button
      type="button"
      role="radio"
      className={styles.card}
      // aria-disabled 는 "지금은 누를 수 없다"를 알리면서 초점은 남긴다.
      aria-disabled={!selectable}
      // **고를 수 없게 되어도 고른 사실은 그대로 싣는다.** 고른 뒤에 최소 평가 기간이 바뀌어
      // 워밍업 판정이 막히면 이 항목은 `selectable`을 잃지만 여전히 사용자가 고른 전략이다.
      // 여기서 속성을 빼면 테두리로는 선택 상태가 보이는데 스크린리더에는 전달되지 않아, 같은
      // 사실이 보는 사람에 따라 달라진다(NFR-002).
      aria-checked={selected}
      aria-describedby={describedBy}
      tabIndex={tabIndex}
      data-selected={selected ? "true" : undefined}
      data-selectable={selectable ? "true" : "false"}
      data-warmup={warmup.state}
      onClick={() => {
        if (selectable) onSelect();
      }}
    >
      <span className={styles.indicator} aria-hidden="true" />
      <span className={styles.body}>
        <span className={styles.name}>{strategy.name}</span>
        <span id={descriptionId} className={styles.description}>
          {strategy.description}
        </span>
        {notices.length > 0 && (
          <span id={noticeId} className={styles.notice}>
            {notices.map((notice) => (
              <span
                key={notice.key}
                className={styles.reason}
                data-kind={notice.key}
              >
                {notice.text}
              </span>
            ))}
          </span>
        )}
      </span>
    </button>
  );
}

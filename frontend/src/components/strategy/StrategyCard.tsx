import type { StrategyView } from "@/types/strategyRegistry";
import {
  collectNotices,
  type SharedNoticeRef,
  type WarmupVerdict,
} from "./cardNotices";
import styles from "./StrategyCard.module.css";

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
  /**
   * 목록 아래 한 곳에 모아 적은 안내. 문장을 키로, 모은 자리의 id·번호·무게를 값으로 받는다. 목록이 넘기지
   * 않으면(카드 하나만 쓰는 자리) 안내를 카드 안에 적는다
   */
  shared?: ReadonlyMap<string, SharedNoticeRef>;
}

/**
 * 전략 선택 목록의 항목 하나(FR-001).
 *
 * <p><b>이름과 한 줄 설명, 그리고 고를 수 없을 때의 사유만 보인다.</b> 축 라벨·기본값·허용 범위는
 * 등록표 응답에 함께 실려 오지만 이 항목은 그것을 그리지 않는다 — 카드 줄은 빠르게 견주는 자리이고,
 * 고른 전략의 자세한 내용은 아래 상세 설정 카드가 맡는다. 사유는 목록 아래에 모아 적고 카드는 이름 옆 번호로
 * 가리켜, 사유가 있는 카드만 높아지거나 같은 문장이 카드마다 되풀이되지 않게 한다.
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
  shared,
}: StrategyCardProps) {
  const all = collectNotices(strategy, warmup);
  // 목록 아래에 모은 안내는 카드 안에 번호만 두고, 문장은 그 한 곳에서 읽힌다.
  const sharedRefs = all.flatMap((notice) => {
    const ref = shared?.get(notice.text);
    return ref ? [ref] : [];
  });
  const notices = all.filter((notice) => !shared?.has(notice.text));

  // 워밍업을 판정하지 못한 것은 고를 수 없다는 뜻이 아니다. 등록표가 이미 내린 판정을 그대로 두고,
  // 판정하지 못했다는 사실만 문장으로 알린다. 실행 직전의 최종 판정은 서버가 다시 한다(FR-006).
  const selectable = strategy.selectable && warmup.state !== "blocked";
  const noticeId =
    notices.length > 0
      ? `strategy-card-notice-${strategy.strategyId}`
      : undefined;
  const descriptionId = `strategy-card-description-${strategy.strategyId}`;
  const describedBy = [
    descriptionId,
    noticeId,
    ...sharedRefs.map((ref) => ref.id),
  ]
    .filter(Boolean)
    .join(" ");

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
        <span className={styles.nameLine}>
          <span className={styles.name}>{strategy.name}</span>
          {sharedRefs.length > 0 && (
            <span className={styles.markers} aria-hidden="true">
              {sharedRefs.map((ref) => (
                <span key={ref.id} className={styles.marker} data-tone={ref.tone}>
                  {ref.number}
                </span>
              ))}
            </span>
          )}
        </span>
        <span id={descriptionId} className={styles.description}>
          {strategy.description}
        </span>
        {notices.length > 0 && (
          <span id={noticeId} className={styles.notice}>
            {notices.map((notice) => (
              <span
                key={notice.key}
                className={styles.reason}
                data-tone={notice.tone}
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

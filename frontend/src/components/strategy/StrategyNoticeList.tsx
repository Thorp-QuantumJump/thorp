import type { SharedNotice } from './cardNotices'
import styles from './StrategyNoticeList.module.css'

interface StrategyNoticeListProps {
  notices: readonly SharedNotice[]
}

/**
 * 전략 카드의 사유를 한 곳에 모아 적은 번호 목록(FR-011).
 *
 * <p><b>화면 머리의 제목 아래에 선다(2026-10-01).</b> 다른 주요 화면처럼 제목은 왼쪽, 설명은 오른쪽에
 * 두는 짜임에 맞추고, 카드 줄 바로 아래를 비워 카드에서 상세 설정으로 곧장 이어지게 한다. 카드는 이름
 * 옆에 같은 번호만 보이고, 보조기술에는 카드의 설명으로 이 문장이 그대로 읽힌다(`aria-describedby` → 항목의
 * `id`). 그래서 문장의 `id`는 카드 목록과 같은 결과(`collectSharedNotices`)에서 받는다.
 *
 * <p>사유가 없으면 아무것도 그리지 않는다. 판정하지 못했을 뿐 고를 수 있는 사유는 경고색을 쓰지 않는다.
 */
export function StrategyNoticeList({ notices }: StrategyNoticeListProps) {
  if (notices.length === 0) return null
  return (
    <ol className={styles.list} aria-label="전략 카드의 사유">
      {notices.map((notice) => (
        <li key={notice.id} className={styles.item}>
          <span className={styles.number} data-tone={notice.tone} aria-hidden="true">
            {notice.number}
          </span>
          <span>
            <span className={styles.names}>{notice.names.join(' · ')}</span>{' '}
            <span id={notice.id}>{notice.text}</span>
          </span>
        </li>
      ))}
    </ol>
  )
}

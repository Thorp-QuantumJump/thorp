import { Link } from 'react-router-dom'
import type { ReleaseLogEntry } from '@/types/learning'
import { CHANGE_LOG_ID } from './ChangeLog'
import { noticeLine, stageHash } from './curriculumCopy'
import { StageLandscape } from './StageLandscape'
import styles from './ReleaseNotice.module.css'

interface ReleaseNoticeProps {
  /** 7일 안의 안내 줄(최신 순서). 이 탭에서 닫은 줄은 미리 뺀다 */
  notices: readonly ReleaseLogEntry[]
  /** 로그인 사용자인가. 기록이 그대로라는 문장을 덧붙일지 정한다 */
  signedIn: boolean
  /** [닫기]. 합친 줄에 든 안내를 모두 닫는다 */
  onDismiss: (entryKeys: string[]) => void
  /** '변경 기록'. 아래 변경 기록을 펼치고 그 자리로 옮긴다 */
  onOpenChangeLog: () => void
  /** 단계 자리로 옮겨 가도 처음 떠난 화면을 잃지 않게 싣는 navigation state */
  linkState?: unknown
}

/**
 * 공개 안내 한 줄(013 FR-031 '공개 안내', design-notes §7).
 *
 * <p>7일 안의 단계 공개와 글 추가를 한 줄로 합쳐 보이고, '○단계 보기'·'변경 기록' 연결과 [닫기]를 둔다. 로그인
 * 사용자에게만, 그리고 1단계가 아닌 단계 공개가 들어 있을 때만 '지금까지의 읽은 기록, 경험치, 배지는 그대로입니다.'를
 * 덧붙인다.
 *
 * <p><b>상태 알림이 아니다.</b> 페이지를 열 때 이미 있는 내용이라 `role="status"`를 두지 않고 움직임도 없다. 닫은
 * 상태는 부르는 쪽이 그 탭에만 기억한다(`noticeStorage`).
 */
export function ReleaseNotice({ notices, signedIn, onDismiss, onOpenChangeLog, linkState }: ReleaseNoticeProps) {
  const line = noticeLine(notices, signedIn)
  if (!line) return null

  return (
    <div className={styles.notice} data-release-notice="">
      <span className={styles.icon} aria-hidden="true">
        <StageLandscape stageNumber={line.stageNumber} mini />
      </span>
      <div className={styles.body}>
        <p className={styles.text}>
          {line.text}
          <span className={styles.links}>
            <Link to={{ hash: stageHash(line.stageNumber) }} state={linkState}>
              {line.stageNumber}단계 보기
            </Link>
            <a
              href={`#${CHANGE_LOG_ID}`}
              onClick={(event) => {
                event.preventDefault()
                onOpenChangeLog()
              }}
            >
              변경 기록
            </a>
          </span>
        </p>
      </div>
      <button type="button" className={styles.close} aria-label="공개 안내 닫기" onClick={() => onDismiss(line.entryKeys)}>
        닫기
      </button>
    </div>
  )
}

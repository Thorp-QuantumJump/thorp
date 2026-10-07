import type { Ref } from 'react'
import type { ReleaseLogEntry } from '@/types/learning'
import { changeLogLine } from './curriculumCopy'
import styles from './ChangeLog.module.css'

/** 공개 안내의 '변경 기록' 연결이 가리키는 자리. */
export const CHANGE_LOG_ID = 'learning-change-log'

interface ChangeLogProps {
  /** 모든 변경 기록, 최신 순서 */
  entries: readonly ReleaseLogEntry[]
  open: boolean
  onToggle: (open: boolean) => void
  /** 펼침 단추(`summary`). 공개 안내의 '변경 기록'으로 옮겨 올 때 초점을 둔다 */
  summaryRef?: Ref<HTMLElement>
}

/**
 * 변경 기록(013 FR-031 '변경 기록', design-notes §7). 배우기 목록의 끝에 접힌 채 둔다.
 *
 * <p>줄마다 날짜와 공개한 단계나 더한 글, 새 글과 다시 쓴 글의 편수를 쓴다(예: "2026-11-19 · 2단계 공개 · 새 글
 * 3편, 다시 쓴 글 1편('수익과 손실')"). 기록이 그대로라는 문장은 넣지 않는다. 날짜는 한국 날짜다.
 */
export function ChangeLog({ entries, open, onToggle, summaryRef }: ChangeLogProps) {
  if (entries.length === 0) return null
  return (
    <details
      id={CHANGE_LOG_ID}
      className={styles.log}
      open={open}
      onToggle={(event) => onToggle(event.currentTarget.open)}
    >
      <summary ref={summaryRef} className={styles.summary}>
        변경 기록
      </summary>
      <ol className={styles.timeline}>
        {entries.map((entry) => {
          const { date, text } = changeLogLine(entry)
          return (
            <li key={entry.entryKey}>
              <time dateTime={date}>{date}</time>
              <span className={styles.dot} aria-hidden="true" />
              <span>{text}</span>
            </li>
          )
        })}
      </ol>
    </details>
  )
}

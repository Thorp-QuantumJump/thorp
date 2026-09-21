import { CloudSun, Layers, LineChart } from 'lucide-react'
import { forwardRef, useId } from 'react'
import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { useDisclosure } from '@/hooks/useDisclosure'
import type { MyRecordListItem } from '@/types/myRecords'
import { RecordDetailPanel } from './RecordDetailPanel'
import {
  KIND_LABEL,
  REOPEN_NOT_YET,
  STATUS_LABEL,
  WITHDRAWN_REASON,
  formatSavedAt,
  reasonLabel,
  reviewHref,
} from './recordLabels'
import styles from './records.module.css'

export type RecordLabels = {
  sector: (code: string) => string
  strategy: (id: string) => string
  /** 010 분야 이름. 목록을 받지 못했으면 코드 그대로다 — 지어내지 않는다 */
  regimeField: (id: string) => string
}

type Props = {
  item: MyRecordListItem
  labels: RecordLabels
  /** 처음부터 펼쳐 둔다(`?resultToken=`으로 들어온 행) */
  initiallyOpen?: boolean
}

// `disabled` 는 초점을 받지 못해 aria-describedby 의 사유가 읽히지 않는다(FR-021). 초점은 두고 눌러도 아무 일이 없다.
const DISABLED_LOOK = 'aria-disabled:opacity-50 aria-disabled:cursor-not-allowed'

const KIND_ICON = { ANALYSIS: LineChart, SEARCH_BUNDLE: Layers, REGIME_SEARCH: CloudSun } as const

/**
 * 목록의 한 항목(FR-001·FR-002, research R7).
 *
 * <p><b>주 모양 버튼은 '다시 보기' 하나다.</b> '설정 바꿔 비교'는 보조 모양이다 — 같은 크기 버튼 둘은 원칙 II
 * 위반이고 `MyRecordsPage.test`가 행마다 주 버튼 수를 단언한다.
 *
 * <p>상태와 사유는 성과와 다른 자리다(FR-014). 이 화면에는 성과 수치 자체가 없다.
 */
export const RecordRow = forwardRef<HTMLLIElement, Props>(function RecordRow(
  { item, labels, initiallyOpen = false },
  ref,
) {
  const panelId = useId()
  const detail = useDisclosure(panelId, initiallyOpen)
  const reasonId = useId()
  const href = reviewHref(item)
  const Icon = KIND_ICON[item.kind]
  const period = item.period
    ? `${item.period.start} ~ ${item.period.end}`
    : item.kind === 'REGIME_SEARCH'
      ? item.regime.baselineMonth.slice(0, 7)
      : null

  return (
    <li ref={ref} className={styles.row} tabIndex={-1} data-record-id={item.recordId}>
      <div className={styles.rowHead}>
        <span className={styles.kind}>
          <Icon size={14} aria-hidden="true" />
          {KIND_LABEL[item.kind]}
        </span>
        <h2 className={styles.name}>{item.name}</h2>
        <span className={styles.status} data-status={item.status}>
          {STATUS_LABEL[item.status]}
          {item.statusReason ? (
            <span className={styles.reason}> · {reasonLabel(item.statusReason)}</span>
          ) : null}
        </span>
      </div>

      <dl className={styles.facts}>
        {item.sectorCodes.length > 0 ? (
          <>
            <dt>섹터</dt>
            <dd>
              <ul className={styles.chips} aria-label="섹터">
                {item.sectorCodes.map((code) => (
                  <li key={code}>{labels.sector(code)}</li>
                ))}
              </ul>
            </dd>
          </>
        ) : null}
        {item.strategyIds.length > 0 ? (
          <>
            <dt>전략</dt>
            <dd>{item.strategyIds.map(labels.strategy).join(', ')}</dd>
          </>
        ) : null}
        {period ? (
          <>
            <dt>{item.kind === 'REGIME_SEARCH' ? '기준월' : '분석 기간'}</dt>
            <dd>{period}</dd>
          </>
        ) : null}
        <dt>저장일</dt>
        <dd>
          <time dateTime={item.savedAt}>{formatSavedAt(item.savedAt)}</time>
        </dd>
      </dl>

      <div className={styles.actions}>
        {href ? (
          <Button asChild>
            <Link to={href}>다시 보기</Link>
          </Button>
        ) : (
          <Button
            type="button"
            aria-disabled="true"
            aria-describedby={reasonId}
            className={DISABLED_LOOK}
          >
            다시 보기
          </Button>
        )}
        <Button
          type="button"
          variant="outline"
          aria-disabled="true"
          aria-describedby={`${reasonId}-reopen`}
          className={DISABLED_LOOK}
        >
          설정 바꿔 비교
        </Button>
        <Button type="button" variant="outline" {...detail.toggleProps}>
          {detail.open ? '접기' : '자세히'}
        </Button>
      </div>
      <p className={styles.srOnly} id={`${reasonId}-reopen`}>
        {REOPEN_NOT_YET}
      </p>
      {href ? null : (
        <p id={reasonId} className={styles.hint}>
          {WITHDRAWN_REASON}
        </p>
      )}

      {detail.open ? (
        <RecordDetailPanel id={panelId} recordId={item.recordId} labels={labels} onClose={detail.close} />
      ) : null}
    </li>
  )
})

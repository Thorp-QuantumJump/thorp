import { motion, useReducedMotion } from 'motion/react'
import { Popover } from 'radix-ui'
import { useId } from 'react'
import { HOVER_CLOSE_DELAY_MS, HOVER_OPEN_DELAY_MS, useHoverOpen } from '@/components/common/useHoverOpen'
import type { FieldSummary } from '@/types/marketFlow'
import { DataStateLine } from './DataState'
import { formatNarrative, stateLineLabels } from './marketFlowFormat'
import styles from './MarketFlow.module.css'

interface FieldSummaryCardProps {
  field: FieldSummary
  selected?: boolean
  onOpen: (field: FieldSummary) => void
  /** 목록 머리에 적은 마지막 갱신 시각. 이 분야의 갱신 시각이 같으면 줄에서 되풀이하지 않는다 */
  listUpdatedAt?: string | null
}

/**
 * 분야 하나를 여는 메뉴 버튼. 일곱 개가 한 줄에 따로따로 선다.
 *
 * 변화 요약과 대상월은 버튼을 가리키거나 키보드 초점을 줄 때 그 아래 말풍선으로 뜬다. 여닫기는 도움말
 * (InlineHelp)과 같은 규칙이다 — Esc 로 닫히고, 마우스를 말풍선 위로 옮겨도 닫히지 않으며, 화면 끝에서는
 * 안쪽으로 비켜 선다. 고른 분야의 요약은 아래 상세 머리에 늘 펼쳐 있다(009 FR-002).
 *
 * 보조기기는 말풍선을 열지 않아도 같은 문장을 버튼의 설명으로 읽는다. 말풍선은 닫히면 문서에서 빠지므로
 * 설명은 늘 있는 숨은 칸이 맡는다. 수집 실패·자료 없음·예시 자료 표는 버튼 위에 늘 보이고 버튼 이름에도
 * 들어간다(FR-011·FR-014).
 */
export function FieldSummaryCard({ field, selected = false, onOpen, listUpdatedAt = null }: FieldSummaryCardProps) {
  const reducedMotion = useReducedMotion()
  const tipId = useId()
  const labels = stateLineLabels(field.status, field.isExampleData)
  const { open, settle, schedule } = useHoverOpen()
  const summaryText = formatNarrative(field.narrative) ?? '변화 요약 대신 자료 상태를 확인해 주세요.'
  const stateLine = (
    <DataStateLine
      status={field.status}
      referenceMonth={field.referenceMonth}
      lastUpdatedAt={field.lastUpdatedAt}
      listUpdatedAt={listUpdatedAt}
      isExampleData={field.isExampleData}
      showLabels={false}
    />
  )
  return (
    <motion.article
      className={styles.summaryCard}
      data-selected={selected}
      initial={reducedMotion ? false : { opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: reducedMotion ? 0 : 0.22, delay: reducedMotion ? 0 : (field.displayOrder - 1) * 0.025 }}
    >
      {selected ? (
        <motion.span
          className={styles.selectionLine}
          layoutId={reducedMotion ? undefined : 'market-flow-selection'}
          transition={{ duration: 0.2 }}
          aria-hidden="true"
        />
      ) : null}
      <Popover.Root open={open} onOpenChange={settle}>
        <h3 className={styles.summaryHeading}>
          <Popover.Anchor asChild>
            <button
              type="button"
              className={styles.openButton}
              aria-label={`${field.displayName} 분야 열기${labels.length > 0 ? ` (${labels.join(', ')})` : ''}`}
              aria-pressed={selected}
              aria-controls="market-flow-detail"
              aria-describedby={tipId}
              onPointerEnter={() => schedule(true, HOVER_OPEN_DELAY_MS)}
              onPointerLeave={() => schedule(false, HOVER_CLOSE_DELAY_MS)}
              onFocus={() => settle(true)}
              onBlur={() => settle(false)}
              onClick={() => onOpen(field)}
            >
              <span>{field.displayName}</span>
              {labels.map((label) => <span key={label} className={styles.stateLabel}>{label}</span>)}
            </button>
          </Popover.Anchor>
        </h3>
        <div id={tipId} className={styles.srOnly}>
          <p>{summaryText}</p>
          {stateLine}
        </div>
        <Popover.Portal>
          <Popover.Content
            // 보조기기에는 위의 숨은 설명이 같은 문장을 읽어 주므로 말풍선은 한 번 더 읽히지 않게 한다.
            aria-hidden="true"
            className={styles.fieldTip}
            side="bottom"
            align="start"
            sideOffset={8}
            collisionPadding={16}
            // 초점은 버튼에 그대로 둔다. 옮기면 버튼이 흐려져 곧바로 닫힌다.
            onOpenAutoFocus={(event) => event.preventDefault()}
            onCloseAutoFocus={(event) => event.preventDefault()}
            // 버튼에서 말풍선으로 마우스를 옮기는 동안, 그리고 말풍선 위에 있는 동안 닫히지 않는다.
            onPointerEnter={() => settle(true)}
            onPointerLeave={() => schedule(false, HOVER_CLOSE_DELAY_MS)}
          >
            <p className={styles.narrative}>{summaryText}</p>
            {stateLine}
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
    </motion.article>
  )
}

/**
 * 분야 메뉴 끝의 'Thorp 종합 비교'(009 FR-019). 일곱 분야를 함께 견주므로 분야 버튼과 같은 줄에 서되 노랑으로 가른다.
 * 분야 버튼과 같은 상세 영역을 열고(aria-controls), 같은 화살표 이동 묶음에 든다. 누를 때만 조회한다(010 US3).
 */
export function CompositeSummaryCard({ selected = false, onOpen }: { selected?: boolean; onOpen: () => void }) {
  const reducedMotion = useReducedMotion()
  return (
    <motion.article
      className={`${styles.summaryCard} ${styles.compositeCard}`}
      data-selected={selected}
      initial={reducedMotion ? false : { opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: reducedMotion ? 0 : 0.22, delay: reducedMotion ? 0 : 0.18 }}
    >
      {selected ? (
        <motion.span
          className={styles.selectionLine}
          layoutId={reducedMotion ? undefined : 'market-flow-selection'}
          transition={{ duration: 0.2 }}
          aria-hidden="true"
        />
      ) : null}
      <h3 className={styles.summaryHeading}>
        <button type="button" className={styles.openButton} aria-pressed={selected} aria-controls="market-flow-detail" onClick={onOpen}>
          Thorp 종합 비교
        </button>
      </h3>
    </motion.article>
  )
}

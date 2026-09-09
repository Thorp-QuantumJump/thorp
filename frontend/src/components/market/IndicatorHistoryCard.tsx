import { ArrowUpRight, X } from 'lucide-react'
import { AnimatePresence, motion, useIsPresent, useReducedMotion, type Transition } from 'motion/react'
import { useCallback, useRef, type ReactNode } from 'react'
import { useIndicatorEscape } from '@/hooks/useIndicatorEscape'
import type { Indicator } from '@/types/market'
import { formatDataDate, formatIndicatorValue } from '@/utils/marketFormat'
import { IndicatorHistoryChart } from './IndicatorHistoryChart'
import styles from './IndicatorHistoryCard.module.css'

interface IndicatorHistoryCardProps {
  indicator: Indicator
  index: number
  variant: 'market' | 'macro'
  className: string
  expanded: boolean
  onActiveChange: (id: string | null) => void
  children: ReactNode
}

const cardLayoutTransition: Transition = {
  duration: 0.48,
  ease: [0.22, 1, 0.36, 1],
}

interface ExpandedIndicatorPanelProps {
  indicator: Indicator
  panelId: string
  layoutId: string
  onClose: () => void
}

/** 닫기 중에도 패널을 유지해 원래 카드 크기로 돌아가며, 퇴장 중 입력은 막는다. */
function ExpandedIndicatorPanel({ indicator, panelId, layoutId, onClose }: ExpandedIndicatorPanelProps) {
  const reducedMotion = useReducedMotion()
  const isPresent = useIsPresent()

  return (
    <motion.div
      id={panelId}
      layoutId={layoutId}
      className={styles.panel}
      role="region"
      aria-label={`${indicator.name} 확대 차트`}
      aria-hidden={!isPresent}
      inert={!isPresent}
      data-present={isPresent}
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{
        layout: reducedMotion ? { duration: 0 } : cardLayoutTransition,
        opacity: { duration: reducedMotion ? 0 : 0.38 },
      }}
    >
      <motion.div
        initial={reducedMotion ? false : { opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0, transition: { duration: reducedMotion ? 0 : 0.12 } }}
        transition={{ delay: reducedMotion ? 0 : 0.18, duration: reducedMotion ? 0 : 0.24 }}
      >
        <div className={styles.panelHeader}>
          <span>최근 5년 · 예시 데이터</span>
          <button
            type="button"
            className={styles.closeButton}
            onClick={onClose}
            aria-label={`${indicator.name} 확대 차트 닫기`}
          >
            <X size={17} aria-hidden="true" />
          </button>
        </div>
        <h3>{indicator.name}</h3>
        <div className={styles.panelValue}>
          <strong>{formatIndicatorValue(indicator)}</strong>
          <span>{formatDataDate(indicator.observationDate)} 기준</span>
        </div>
        <IndicatorHistoryChart indicator={indicator} />
        <p className={styles.interpretation}>{indicator.interpretation}</p>
        <div className={styles.panelFooter}>
          <span>{indicator.source.provider}</span>
          <span>장기 이력은 모의 데이터</span>
        </div>
      </motion.div>
    </motion.div>
  )
}

/** 원래 카드 자리를 유지하면서 선택한 카드만 확대하며 hover·키보드·터치를 지원한다. */
export function IndicatorHistoryCard({
  indicator,
  index,
  variant,
  className,
  expanded,
  onActiveChange,
  children,
}: IndicatorHistoryCardProps) {
  const triggerRef = useRef<HTMLButtonElement>(null)
  const reducedMotion = useReducedMotion()
  const panelId = `indicator-history-${indicator.id}`
  const layoutId = `indicator-card-${indicator.id}`

  /** 명시적으로 닫으면 원래 버튼으로 초점을 돌려 키보드 탐색을 이어간다. */
  const closePanel = useCallback(() => {
    onActiveChange(null)
    triggerRef.current?.focus({ preventScroll: true })
  }, [onActiveChange])

  useIndicatorEscape(expanded, closePanel)

  return (
    <motion.article
      className={styles.slot}
      data-variant={variant}
      data-expanded={expanded}
      initial={reducedMotion ? false : { opacity: 0, y: 28 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: false, amount: 0.2 }}
      transition={{ delay: reducedMotion ? 0 : index * 0.07, duration: reducedMotion ? 0 : 0.55 }}
      onPointerEnter={(event) => {
        if (event.pointerType === 'mouse') onActiveChange(indicator.id)
      }}
      onPointerLeave={(event) => {
        if (event.pointerType === 'mouse' && expanded) onActiveChange(null)
      }}
      onBlur={(event) => {
        if (expanded && !event.currentTarget.contains(event.relatedTarget)) onActiveChange(null)
      }}
    >
      <motion.div
        layoutId={layoutId}
        className={`${className} ${styles.preview}`}
        transition={{ layout: reducedMotion ? { duration: 0 } : cardLayoutTransition }}
      >
        <div aria-hidden={expanded}>{children}</div>
        <button
          ref={triggerRef}
          className={styles.expandButton}
          type="button"
          aria-label={`${indicator.name} 5년 차트 ${expanded ? '닫기' : '보기'}`}
          aria-expanded={expanded}
          aria-controls={panelId}
          onClick={() => onActiveChange(expanded ? null : indicator.id)}
        >
          최근 3개월 · 예시 <span>5년 보기 <ArrowUpRight size={13} aria-hidden="true" /></span>
        </button>
      </motion.div>
      <AnimatePresence initial={false}>
        {expanded && (
          <ExpandedIndicatorPanel
            key={indicator.id}
            indicator={indicator}
            panelId={panelId}
            layoutId={layoutId}
            onClose={closePanel}
          />
        )}
      </AnimatePresence>
    </motion.article>
  )
}

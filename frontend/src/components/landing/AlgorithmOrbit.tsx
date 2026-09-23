import { ArrowUpRight } from 'lucide-react'
import { motion, useInView, useReducedMotion } from 'motion/react'
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
  type PointerEvent,
} from 'react'
import { Link } from 'react-router-dom'
import { fetchStrategyRegistry, normalizeStrategyRegistryError } from '@/api/strategyRegistry'
import type { StrategyView } from '@/types/strategyRegistry'
import styles from '@/pages/LandingPage.module.css'

function relativeIndex(index: number, selectedIndex: number, length: number) {
  let difference = index - selectedIndex
  if (difference > length / 2) difference -= length
  if (difference < -length / 2) difference += length
  return difference
}

/**
 * 등록된 배분 전략을 원근에 놓고 탐색하는 3D 캐러셀이다.
 *
 * <p><b>전략 지식을 갖지 않는다.</b> 이름·설명·개수는 `GET /strategies/registry` 응답에서만
 * 읽는다(003 FR-005). 예전에는 데모 자료의 다섯 이름을 상수로 들고 있어서 등록표에 없는 전략을
 * 첫 화면이 광고했다.
 *
 * <p><b>성과를 싣지 않는다.</b> 카드에 CAGR·MDD·SHARPE를 적던 자리를 비웠다. 실행하지 않은
 * 전략에는 성과가 없고, 있는 것처럼 보이면 첫 화면이 추천을 하는 것이 된다.
 *
 * <p>조회가 실패하면 대신할 값을 지어내지 않고 실패를 말한 뒤 다시 시도할 길을 둔다(003 FR-018).
 */
export function AlgorithmOrbit() {
  const [strategies, setStrategies] = useState<StrategyView[]>([])
  const [loadState, setLoadState] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [selectedIndex, setSelectedIndex] = useState(0)
  const [stageWidth, setStageWidth] = useState(960)
  const [announcement, setAnnouncement] = useState('')
  const stageRef = useRef<HTMLDivElement>(null)
  const pointerStartRef = useRef<number | null>(null)
  const didDragRef = useRef(false)
  const reducedMotion = useReducedMotion()
  const isInView = useInView(stageRef, { amount: 0.2 })

  const loadStrategies = useCallback((signal?: AbortSignal) => {
    setLoadState('loading')
    return fetchStrategyRegistry(undefined, signal)
      .then((registry) => {
        setStrategies(registry.strategies)
        setSelectedIndex(0)
        setLoadState('ready')
      })
      .catch((error: unknown) => {
        if (normalizeStrategyRegistryError(error).canceled) return
        setStrategies([])
        setLoadState('failed')
      })
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    void loadStrategies(controller.signal)
    return () => controller.abort()
  }, [loadStrategies])

  useEffect(() => {
    const stage = stageRef.current
    if (!stage) return

    const observer = new ResizeObserver(([entry]) => {
      if (entry) setStageWidth(entry.contentRect.width)
    })
    observer.observe(stage)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    if (reducedMotion || !isInView || strategies.length < 2) return

    const intervalId = window.setInterval(() => {
      if (document.visibilityState === 'visible') {
        setSelectedIndex((selectedIndex + 1) % strategies.length)
      }
    }, 3600)

    return () => window.clearInterval(intervalId)
  }, [isInView, reducedMotion, selectedIndex, strategies.length])

  const move = (direction: number) => {
    if (strategies.length === 0) return
    const nextIndex = (selectedIndex + direction + strategies.length) % strategies.length
    setSelectedIndex(nextIndex)
    setAnnouncement(`${strategies[nextIndex]?.name ?? ''} 전략 선택됨`)
  }

  const selectStrategy = (index: number) => {
    setSelectedIndex(index)
    setAnnouncement(`${strategies[index]?.name ?? ''} 전략 선택됨`)
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'ArrowLeft') {
      event.preventDefault()
      move(-1)
    }
    if (event.key === 'ArrowRight') {
      event.preventDefault()
      move(1)
    }
  }

  const handlePointerDown = (event: PointerEvent<HTMLDivElement>) => {
    pointerStartRef.current = event.clientX
    didDragRef.current = false
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  const handlePointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (pointerStartRef.current === null) return
    if (Math.abs(event.clientX - pointerStartRef.current) > 8) {
      didDragRef.current = true
    }
  }

  const handlePointerUp = (event: PointerEvent<HTMLDivElement>) => {
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
    if (pointerStartRef.current === null) return
    const distance = event.clientX - pointerStartRef.current
    pointerStartRef.current = null
    didDragRef.current = Math.abs(distance) > 8
    if (Math.abs(distance) < 44) return
    move(distance > 0 ? -1 : 1)
  }

  const handlePointerCancel = (event: PointerEvent<HTMLDivElement>) => {
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
    pointerStartRef.current = null
    didDragRef.current = false
  }

  const preventClickAfterDrag = (event: MouseEvent<HTMLElement>) => {
    if (!didDragRef.current) return false
    event.preventDefault()
    event.stopPropagation()
    didDragRef.current = false
    return true
  }

  const xRadius = Math.min(280, Math.max(112, stageWidth * 0.27))

  return (
    <motion.section
      id="algorithms"
      className={styles.algorithmSection}
      initial={{ opacity: 0, y: 54 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, amount: 0.12 }}
      transition={{ duration: 0.82, ease: [0.22, 1, 0.36, 1] }}
      aria-labelledby="algorithm-title"
    >
      {/*
        '검증된 알고리즘'이라고 적던 자리다. 검증 구분은 판정 기준(PD-12)이 정해지기 전까지
        결과 화면에서도 말하지 못하는 것이라(005 FR-012) 첫 화면이 먼저 단정할 수 없다.
      */}
      <div className={styles.algorithmHeader}>
        <p>ALGORITHM LIBRARY</p>
        <h2 id="algorithm-title">
          배분 규칙을
          <br />
          같은 조건에서 비교하세요.
        </h2>
        <span>카드를 눌러 각 전략이 비중을 어떤 규칙으로 정하는지 확인하세요.</span>
      </div>

      <div
        ref={stageRef}
        className={styles.orbitStage}
        role="region"
        aria-label="알고리즘 선택 캐러셀"
        aria-roledescription="3D 캐러셀"
        tabIndex={0}
        onKeyDown={handleKeyDown}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerCancel}
      >
        <div className={styles.orbitFloor} aria-hidden="true" />
        {loadState === 'loading' ? (
          <p className={styles.orbitNotice} role="status">
            전략 목록을 불러오는 중입니다.
          </p>
        ) : null}
        {loadState === 'failed' ? (
          <div className={styles.orbitNotice} role="status">
            <p>전략 목록을 불러오지 못했습니다.</p>
            <button type="button" onClick={() => void loadStrategies()}>
              다시 시도
            </button>
          </div>
        ) : null}
        {strategies.map((strategy, index) => {
          const relative = relativeIndex(index, selectedIndex, strategies.length)
          const angle = relative * ((Math.PI * 2) / strategies.length)
          const depth = Math.cos(angle)
          const isSelected = index === selectedIndex
          const x = Math.sin(angle) * xRadius
          const y = -(1 - depth) * Math.min(72, stageWidth * 0.07)
          const z = depth * 190
          const scale = 0.72 + ((depth + 1) / 2) * 0.28
          const opacity = 0.36 + ((depth + 1) / 2) * 0.64
          const blur = reducedMotion ? 0 : (1 - depth) * 2

          return (
            <motion.article
              key={strategy.strategyId}
              className={styles.algorithmCard}
              data-selected={isSelected}
              aria-current={isSelected ? 'true' : undefined}
              animate={{
                x,
                y,
                z: reducedMotion ? 0 : z,
                scale: reducedMotion && !isSelected ? 0.84 : scale,
                rotateX: reducedMotion ? 0 : (1 - depth) * -4,
                rotateY: reducedMotion ? 0 : Math.sin(angle) * -13,
                opacity,
                filter: `blur(${blur.toFixed(1)}px)`,
              }}
              transition={reducedMotion
                ? { duration: 0 }
                : { type: 'spring', stiffness: 145, damping: 24, mass: 0.85 }}
              style={{ zIndex: Math.round((depth + 1) * 50) }}
            >
              <div className={styles.cardIndex}>
                <span>0{index + 1}</span>
              </div>
              <div>
                <p>QUANT STRATEGY</p>
                <h3>{strategy.name}</h3>
                <span>{strategy.description}</span>
              </div>
              {isSelected ? (
                <Link to="/diagnosis/sectors" onClick={preventClickAfterDrag}>
                  이 전략 백테스트하기
                  <ArrowUpRight aria-hidden="true" size={18} />
                </Link>
              ) : (
                <button
                  type="button"
                  className={styles.cardSelectButton}
                  aria-label={`${strategy.name} 전략 선택`}
                  onClick={(event) => {
                    if (!preventClickAfterDrag(event)) selectStrategy(index)
                  }}
                  tabIndex={0}
                />
              )}
            </motion.article>
          )
        })}
      </div>

      <p className={styles.srOnly} aria-live="polite">
        {announcement}
      </p>
    </motion.section>
  )
}

import { ArrowUpRight } from 'lucide-react'
import { motion, useReducedMotion } from 'motion/react'
import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { Link } from 'react-router-dom'
import { strategyResults } from '@/data/backtestMock'
import styles from '@/pages/LandingPage.module.css'

function relativeIndex(index: number, selectedIndex: number, length: number) {
  let difference = index - selectedIndex
  if (difference > length / 2) difference -= length
  if (difference < -length / 2) difference += length
  return difference
}

/** 전략 카드를 원근에 놓고 앞쪽 카드를 선명하게 탐색하는 3D 캐러셀이다. */
export function AlgorithmOrbit() {
  const [selectedIndex, setSelectedIndex] = useState(0)
  const [stageWidth, setStageWidth] = useState(960)
  const stageRef = useRef<HTMLDivElement>(null)
  const pointerStartRef = useRef<number | null>(null)
  const reducedMotion = useReducedMotion()

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
    if (reducedMotion) return

    const intervalId = window.setInterval(() => {
      if (document.visibilityState === 'visible') {
        setSelectedIndex((selectedIndex + 1) % strategyResults.length)
      }
    }, 3600)

    return () => window.clearInterval(intervalId)
  }, [reducedMotion, selectedIndex])

  const move = (direction: number) => {
    setSelectedIndex((current) => (current + direction + strategyResults.length) % strategyResults.length)
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
  }

  const handlePointerUp = (event: PointerEvent<HTMLDivElement>) => {
    if (pointerStartRef.current === null) return
    const distance = event.clientX - pointerStartRef.current
    pointerStartRef.current = null
    if (Math.abs(distance) < 44) return
    move(distance > 0 ? -1 : 1)
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
      <div className={styles.algorithmHeader}>
        <p>ALGORITHM LIBRARY</p>
        <h2 id="algorithm-title">
          검증된 알고리즘을
          <br />
          사용해보세요.
        </h2>
        <span>카드를 눌러 각 전략의 수익과 위험을 비교해보세요.</span>
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
        onPointerUp={handlePointerUp}
        onPointerCancel={() => {
          pointerStartRef.current = null
        }}
      >
        <div className={styles.orbitFloor} aria-hidden="true" />
        {strategyResults.map((strategy, index) => {
          const relative = relativeIndex(index, selectedIndex, strategyResults.length)
          const angle = relative * ((Math.PI * 2) / strategyResults.length)
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
              key={strategy.id}
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
                <span>{strategy.parameter}</span>
              </div>
              <div>
                <p>QUANT STRATEGY</p>
                <h3>{strategy.name}</h3>
                <span>{strategy.description}</span>
              </div>
              <dl>
                <div><dt>CAGR</dt><dd>{strategy.cagr}</dd></div>
                <div><dt>MDD</dt><dd>{strategy.mdd}</dd></div>
                <div><dt>SHARPE</dt><dd>{strategy.sharpe}</dd></div>
              </dl>
              {isSelected ? (
                <Link to="/backtest">
                  이 전략 백테스트하기
                  <ArrowUpRight aria-hidden="true" size={18} />
                </Link>
              ) : (
                <button
                  type="button"
                  className={styles.cardSelectButton}
                  aria-label={`${strategy.name} 전략 선택`}
                  onClick={() => setSelectedIndex(index)}
                  tabIndex={0}
                />
              )}
            </motion.article>
          )
        })}
      </div>

      <p className={styles.srOnly} aria-live="polite">
        {strategyResults[selectedIndex]?.name}
      </p>
    </motion.section>
  )
}

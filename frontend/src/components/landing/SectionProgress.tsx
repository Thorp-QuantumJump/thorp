import { useMotionValueEvent, useReducedMotion, type MotionValue } from 'motion/react'
import { useState } from 'react'
import styles from '@/pages/LandingPage.module.css'

/** 칸마다의 이름. 점에 마우스를 올리면 보인다. */
const SECTIONS = ['서비스', '시장 흐름', '백테스트', '배분 규칙']

/** 지금 몇 번째 칸인지 오른쪽에 점으로 보인다. 점을 누르면 그 칸으로 간다. */
export function SectionProgress({ progress, targets }: { progress: MotionValue<number>; targets: string[] }) {
  const reducedMotion = useReducedMotion()
  const [active, setActive] = useState(0)

  useMotionValueEvent(progress, 'change', (value) => {
    setActive(Math.min(targets.length - 1, Math.round(value * (targets.length - 1))))
  })

  return (
    <nav className={styles.sectionProgress} aria-label="첫 화면 칸 이동">
      {targets.map((target, index) => (
        <button
          key={target}
          type="button"
          aria-label={`${SECTIONS[index] ?? index + 1} 칸으로 이동`}
          aria-current={index === active ? 'true' : undefined}
          onClick={() =>
            document.getElementById(target)?.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'start' })
          }
        >
          <span>{SECTIONS[index]}</span>
        </button>
      ))}
    </nav>
  )
}

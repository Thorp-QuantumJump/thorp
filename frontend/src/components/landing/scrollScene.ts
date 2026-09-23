import { useReducedMotion, useScroll, useSpring, useTransform, type MotionStyle } from 'motion/react'
import { createContext, useContext, type RefObject } from 'react'

/**
 * 첫 화면은 `main`이 스스로 스크롤한다(한 칸씩 넘기기). 칸들이 자기 움직임을 그 스크롤에 묶을 수
 * 있도록 그 요소를 나눠 준다.
 */
export const LandingScrollContext = createContext<RefObject<HTMLElement | null> | null>(null)

type Scene = { copy: MotionStyle; window: MotionStyle }

const still: Scene = { copy: {}, window: {} }

/**
 * 스크롤한 만큼 따라가는 칸 움직임. 한 번 재생되고 끝나는 들어옴이 아니라, 올리고 내리는 만큼
 * 앞뒤로 따라간다.
 *
 * - `leave`: 첫 칸처럼 화면을 떠나기만 한다. 글은 올라가며 흐려지고 창은 뒤로 기울며 작아진다.
 * - `pass`: 아래에서 들어와 가운데 섰다가 위로 나간다. `from`은 글이 밀려 들어오는 쪽이다.
 */
export function useScrollScene(target: RefObject<HTMLElement | null>, mode: 'leave' | 'pass', from: 'left' | 'right' = 'left'): Scene {
  const container = useContext(LandingScrollContext)
  const reducedMotion = useReducedMotion()
  const { scrollYProgress } = useScroll({
    container: container ?? undefined,
    target,
    offset: mode === 'leave' ? ['start start', 'end start'] : ['start end', 'end start'],
  })
  // 휠 한 번에 튀지 않도록 살짝 늦게 따라가게 한다.
  const p = useSpring(scrollYProgress, { stiffness: 140, damping: 26, mass: 0.35 })
  const side = from === 'left' ? -1 : 1

  const stops = mode === 'leave' ? [0, 1] : [0, 0.5, 1]
  const copyY = useTransform(p, stops, mode === 'leave' ? [0, -90] : [70, 0, -50])
  const copyX = useTransform(p, stops, mode === 'leave' ? [0, 0] : [side * 90, 0, side * -30])
  const copyOpacity = useTransform(p, stops, mode === 'leave' ? [1, 0.15] : [0, 1, 0.25])
  const windowY = useTransform(p, stops, mode === 'leave' ? [0, -40] : [140, 0, -70])
  const windowScale = useTransform(p, stops, mode === 'leave' ? [1, 0.88] : [0.86, 1, 0.94])
  const windowRotate = useTransform(p, stops, mode === 'leave' ? [0, 14] : [18, 0, -8])
  const windowOpacity = useTransform(p, stops, mode === 'leave' ? [1, 0.35] : [0.2, 1, 0.5])

  if (reducedMotion) return still
  return {
    copy: { x: copyX, y: copyY, opacity: copyOpacity },
    window: { y: windowY, scale: windowScale, rotateX: windowRotate, opacity: windowOpacity, transformPerspective: 1400 },
  }
}

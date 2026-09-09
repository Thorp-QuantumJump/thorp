import { useEffect, useRef, useState } from 'react'
import styles from '@/pages/LandingPage.module.css'
import atmosphereShader from './market-atmosphere.wgsl?raw'

/** WebGPU가 가능할 때만 Hero 배경을 렌더링하고, 실패 시 SVG 배경을 그대로 유지한다. */
export function MarketAtmosphereCanvas() {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const pointerRef = useRef({ x: 0.5, y: 0.5 })
  const [isReady, setIsReady] = useState(false)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || !('gpu' in navigator)) return

    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
    if (reducedMotion.matches) return

    let disposed = false
    let frameId = 0
    let gpuContext: { dispose: () => void } | null = null
    let isVisible = true
    let scheduleRender: () => void = () => {}

    const stopRender = () => {
      window.cancelAnimationFrame(frameId)
      frameId = 0
    }

    const visibilityObserver = new IntersectionObserver(
      ([entry]) => {
        isVisible = entry?.isIntersecting ?? false
        if (isVisible) scheduleRender()
        else stopRender()
      },
      { threshold: 0.02 },
    )
    visibilityObserver.observe(canvas)

    const handlePointerMove = (event: PointerEvent) => {
      pointerRef.current = {
        x: event.clientX / Math.max(window.innerWidth, 1),
        y: event.clientY / Math.max(window.innerHeight, 1),
      }
    }
    window.addEventListener('pointermove', handlePointerMove, { passive: true })

    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') scheduleRender()
      else stopRender()
    }
    document.addEventListener('visibilitychange', handleVisibilityChange)

    const start = async () => {
      try {
        const { effect, frame, init, surface } = await import('vgpu')
        const gpu = await init()
        if (disposed) {
          gpu.dispose()
          return
        }

        gpuContext = gpu
        const target = surface(gpu, canvas, { dpr: [1, 1.5] })
        const atmosphere = effect(gpu, atmosphereShader, {
          set: { params: { data: [0, 0.5, 0.5, 0] } },
        })
        atmosphere.compileSync(target)
        if (disposed) return

        const startedAt = performance.now()
        let hasRendered = false

        const render = () => {
          frameId = 0
          if (disposed || !isVisible || document.visibilityState !== 'visible') return

          const elapsed = (performance.now() - startedAt) / 1000
          const pointer = pointerRef.current
          atmosphere.set({ params: { data: [elapsed, pointer.x, pointer.y, 0] } })
          frame(gpu, (currentFrame) => {
            currentFrame.pass(target, atmosphere)
          })
          if (!hasRendered) {
            hasRendered = true
            setIsReady(true)
          }

          scheduleRender()
        }

        scheduleRender = () => {
          if (
            !disposed
            && isVisible
            && document.visibilityState === 'visible'
            && frameId === 0
          ) {
            frameId = window.requestAnimationFrame(render)
          }
        }
        scheduleRender()
      } catch {
        if (!disposed) {
          setIsReady(false)
          gpuContext?.dispose()
          gpuContext = null
        }
      }
    }

    void start()

    return () => {
      disposed = true
      stopRender()
      window.removeEventListener('pointermove', handlePointerMove)
      document.removeEventListener('visibilitychange', handleVisibilityChange)
      visibilityObserver.disconnect()
      gpuContext?.dispose()
      gpuContext = null
    }
  }, [])

  return (
    <canvas
      ref={canvasRef}
      className={styles.atmosphereCanvas}
      data-ready={isReady}
      aria-hidden="true"
    />
  )
}

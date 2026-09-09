import { useEffect, useRef, useState } from 'react'
import styles from '@/pages/LandingPage.module.css'

const atmosphereShader = `
struct Params {
  data: vec4f,
}

@group(0) @binding(0) var<uniform> params: Params;

fn hash(value: f32) -> f32 {
  return fract(sin(value * 127.1) * 43758.5453);
}

fn softLine(value: f32, linePosition: f32, width: f32) -> f32 {
  return 1.0 - smoothstep(width, width * 2.4, abs(value - linePosition));
}

@fragment fn fs_main(@location(0) uv: vec2f) -> @location(0) vec4f {
  let time = params.data.x;
  let pointer = params.data.yz;
  let p = vec2f(uv.x, 1.0 - uv.y);

  let minorGridX = softLine(fract(p.x * 22.0), 0.0, 0.006);
  let minorGridY = softLine(fract(p.y * 12.0), 0.0, 0.008);
  let grid = max(minorGridX, minorGridY) * 0.075;

  let wave = 0.29
    + sin(p.x * 8.0 + time * 0.14) * 0.035
    + sin(p.x * 19.0 - time * 0.1) * 0.018
    + p.x * 0.055;
  let waveCore = softLine(p.y, wave, 0.0025);
  let waveGlow = exp(-abs(p.y - wave) * 85.0);

  let candleCell = floor(p.x * 34.0);
  let candleX = fract(p.x * 34.0);
  let candleCenter = 0.24
    + sin(candleCell * 1.47) * 0.035
    + (hash(candleCell) - 0.5) * 0.11
    + p.x * 0.035;
  let candleHeight = 0.022 + hash(candleCell + 9.0) * 0.065;
  let candleBody = step(0.27, candleX) * step(candleX, 0.73)
    * step(abs(p.y - candleCenter), candleHeight * 0.45);
  let candleWick = step(0.47, candleX) * step(candleX, 0.53)
    * step(abs(p.y - candleCenter), candleHeight);
  let candleMask = max(candleBody, candleWick) * smoothstep(0.08, 0.27, p.y)
    * (1.0 - smoothstep(0.42, 0.56, p.y));

  let pointerGlow = exp(-distance(p, vec2f(pointer.x, 1.0 - pointer.y)) * 4.8) * 0.07;
  let centerGlow = exp(-distance(p, vec2f(0.56, 0.56)) * 3.2) * 0.12;
  let movingGlow = exp(-distance(p, vec2f(0.2 + fract(time * 0.015) * 0.7, wave)) * 8.0) * 0.18;

  var color = vec3f(0.008, 0.026, 0.019);
  color += vec3f(0.05, 0.14, 0.09) * grid;
  color += vec3f(0.012, 0.78, 0.35) * waveCore * 0.38;
  color += vec3f(0.0, 0.52, 0.22) * waveGlow * 0.18;
  color += vec3f(0.05, 0.48, 0.22) * candleMask * 0.24;
  color += vec3f(0.0, 0.55, 0.22) * (pointerGlow + centerGlow + movingGlow);

  let vignette = smoothstep(0.94, 0.2, distance(p, vec2f(0.5, 0.5)));
  color *= 0.68 + vignette * 0.42;

  return vec4f(color, 1.0);
}
`

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

import { cleanup, configure } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

/**
 * `findBy*`·`waitFor`가 기다리는 시간의 한계.
 *
 * <p>기본값 1초는 이 저장소의 화면 테스트에 모자란다. 파일 96개가 jsdom을 각자 만들며 병렬로 도는
 * 동안 느린 화면(`SignupPage`는 혼자 20초가 넘는다)이 1초 안에 다음 단계를 그리지 못해, 코드가
 * 멀쩡해도 실행할 때마다 다른 파일이 실패한다. 한계를 늘려도 단언은 그대로이므로 진짜 고장은 여전히
 * 실패한다.
 */
configure({ asyncUtilTimeout: 5_000 })

Object.defineProperty(window, 'scrollTo', {
  configurable: true,
  value: vi.fn(),
})

/**
 * jsdom에는 `ResizeObserver`가 없다. 차트의 크기 측정(`@visx/responsive`의 `ParentSize`)이 마운트
 * 시점에 생성자를 부르므로, 없으면 차트를 품은 화면이 렌더 도중 터진다.
 *
 * <p>크기를 알려주지 않는 관찰자다. 폭이 0이면 차트는 아무것도 그리지 않으므로, 화면 테스트는
 * 차트 없이 나머지를 확인한다. 실제로 그려진 요소를 봐야 하는 테스트는 `ParentSize`를 그 파일에서
 * 직접 대역으로 바꿔 크기를 정한다(`EquityChart.test.tsx`).
 */
if (!('ResizeObserver' in window)) {
  Object.defineProperty(window, 'ResizeObserver', {
    configurable: true,
    value: class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  })
}

/**
 * jsdom은 SVG 경로의 길이를 재지 않는다. 차트가 선 길이로 파선 꼬리와 강조 구간을 계산하므로,
 * 없으면 경로를 그리는 화면이 마운트 직후 터진다. 길이에 기대는 표현은 이 저장소의 결과 그래프가
 * 쓰지 않으므로 0으로 답한다.
 */
const svgPathPrototype: { getTotalLength?: () => number } | undefined =
  typeof SVGPathElement === 'undefined'
    ? (globalThis as { SVGElement?: { prototype: object } }).SVGElement?.prototype
    : SVGPathElement.prototype
if (svgPathPrototype && !svgPathPrototype.getTotalLength) {
  svgPathPrototype.getTotalLength = () => 0
}

afterEach(() => {
  cleanup()
})

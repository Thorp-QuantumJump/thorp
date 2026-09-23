import { motion, useReducedMotion } from 'motion/react'
import styles from '@/pages/LandingPage.module.css'

/*
  첫 화면 오른쪽의 결과 비교 화면 **예시**다. 어떤 실행에서 나온 값이 아니며, 결과 화면이 무엇을
  보여 주는지 모양으로 알리는 그림이다. 창 머리에 '예시 화면'을 밝히고, 보조기기에서는 숨긴다
  (읽을 거리가 아니라 그림이다). 실제 수치는 결과 화면이 자기 실행에서 읽어 보인다.
*/
const EXAMPLE = {
  condition: '반도체 · 2차전지 · 금융 · 2021.09–2026.08',
  metrics: [
    { label: '비용 차감 후 수익', value: '+24.6%', direction: 'up' },
    { label: '가장 크게 줄었던 폭', value: '−12.1%', direction: 'down' },
    { label: '거래비용', value: '84,200원' },
    { label: '시작 금액', value: '1,000만원' },
  ],
  weights: [
    { name: '반도체', share: 38 },
    { name: '금융', share: 31 },
    { name: '2차전지', share: 19 },
    { name: '조선', share: 12 },
  ],
  rules: [
    { name: '역변동성', net: '+24.6%', drawdown: '−12.1%', cost: '84,200원', selected: true },
    { name: '동일 비중', net: '+13.2%', drawdown: '−18.7%', cost: '61,000원' },
    { name: '최소분산', net: '+9.8%', drawdown: '−9.4%', cost: '72,500원' },
  ],
} as const

const selectedPath = 'M12 150 C60 142 90 156 130 134 S210 110 250 112 S320 80 350 68 S380 56 388 50'
const comparisonPaths = [
  'M12 150 C60 144 100 152 140 140 S220 126 260 118 S340 98 388 88',
  'M12 150 C60 146 100 158 140 146 S220 138 260 126 S340 118 388 104',
]

export function ResultPreviewWindow() {
  const reducedMotion = useReducedMotion()
  const draw = (delay: number) =>
    reducedMotion
      ? {}
      : {
          initial: { pathLength: 0 },
          animate: { pathLength: 1 },
          transition: { duration: 1.6, delay, ease: [0.22, 1, 0.36, 1] as const },
        }

  return (
    <div className={styles.previewWindow} aria-hidden="true">
      <div className={styles.previewBar}>
        <span className={styles.previewDots}><i /><i /><i /></span>
        <span className={styles.previewAddress}>thorp · 결과 · <b>{EXAMPLE.condition}</b></span>
        <span className={styles.previewExample}>예시 화면</span>
      </div>

      <dl className={styles.previewMetrics}>
        {EXAMPLE.metrics.map((metric) => (
          <div key={metric.label}>
            <dd data-direction={'direction' in metric ? metric.direction : undefined}>{metric.value}</dd>
            <dt>{metric.label}</dt>
          </div>
        ))}
      </dl>

      <div className={styles.previewPanes}>
        <div className={styles.previewPane}>
          <p className={styles.previewPaneTitle}>누적 자산가치 <b>역변동성 · 동일 비중</b></p>
          <svg className={styles.previewChart} viewBox="0 0 400 190">
            {[40, 90, 140].map((y) => <line key={y} x1="12" x2="388" y1={y} y2={y} />)}
            <path className={styles.previewArea} d={`${selectedPath} L388 176 L12 176 Z`} />
            <motion.path className={styles.previewOther} d={comparisonPaths[1]} {...draw(0.65)} />
            <motion.path className={styles.previewBase} d={comparisonPaths[0]} {...draw(0.5)} />
            <motion.path className={styles.previewSelected} d={selectedPath} {...draw(0.3)} />
          </svg>
        </div>
        <div className={styles.previewPane}>
          <p className={styles.previewPaneTitle}>마지막 비중</p>
          <ul className={styles.previewWeights}>
            {EXAMPLE.weights.map((weight, index) => (
              <li key={weight.name}>
                <span>{weight.name}</span>
                <span className={styles.previewTrack}>
                  <i style={{ width: `${weight.share}%`, opacity: 1 - index * 0.22 }} />
                </span>
                <b>{weight.share}%</b>
              </li>
            ))}
          </ul>
        </div>
      </div>

      <table className={styles.previewTable}>
        <thead>
          <tr><th>배분 규칙</th><th>수익</th><th>하락 폭</th><th data-optional="true">거래비용</th></tr>
        </thead>
        <tbody>
          {EXAMPLE.rules.map((rule) => (
            <tr key={rule.name} data-selected={'selected' in rule ? true : undefined}>
              <td>{rule.name}</td>
              <td data-direction="up">{rule.net}</td>
              <td data-direction="down">{rule.drawdown}</td>
              <td data-optional="true">{rule.cost}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

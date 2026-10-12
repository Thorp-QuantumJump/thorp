import { useId } from 'react'
import { stageColor } from '../stageColors'
import { MaterialDefs } from './MaterialDefs'
import { paint, svgId, type Material } from './materials'
import { medalPath, type MedalKind } from './medalShapes'
import styles from './GainGrid.module.css'

/**
 * 비로그인 '나의 학습' 화면의 '나의 학습에 쌓이는 것' 세 칸(013 FR-020, design-notes §3.5, 목업 `gainGrid`).
 *
 * <p>칸마다 재질만 살린 실루엣(레벨 표장은 초록 금속, 글 도장과 단계 완주 메달, 배지는 갈래 에나멜)과 한 문장이 있다.
 * 숫자·글리프·윤곽선·점선은 없다. 실선 회색은 '아직 안 함'으로, 점선은 '준비 중'으로 읽히기 때문이다. 그림은 보조
 * 기술에 읽히지 않는다.
 */
export function GainGrid() {
  const headingId = useId()
  const prefix = svgId(useId())
  const items = [
    {
      title: '레벨 표장',
      text: '글을 읽고 확인 문제를 풀면 경험치가 쌓여 레벨이 오릅니다.',
      art: (
        <svg viewBox="0 0 100 100" focusable="false">
          <Silhouette
            prefix={prefix}
            kind="level"
            x={50}
            y={50}
            scale={1}
            fill={paint(prefix, 'gmetal')}
            face
          />
        </svg>
      ),
    },
    {
      title: '글 도장과 단계 완주 메달',
      text: '읽은 글마다 도장이 찍히고, 한 단계의 글을 모두 읽으면 그 단계의 메달을 받습니다.',
      art: (
        <svg viewBox="0 0 170 100" focusable="false">
          {[16, 44, 72].map((x) => (
            <g key={x}>
              <circle cx={x} cy={68.5} r="11" fill="rgb(0 0 0 / 0.35)" />
              <circle cx={x} cy={66} r="11" fill={paint(prefix, 'glow')} opacity={0.6} />
              <circle cx={x} cy={66} r="7.6" fill="rgb(0 0 0 / 0.22)" />
            </g>
          ))}
          <Silhouette
            prefix={prefix}
            kind="stage"
            x={128}
            y={50}
            scale={0.96}
            fill={stageColor(1)}
          />
        </svg>
      ),
    },
    {
      title: '배지 일곱 갈래',
      text: '읽은 글, 첫 답 정답, 익힌 용어처럼 받는 기준을 모두 미리 공개합니다.',
      art: (
        <svg viewBox="0 0 200 100" focusable="false">
          <Silhouette
            prefix={prefix}
            kind="read"
            x={34}
            y={52}
            scale={0.64}
            fill={paint(prefix, 'en-read')}
          />
          <Silhouette
            prefix={prefix}
            kind="first"
            x={100}
            y={52}
            scale={0.64}
            fill={paint(prefix, 'en-first')}
          />
          <Silhouette
            prefix={prefix}
            kind="all"
            x={166}
            y={52}
            scale={0.64}
            fill={paint(prefix, 'en-all')}
          />
        </svg>
      ),
    },
  ]
  const materials: Material[] = [
    'gmetal',
    'glow',
    'glass',
    'shadow',
    'en-read',
    'en-first',
    'en-all',
  ]

  return (
    <section className={styles.gain} aria-labelledby={headingId}>
      <svg className={styles.defs} width="0" height="0" focusable="false" aria-hidden="true">
        <defs>
          <MaterialDefs prefix={prefix} materials={materials} />
        </defs>
      </svg>
      <h2 id={headingId} className={styles.title}>
        나의 학습에 쌓이는 것
      </h2>
      <ul className={styles.items}>
        {items.map((item) => (
          <li key={item.title} className={styles.item}>
            <span className={styles.art} aria-hidden="true">
              {item.art}
            </span>
            <span className={styles.text}>
              <b>{item.title}</b>
              <span>{item.text}</span>
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}

/**
 * 재질 기운만 남긴 실루엣. 숫자·글리프·윤곽선 없이 채움과 유리 광택만 있다. 채움은 재질(`url(#…)`)이나 CSS 색 값이라
 * `style`로 칠한다. 표장의 안쪽 면은 바탕색 토큰을 반쯤 비친다.
 */
function Silhouette({
  prefix,
  kind,
  x,
  y,
  scale,
  fill,
  face = false,
}: {
  prefix: string
  kind: MedalKind
  x: number
  y: number
  scale: number
  fill: string
  face?: boolean
}) {
  return (
    <g transform={`translate(${x} ${y}) scale(${scale}) translate(-50 -50)`}>
      <ellipse cx="50" cy="97" rx="31" ry="3.4" fill={paint(prefix, 'shadow')} />
      <path d={medalPath(kind)} style={{ fill }} opacity={0.55} />
      {face ? <path className={styles.face} d={medalPath(kind, 7)} /> : null}
      <path d={medalPath(kind)} fill={paint(prefix, 'glass')} opacity={0.8} />
    </g>
  )
}

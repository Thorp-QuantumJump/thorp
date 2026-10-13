import { useId, type CSSProperties } from 'react'
import { stageColor } from '../stageColors'
import { roundCoord } from './svgCoord'
import styles from './ArticleStamp.module.css'

/**
 * 글 도장의 다섯 모양(013 design-notes §4 상태 문법).
 *
 * - `done`: 초록 잉크로 채워 찍은 도장과 체크(완료)
 * - `reading`: 실선 회색 윤곽과 초록 진행 호(읽는 중)
 * - `unread`: 실선 회색 윤곽, 속 비움(안 읽음)
 * - `preparing`: 점선 윤곽, 속 비움(준비 중)
 * - `neutral`: 윤곽선 없이 채운 원판과 번호만. 비로그인이거나 기록이 없을 때다. 실선 회색 윤곽은 '아직 안 함'으로
 *   읽히므로 쓰지 않는다(§3.5)
 */
export type ArticleStampState = 'done' | 'reading' | 'unread' | 'preparing' | 'neutral'

interface ArticleStampProps {
  state: ArticleStampState
  /** 화면 크기(px). 같은 그림을 줄이면 상태를 나르는 선이 가늘어지므로 자리마다 크기를 정해 그린다 */
  size: number
  /** 도장 안 글자(예: `1-4`). 없으면 번호 없이 그린다 */
  label?: string
  /** `reading`의 읽은 비율(0~100) */
  percent?: number
  /** 안쪽 고리의 옅은 단계 색(꾸밈). 상태를 나타내지 않는다 */
  stageNumber?: number
  /** 도장마다 조금씩 다른 기울기의 순서 */
  tilt?: number
  /** 아래에 까는 종이 원판 */
  disc?: boolean
  /** `done`에서 번호 대신 큰 체크만 둔다. 번호가 바로 옆 카드에 있는 자리다 */
  checkOnly?: boolean
  /**
   * `done`의 '방금 찍힌 도장'(목업 `stampSVG`의 `fresh`). 속을 진하게 채우고 더 기울이며 바깥에 옅은 후광 한 겹을
   * 두른다. 완료 카드의 마지막 칸이 쓴다
   */
  fresh?: boolean
  /** `reading`의 진행 호 끝에 빛 점을 둔다. 지금 이어 읽을 글의 도장이다(목업 `tip`) */
  tip?: boolean
  className?: string
}

const TILTS = [-4, 3, -2, 5]

/**
 * 글 도장 하나(44×44 viewBox). 커리큘럼 보기의 글 노드와 단계 표식이 쓰고, 단계 머리의 도장 줄과 완료 카드(B13)도 이
 * 도장을 쓴다.
 *
 * <p><b>그림은 보조 기술에 읽히지 않는다(`aria-hidden`).</b> 상태는 옆의 글자('완료'·'62% 읽는 중'·'안 읽음')가
 * 원본이다(design-notes §6). 필터 식별자는 `useId`로 만들어 같은 도장이 여러 번 그려져도 겹치지 않는다.
 *
 * <p><b>색은 의미 토큰이다.</b> 선과 면의 색은 `ArticleStamp.module.css`가 `index.css` 토큰으로 정하고 이 파일은
 * 모양과 굵기만 정한다. 단계 장식 색만 `--stamp-ring`으로 넘긴다.
 * 각 부분에는 `data-part`를 붙여 둔다.
 */
export function ArticleStamp({
  state,
  size,
  label,
  percent = 0,
  stageNumber,
  tilt = 0,
  disc = false,
  checkOnly = false,
  fresh = false,
  tip = false,
  className,
}: ArticleStampProps) {
  const id = useId().replace(/[^a-zA-Z0-9_-]/g, '')
  // viewBox 한 칸에 해당하는 화면 px의 역수다. 선 굵기를 화면 기준으로 맞출 때 곱한다(44px 칸이면 1).
  const viewPerPixel = 44 / size
  const fontSize = roundCoord(Math.max(11, 12 * viewPerPixel))
  const classes = [styles.stamp, className].filter(Boolean).join(' ')
  const ringColor = stageNumber ? ({ ['--stamp-ring' as string]: stageColor(stageNumber) } as CSSProperties) : undefined

  const number = label ? (
    <text data-part="number" className={styles.number} x="22" y="22.5" textAnchor="middle" dominantBaseline="central" fontSize={fontSize} fontWeight={700}>
      {label}
    </text>
  ) : null
  const paper = disc ? <circle data-part="paper" className={styles.paper} cx="22" cy="22" r="21.3" /> : null

  if (state === 'neutral') {
    return (
      <svg className={classes} viewBox="0 0 44 44" width={size} height={size} aria-hidden="true" focusable="false" data-stamp="neutral">
        {paper}
        <circle data-part="groove" cx="22" cy="22" r="16.6" fill="none" stroke="#000" strokeOpacity={0.3} strokeWidth={roundCoord(1.3 * viewPerPixel)} />
        {number}
      </svg>
    )
  }

  if (state === 'done') {
    const markSize = roundCoord(Math.max(10, 12 * viewPerPixel))
    const mark =
      checkOnly || !label ? (
        <path data-part="mark" className={styles.mark} d="M14 22.5l5 4.8 11-10.6" strokeWidth={roundCoord(Math.max(2.6, 2 * viewPerPixel))} strokeLinecap="round" strokeLinejoin="round" />
      ) : (
        <>
          <text data-part="mark" className={styles.markText} x="22" y={roundCoord(16 + (markSize - 10) * 0.3)} textAnchor="middle" dominantBaseline="central" fontSize={markSize} fontWeight={800}>
            {label}
          </text>
          <path className={styles.mark} d="M18.4 29.6l2.7 2.5 5.3-5.1" strokeWidth={roundCoord(Math.max(2, 1.6 * viewPerPixel))} strokeLinecap="round" strokeLinejoin="round" />
        </>
      )
    return (
      <svg className={classes} viewBox="0 0 44 44" width={size} height={size} aria-hidden="true" focusable="false" overflow="visible" data-stamp="done" data-fresh={fresh ? '' : undefined}>
        <defs>
          {/* 도장 잉크가 종이에 번진 가장자리. 그림자 번짐은 쓰지 않는다 */}
          <filter id={`${id}-ink`} x="-15%" y="-15%" width="130%" height="130%">
            <feTurbulence type="fractalNoise" baseFrequency="1.1" numOctaves={2} seed={7} result="noise" />
            <feDisplacementMap in="SourceGraphic" in2="noise" scale={0.9} xChannelSelector="R" yChannelSelector="G" result="moved" />
            <feColorMatrix in="noise" type="matrix" values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 -1.3 1.62" result="mask" />
            <feComposite in="moved" in2="mask" operator="in" />
          </filter>
        </defs>
        {paper}
        {fresh ? <circle data-part="halo" className={styles.halo} cx="22" cy="22" r="23.5" strokeWidth={roundCoord(3 * viewPerPixel)} /> : null}
        <g transform={`rotate(${fresh ? -8 : TILTS[tilt % TILTS.length]} 22 22)`} filter={`url(#${id}-ink)`}>
          <circle
            data-part="ink"
            className={styles.ink}
            cx="22"
            cy="22"
            r="20"
            fillOpacity={fresh ? 0.45 : 0.2}
            strokeWidth={roundCoord((fresh ? 2.6 : 2.2) * viewPerPixel)}
          />
          <circle className={styles.inkRing} cx="22" cy="22" r="16.6" strokeWidth={roundCoord(viewPerPixel)} />
          {mark}
        </g>
      </svg>
    )
  }

  const preparing = state === 'preparing'
  return (
    <svg className={classes} style={ringColor} viewBox="0 0 44 44" width={size} height={size} aria-hidden="true" focusable="false" overflow="visible" data-stamp={state}>
      {paper}
      <circle
        data-part="outline"
        className={styles.outline}
        cx="22"
        cy="22"
        r="20"
        strokeWidth={roundCoord(1.5 * viewPerPixel)}
        strokeDasharray={preparing ? `${roundCoord(4 * viewPerPixel)} ${roundCoord(3 * viewPerPixel)}` : undefined}
      />
      {preparing ? (
        <circle
          data-part="inner"
          className={styles.innerPreparing}
          cx="22"
          cy="22"
          r="16.6"
          strokeWidth={roundCoord(viewPerPixel)}
          strokeDasharray={`${roundCoord(3 * viewPerPixel)} ${roundCoord(3 * viewPerPixel)}`}
        />
      ) : (
        <circle data-part="ring" className={styles.ring} cx="22" cy="22" r="16.6" strokeWidth={roundCoord(viewPerPixel)} />
      )}
      {number}
      {state === 'reading' ? (
        <circle
          data-part="progress"
          className={styles.progress}
          cx="22"
          cy="22"
          r="20"
          strokeWidth={roundCoord(3 * viewPerPixel)}
          strokeLinecap="round"
          pathLength={100}
          strokeDasharray={`${percent} 100`}
          transform="rotate(-90 22 22)"
        />
      ) : null}
      {state === 'reading' && tip ? <ProgressTip id={id} percent={percent} viewPerPixel={viewPerPixel} /> : null}
    </svg>
  )
}

/** 진행 호 끝의 빛 점(목업 `tip`). 옅은 빛 번짐 위에 밝은 점 하나다. */
function ProgressTip({ id, percent, viewPerPixel }: { id: string; percent: number; viewPerPixel: number }) {
  const angle = ((-90 + percent * 3.6) * Math.PI) / 180
  const x = roundCoord(22 + 20 * Math.cos(angle))
  const y = roundCoord(22 + 20 * Math.sin(angle))
  return (
    <g data-part="tip" className={styles.tip}>
      <defs>
        <filter id={`${id}-soft`} x="-1" y="-1" width="3" height="3">
          <feGaussianBlur stdDeviation="2.4" />
        </filter>
      </defs>
      <circle className={styles.tipGlow} cx={x} cy={y} r={roundCoord(7 * viewPerPixel)} opacity={0.3} filter={`url(#${id}-soft)`} />
      <circle className={styles.tipCore} cx={x} cy={y} r={roundCoord(3.4 * viewPerPixel)} />
    </g>
  )
}

import type { ReactNode } from 'react'
import { mutedStageColor, stageColor } from '../stageColors'
import styles from './StageLandscape.module.css'

interface StageLandscapeProps {
  stageNumber: number
  /** 준비 중 단계는 점선 '설계 도면'으로 그리고 면을 채우지 않는다(design-notes §3.1) */
  preparing?: boolean
  /** 목록 구분 줄처럼 작은 자리는 큰 도형만 남긴 판을 쓴다 */
  mini?: boolean
  className?: string
}

/**
 * 단계 풍경(013 design-notes §3.1). 2색 선화이고 160×110 viewBox다.
 *
 * <p>1단계 칸 나뉜 진열장, 2단계 배와 잔물결, 3단계 갈라지는 물길, 4단계 등대, 5단계 갈림길 표지판과 돋보기,
 * 6단계 창밖 하늘 띠다. 2단계 물결은 수평 대칭이라 주가 차트로 읽히지 않고, 오르는 사선·축·숫자·동전·로켓은 그리지
 * 않는다. 색은 `currentColor` 하나이고 면의 옅은 채움은 CSS가 정한다. 그림이므로 보조 기술에 읽히지 않는다.
 * 여섯 풍경 밖의 단계 번호는 그리지 않는다.
 */
export function StageLandscape({ stageNumber, preparing = false, mini = false, className }: StageLandscapeProps) {
  const drawing = mini ? miniDrawing(stageNumber) : fullDrawing(stageNumber)
  if (!drawing) return null
  const classes = [styles.land, preparing ? styles.preparing : null, mini ? styles.mini : null, className]
    .filter(Boolean)
    .join(' ')
  return (
    <svg
      className={classes}
      viewBox="0 0 160 110"
      focusable="false"
      aria-hidden="true"
      style={{ color: preparing ? mutedStageColor(stageNumber) : stageColor(stageNumber) }}
      data-landscape={stageNumber}
    >
      {drawing}
    </svg>
  )
}

/* 그리기 부품. 선(L), 옅은 면(A), 더 옅은 면(A2), 빛줄기(B), 왼쪽 위 하이라이트(H)다. */
const L = (d: string, key?: string) => <path key={key ?? d} className={styles.line} d={d} vectorEffect="non-scaling-stroke" />
const H = (d: string) => <path key={`h-${d}`} className={styles.highlight} d={d} vectorEffect="non-scaling-stroke" />

function wave(x: number, y: number, first: number, halves: number): ReactNode {
  return L(`M${x} ${y} q6 ${first} 12 0${' t12 0'.repeat(halves - 1)}`)
}

function fullDrawing(n: number): ReactNode {
  const area = styles.area
  const area2 = styles.area2
  const vs = 'non-scaling-stroke'
  switch (n) {
    case 1:
      return (
        <>
          {L('M12 100 H148')}
          {L('M50 42 C50 12 110 12 110 42')}
          <rect className={area2} x="30" y="42" width="100" height="56" rx="6" vectorEffect={vs} />
          {L('M63.3 42 V98 M96.6 42 V98 M30 70 H130')}
          <circle className={area} cx="46.6" cy="57" r="6.5" vectorEffect={vs} />
          <rect className={area} x="74.5" y="51" width="11" height="12" rx="2" vectorEffect={vs} />
          <circle className={area} cx="108" cy="59" r="4.6" vectorEffect={vs} />
          <circle className={area} cx="119.5" cy="60" r="3.2" vectorEffect={vs} />
          <rect className={area} x="40" y="77" width="13" height="13" rx="2.5" vectorEffect={vs} />
          <circle className={area} cx="80" cy="84" r="7" vectorEffect={vs} />
          <rect className={area} x="102.5" y="80" width="10" height="10" rx="2" vectorEffect={vs} />
          <rect className={area} x="115.5" y="80" width="10" height="10" rx="2" vectorEffect={vs} />
          {H('M55.5 37 C57 28 66 23.5 80 23.5 S 91 25 94 27.5')}
        </>
      )
    case 2:
      // 배와 잔물결: 같은 위상, 세로 간격 7, 길이 120·96·72, 가운데 정렬
      return (
        <>
          <circle className={area} cx="32" cy="28" r="9" vectorEffect={vs} />
          {L('M80 20 V70')}
          <path className={area} d="M83 24 L83 66 L111 66 Z" vectorEffect={vs} />
          <path className={area2} d="M77 31 L77 66 L55 66 Z" vectorEffect={vs} />
          <path className={area} d="M47 70 H115 L105 83 H57 Z" vectorEffect={vs} />
          {wave(20, 90, -5, 10)}
          {wave(32, 97, 5, 8)}
          {wave(44, 104, -5, 6)}
          {H('M53 73.5 H88')}
        </>
      )
    case 3:
      return (
        <>
          <path className={area} d="M8 48 H50 C57 48 60 51 63 55 C60 59 57 62 50 62 H8 Z" vectorEffect={vs} />
          {L('M63 55 C80 55 86 26 110 26')}
          {L('M63 55 C82 55 92 57 114 57')}
          {L('M63 55 C80 55 88 88 110 88')}
          <circle className={area} cx="121" cy="26" r="10" vectorEffect={vs} />
          <circle className={area} cx="131" cy="57" r="16" vectorEffect={vs} />
          <circle className={area} cx="117" cy="88" r="6.5" vectorEffect={vs} />
          <ellipse className={area2} cx="30" cy="76" rx="8" ry="3.6" vectorEffect={vs} />
          <ellipse className={area2} cx="40" cy="34" rx="6" ry="3" vectorEffect={vs} />
          {H('M119.7 52.9 A12 12 0 0 1 137 46.6')}
        </>
      )
    case 4:
      return (
        <>
          <path className={styles.beam} d="M77 37 L16 18 L16 56 Z" />
          <path className={`${styles.line} ${styles.faint}`} d="M77 37 L16 18 M77 37 L16 56" vectorEffect={vs} />
          <path className={area} d="M70 100 L75 46 H93 L98 100 Z" vectorEffect={vs} />
          {L('M73.5 62 H94.5 M71.9 80 H96.1')}
          <rect className={area2} x="76" y="32" width="16" height="14" rx="2" vectorEffect={vs} />
          <path className={area} d="M72 32 L84 20 L96 32 Z" vectorEffect={vs} />
          {L('M81 100 V92 a3 3 0 0 1 6 0 V100')}
          {L('M44 100 Q57 90 70 100 M98 100 Q113 88 130 100')}
          {L('M8 104 H152')}
          {H('M76.5 30 L84 22.5')}
        </>
      )
    case 5:
      return (
        <>
          {L('M70 106 C70 96 50 94 18 98 M70 106 C70 96 92 94 142 98')}
          {L('M70 102 V28')}
          {L('M62 102 H78')}
          <path className={area} d="M70 34 H44 L37 41 L44 48 H70 Z" vectorEffect={vs} />
          <path className={area2} d="M70 56 H96 L103 63 L96 70 H70 Z" vectorEffect={vs} />
          <circle className={area} cx="122" cy="36" r="13" vectorEffect={vs} />
          <path className={`${styles.line} ${styles.thick}`} d="M131.5 45.5 L143 57" vectorEffect={vs} />
          {H('M112.6 34.4 A9.5 9.5 0 0 1 125.2 27.1')}
        </>
      )
    case 6:
      return (
        <>
          <rect className={area2} x="20" y="14" width="120" height="80" rx="8" vectorEffect={vs} />
          <rect className={area2} x="29" y="34" width="102" height="40" rx="5" vectorEffect={vs} />
          {L('M12 100 H148 M16 94 H144')}
          <circle className={area} cx="46" cy="54" r="8" vectorEffect={vs} />
          {L('M46 39.5 V42.5 M46 65.5 V68.5 M31.5 54 H34.5 M57.5 54 H60.5 M36 44 L38 46 M56 44 L54 46')}
          <path className={area} d="M68 62 H95 a7 7 0 0 0 0 -14 a10 10 0 0 0 -19 -3 a7.5 7.5 0 0 0 -8 17 Z" vectorEffect={vs} />
          {L('M110 44 l-2 6 M120 42 l-2 6 M115 56 l-2 6 M125 54 l-2 6')}
          {H('M26 30 Q26 20 36 20 H72')}
        </>
      )
    default:
      return null
  }
}

/** 작은 자리용: 같은 풍경에서 큰 도형만 남긴 판이다. */
function miniDrawing(n: number): ReactNode {
  const area = styles.area
  const area2 = styles.area2
  const vs = 'non-scaling-stroke'
  switch (n) {
    case 1:
      return (
        <>
          {L('M48 40 C48 8 112 8 112 40')}
          <rect className={area2} x="24" y="40" width="112" height="60" rx="8" vectorEffect={vs} />
          {L('M61 40 V100 M99 40 V100 M24 70 H136')}
          <circle className={area} cx="42" cy="55" r="8" vectorEffect={vs} />
          <rect className={area} x="70" y="76" width="20" height="16" rx="3" vectorEffect={vs} />
        </>
      )
    case 2:
      return (
        <>
          <circle className={area} cx="30" cy="26" r="11" vectorEffect={vs} />
          {L('M80 12 V70')}
          <path className={area} d="M84 16 L84 66 L118 66 Z" vectorEffect={vs} />
          <path className={area2} d="M76 26 L76 66 L50 66 Z" vectorEffect={vs} />
          <path className={area} d="M40 72 H122 L110 88 H52 Z" vectorEffect={vs} />
          {L('M24 101 q14 -9 28 0 t28 0 t28 0 t28 0')}
        </>
      )
    case 3:
      return (
        <>
          <path className={area} d="M6 46 H48 L62 55 L48 64 H6 Z" vectorEffect={vs} />
          {L('M62 55 C82 55 86 22 108 22 M62 55 H106 M62 55 C82 55 86 88 108 88')}
          <circle className={area} cx="122" cy="22" r="13" vectorEffect={vs} />
          <circle className={area} cx="128" cy="55" r="20" vectorEffect={vs} />
          <circle className={area} cx="120" cy="88" r="10" vectorEffect={vs} />
        </>
      )
    case 4:
      return (
        <>
          <path className={styles.beam} d="M78 36 L14 14 L14 58 Z" />
          <path className={area} d="M66 102 L72 44 H96 L102 102 Z" vectorEffect={vs} />
          <path className={area} d="M68 28 L84 12 L100 28 Z" vectorEffect={vs} />
          <rect className={area2} x="74" y="28" width="20" height="16" rx="2" vectorEffect={vs} />
          {L('M8 103 H152')}
        </>
      )
    case 5:
      return (
        <>
          {L('M64 104 V20')}
          <path className={area} d="M64 26 H30 L20 36 L30 46 H64 Z" vectorEffect={vs} />
          <path className={area2} d="M64 56 H98 L108 66 L98 76 H64 Z" vectorEffect={vs} />
          <circle className={area} cx="124" cy="38" r="17" vectorEffect={vs} />
          {L('M136 51 L150 66')}
          {L('M30 104 H110')}
        </>
      )
    case 6:
      return (
        <>
          <rect className={area2} x="16" y="10" width="128" height="88" rx="10" vectorEffect={vs} />
          <circle className={area} cx="50" cy="52" r="13" vectorEffect={vs} />
          <path className={area} d="M72 66 H104 a9 9 0 0 0 0 -18 a13 13 0 0 0 -24 -4 a10 10 0 0 0 -8 22 Z" vectorEffect={vs} />
          {L('M8 104 H152')}
        </>
      )
    default:
      return null
  }
}

import { roundCoord } from './svgCoord'

/**
 * 배지 일곱 갈래의 메달 실루엣 경로(013 design-notes §3.3 표).
 *
 * <p>비로그인 견본 그림(`GuestSample`)과 '나의 학습'의 메달·표장(B13 T193·T194)이 같은 모양을 쓰도록 한곳에
 * 둔다. 좌표는 0~100 정사각형이고, `inset`은 윤곽에서 안쪽으로 줄이는 거리다. 받은 메달의 에나멜 면처럼 안쪽
 * 면을 그릴 때 양수를, 진행 호처럼 바깥으로 넓힐 때 음수를 준다. 목업 `design-mockup.html`의 `bshape`를 옮겼다.
 */

/**
 * 갈래 이름. 레벨은 둥근 팔각 표장, 읽은 글은 책갈피 방패, 첫 답 정답은 육각, 모두 맞힘은 로제트, 단계 완주는
 * 아치형 창, 익힌 용어는 모서리 접힌 낱말 카드, 특별은 마름모다.
 */
export type MedalKind = 'level' | 'read' | 'first' | 'all' | 'stage' | 'terms' | 'special'

type Point = readonly [number, number]

function point(p: Point): string {
  return `${roundCoord(p[0])} ${roundCoord(p[1])}`
}

function lerp(a: Point, b: Point, t: number): Point {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]
}

/** 모서리를 둥글린 정다각형. `rotation`은 첫 꼭짓점의 각도(도)다. */
function roundedPolygon(sides: number, radius: number, rotation: number, cornerRadius: number): string {
  const vertices: Point[] = []
  for (let i = 0; i < sides; i += 1) {
    const angle = ((rotation + (360 * i) / sides) * Math.PI) / 180
    vertices.push([50 + radius * Math.cos(angle), 50 + radius * Math.sin(angle)])
  }
  const side = Math.hypot(vertices[1][0] - vertices[0][0], vertices[1][1] - vertices[0][1])
  const t = Math.min(cornerRadius / side, 0.45)
  const before = (i: number) => lerp(vertices[i], vertices[(i - 1 + sides) % sides], t)
  const after = (i: number) => lerp(vertices[i], vertices[(i + 1) % sides], t)

  if (Math.abs(rotation + 90) < 0.01) {
    // 꼭짓점이 맨 위에 오는 모양(육각·마름모)은 그 꼭짓점의 둥근 모서리 가운데서 시작한다.
    const start = lerp(lerp(before(0), vertices[0], 0.5), lerp(vertices[0], after(0), 0.5), 0.5)
    let d = `M${point(start)}Q${point(lerp(vertices[0], after(0), 0.5))} ${point(after(0))}`
    for (let i = 1; i < sides; i += 1) {
      d += `L${point(before(i))}Q${point(vertices[i])} ${point(after(i))}`
    }
    return `${d}L${point(before(0))}Q${point(lerp(before(0), vertices[0], 0.5))} ${point(start)}Z`
  }
  const start = lerp(vertices[0], vertices[1], 0.5)
  let d = `M${point(start)}`
  for (let k = 1; k <= sides; k += 1) {
    const i = k % sides
    d += `L${point(before(i))}Q${point(vertices[i])} ${point(after(i))}`
  }
  return `${d}L${point(start)}Z`
}

/** 열여섯 잎 로제트. */
function rosette(inset: number): string {
  const petals = 16
  const radius = 40 - inset
  const points: Point[] = []
  for (let i = 0; i < petals; i += 1) {
    const angle = ((-90 - 180 / petals + (360 / petals) * i) * Math.PI) / 180
    points.push([50 + radius * Math.cos(angle), 50 + radius * Math.sin(angle)])
  }
  const chord = 2 * radius * Math.sin(Math.PI / petals)
  const arc = roundCoord((chord / 2) * 1.001)
  let d = `M${point(points[0])}`
  for (let i = 1; i <= petals; i += 1) d += `A${arc} ${arc} 0 0 1 ${point(points[i % petals])}`
  return `${d}Z`
}

/** 아래가 V로 갈라진 책갈피 방패. */
function bookmark(inset: number): string {
  const x0 = roundCoord(17 + inset)
  const x1 = roundCoord(83 - inset)
  const y0 = roundCoord(7 + inset)
  const bottom = roundCoord(93 - inset)
  const notch = roundCoord(76.5 - inset * 1.25)
  const r = roundCoord(Math.max(9 - inset * 0.6, 2))
  return `M50 ${y0}H${roundCoord(x1 - r)}Q${x1} ${y0} ${x1} ${roundCoord(y0 + r)}V${bottom}L50 ${notch}L${x0} ${bottom}V${roundCoord(y0 + r)}Q${x0} ${y0} ${roundCoord(x0 + r)} ${y0}Z`
}

/** 고딕 뾰족 아치 창. 안쪽 면(`inset > 0`)은 창 몸통만, 바깥 윤곽은 창턱까지 그린다. */
function arch(inset: number): string {
  const x0 = roundCoord(18 + inset)
  const x1 = roundCoord(82 - inset)
  const ya = 46
  const yt = roundCoord(6 + inset * 1.35)
  const hw = 50 - x0
  const h = ya - yt
  const left = `C${x0} ${roundCoord(ya - 0.49 * h)} ${roundCoord(50 - 0.54 * hw)} ${roundCoord(yt + 0.18 * h)} 50 ${yt}`
  const right = `C${roundCoord(50 + 0.54 * hw)} ${roundCoord(yt + 0.18 * h)} ${x1} ${roundCoord(ya - 0.49 * h)} ${x1} ${ya}`
  if (inset > 0) {
    const yb = roundCoord(88 - inset)
    return `M${x0} ${yb}V${ya}${left}${right}V${yb}Z`
  }
  const sill = roundCoord(88 + inset * 0.5)
  const sx0 = roundCoord(10 + inset)
  const sx1 = roundCoord(90 - inset)
  const sb = roundCoord(95 - inset * 0.8)
  const q = 1.6
  return `M${x0} ${sill}V${ya}${left}${right}V${sill}H${roundCoord(sx1 - q)}Q${sx1} ${sill} ${sx1} ${roundCoord(sill + q)}V${roundCoord(sb - q)}Q${sx1} ${sb} ${roundCoord(sx1 - q)} ${sb}H${roundCoord(sx0 + q)}Q${sx0} ${sb} ${sx0} ${roundCoord(sb - q)}V${roundCoord(sill + q)}Q${sx0} ${sill} ${roundCoord(sx0 + q)} ${sill}Z`
}

/**
 * 받은 메달의 은록 금속 테두리 두께(그림판 단위). 화면에서 96px 이상은 5px, 72px 이상은 4px, 48px 이상은 3px, 그보다
 * 작으면 2px이다. 단계 완주 메달과 갈래 메달이 같다. 레벨 표장은 테두리가 더 두꺼워 표장이 따로 정한다.
 */
export function medalRim(size: number): number {
  const pixels = size >= 96 ? 5 : size >= 72 ? 4 : size >= 48 ? 3 : 2
  return pixels * (100 / size)
}

/** 모서리 접힌 낱말 카드의 치수. 접힌 모서리를 따로 그릴 때도 쓴다. */
export function termCardGeometry(inset: number) {
  return {
    x0: roundCoord(15 + inset),
    x1: roundCoord(85 - inset),
    y0: roundCoord(11 + inset),
    y1: roundCoord(89 - inset),
    fold: roundCoord(20 - inset * 0.41),
    r: roundCoord(Math.max(7 - inset * 0.5, 2)),
  }
}

function termCard(inset: number): string {
  const g = termCardGeometry(inset)
  return `M50 ${g.y0}H${roundCoord(g.x1 - g.fold)}L${g.x1} ${roundCoord(g.y0 + g.fold)}V${roundCoord(g.y1 - g.r)}Q${g.x1} ${g.y1} ${roundCoord(g.x1 - g.r)} ${g.y1}H${roundCoord(g.x0 + g.r)}Q${g.x0} ${g.y1} ${g.x0} ${roundCoord(g.y1 - g.r)}V${roundCoord(g.y0 + g.r)}Q${g.x0} ${g.y0} ${roundCoord(g.x0 + g.r)} ${g.y0}Z`
}

/** 갈래의 실루엣 경로. */
export function medalPath(kind: MedalKind, inset = 0): string {
  switch (kind) {
    case 'level':
      return roundedPolygon(8, 45 - inset / Math.cos(Math.PI / 8), -112.5, Math.max(9 - inset * 0.4, 2.5))
    case 'first':
      return roundedPolygon(6, 46 - inset / Math.cos(Math.PI / 6), -90, Math.max(10 - inset * 0.45, 3))
    case 'special':
      return roundedPolygon(4, 47 - inset / Math.cos(Math.PI / 4), -90, Math.max(10 - inset * 0.45, 3))
    case 'all':
      return rosette(inset)
    case 'read':
      return bookmark(inset)
    case 'stage':
      return arch(inset)
    case 'terms':
      return termCard(inset)
  }
}

/**
 * 단계 완주 아치의 진행 호 경로. 창턱 가로선을 빼고 왼쪽 옆면 → 꼭대기 → 오른쪽 옆면만 따라간다. 받침을 돌아
 * 나오는 갈고리가 생기지 않게 하려는 것이다.
 */
export function stageArchProgressPath(inset: number): string {
  const x0 = roundCoord(18 + inset)
  const x1 = roundCoord(82 - inset)
  const ya = 46
  const yt = roundCoord(6 + inset * 1.35)
  const hw = 50 - x0
  const h = ya - yt
  const sill = roundCoord(88 + inset * 0.5)
  return `M${x0} ${sill}V${ya}C${x0} ${roundCoord(ya - 0.49 * h)} ${roundCoord(50 - 0.54 * hw)} ${roundCoord(yt + 0.18 * h)} 50 ${yt}C${roundCoord(50 + 0.54 * hw)} ${roundCoord(yt + 0.18 * h)} ${x1} ${roundCoord(ya - 0.49 * h)} ${x1} ${ya}V${sill}`
}

/** 단계 완주 아치의 납선(번호 원이 없는 모양). 스테인드글라스 조각을 나누는 선이다. */
export const STAGE_LEAD_PATH = 'M50 6V28M50 54V88M18 50L37.5 44.5M62.5 44.5L82 50M18 66L50 74L82 66'

/**
 * 단계 완주 아치 가운데 번호 원의 글자 크기와 반지름(viewBox 단위). 번호가 화면에서 12px 아래로 내려가지 않게 아치가
 * 작을수록 원과 글자를 키운다(목업 `archNum`). 화면 36px 미만이면 번호를 그리지 않는다.
 */
export function stageNumberCircle(size: number): { fontSize: number; radius: number } {
  const fontSize = roundCoord(Math.max(17, 1200 / size))
  return { fontSize, radius: roundCoord((13 * fontSize) / 17) }
}

/**
 * 단계 완주 아치의 납선. 가운데 번호 원(반지름 `radius`, 0이면 원 없음) 둘레에서 끊긴다(목업 `leadPath`). 가로
 * 범위 `x0`~`x1`, 세로 범위 `y0`~`y1`을 받아 색유리 조각을 나누는 선과 바깥 납선에 함께 쓴다.
 */
export function stageLeadPath(radius: number, x0 = 18, x1 = 82, y0 = 6, y1 = 88): string {
  const toCircle = (sx: number) => {
    const dx = sx - 50
    const dy = 9
    const length = Math.hypot(dx, dy)
    return point([50 + (radius * dx) / length, 41 + (radius * dy) / length])
  }
  const vertical = radius > 0 ? `M50 ${y0}V${roundCoord(41 - radius)}M50 ${roundCoord(41 + radius)}V${y1}` : `M50 ${y0}V${y1}`
  return `${vertical}M${x0} 50L${toCircle(x0)}M${toCircle(x1)}L${x1} 50M${x0} 66L50 74L${x1} 66`
}

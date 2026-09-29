import { localToday } from './listingWindow'
import type { SectorSummary } from '@/types/sector'

/**
 * 고른 투자 대상의 상장 구간을 한 축 위에 겹쳐 놓기 위한 계산이다.
 *
 * 선택이 하나여도 그 대상의 가용 기간을 보여 주고, 둘 이상이면 가장 늦은 상장일부터 시작하는
 * 공통 구간을 보여 준다. `describeListingWindow`는 "상장일 차이 때문에 기간이 줄었다"는 안내가
 * 필요한지만 판정하므로 하나이거나 상장일이 모두 같을 때는 조용하다. 타임라인은 기간 자체를
 * 답하므로 그 안내 판정과 표시 조건을 공유하지 않는다.
 *
 * **서버를 부르지 않는다.** 재료는 `GET /sectors`가 이미 준 `representativeEtf.listedOn`뿐이다.
 * 백엔드의 `GET /sectors/evaluation-window`는 전략 워밍업 거래일 수를 입력으로 받으므로 전략을
 * 고르기 전인 이 화면에서는 부를 자리가 아니다(listingWindow.ts와 같은 까닭).
 */

/** 축 오른쪽 끝은 오늘이다. 자료 마감일은 응답에 없으므로 없는 날짜를 지어내지 않는다(원칙 VI). */
const MILLIS_PER_DAY = 86_400_000

/** `GET /sectors`가 보장하는 상장일 모양이다. 깨진 응답으로 날짜를 지어내지 않는다. */
const ISO_DATE_SHAPE = /^\d{4}-\d{2}-\d{2}$/

/** 상세에서 보여 줄 한 투자 대상의 상장 정보다. */
export type TimelineTrack = {
  code: string
  name: string
  /** `YYYY-MM-DD` */
  listedOn: string
}

/**
 * 타임라인이 화면에 놓일 상태다. 카드는 어느 상태에서도 자리를 지키고, `empty`이면 막대 대신 한 줄로 까닭을 알린다.
 *
 * `empty`의 까닭은 둘이다. `noSelection`은 아직 아무것도 고르지 않은 경우다. `noWindow`는 상장일을 읽을
 * 수 없거나 가장 늦은 상장일이 아직 오지 않아 그릴 구간이 없는 경우다.
 *
 * **마지막 경우에 막대를 그리지 않는 까닭**은 축 오른쪽 끝이 오늘이기 때문이다. 오늘 이후에
 * 시작하는 막대는 축 밖에 놓여 폭이 0인 선으로만 보이는데, 그 그림이 알려 주는 사실은 이미
 * `ListingWindowNotice`가 한 문장으로 말하고 있다. 없는 자리를 억지로 그리면 사용자는 폭 0인
 * 막대를 "자료가 거의 없다"로 읽는다.
 */
export type AvailabilityTimeline =
  | { status: 'empty'; reason: 'noSelection' | 'noWindow' }
  | {
      status: 'shown'
      /** 축 왼쪽 끝. 고른 대상 가운데 가장 이른 상장일이다. */
      axisStart: string
      /** 축 오른쪽 끝. 오늘이다. */
      axisEnd: string
      /** 표시 순서를 그대로 지킨 막대 목록이다. */
      tracks: TimelineTrack[]
      /** 선택한 대상 가운데 가장 늦은 상장일이다. */
      commonStart: string
      commonStartRatio: number
      /** 공통 시작일을 정한 대상의 이름이다. */
      bindingSectorName: string
      /** 공통 구간의 길이를 사람이 읽을 문장으로 다듬은 것이다. */
      commonSpanLabel: string
    }

/** `YYYY-MM-DD`를 UTC 자정의 밀리초로 읽는다. 시간대를 끼워 넣지 않아야 하루가 어긋나지 않는다. */
function toEpoch(date: string): number {
  const [year, month, day] = date.split('-').map(Number)
  return Date.UTC(year, month - 1, day)
}

/**
 * 두 날짜 사이를 해와 달로 나눈다. 달의 길이가 고르지 않으므로 일수를 30으로 나누지 않는다.
 *
 * 끝날의 일자가 시작날의 일자보다 이르면 마지막 달이 아직 차지 않은 것이므로 한 달을 뺀다.
 */
function monthsBetween(from: string, to: string): number {
  const [fromYear, fromMonth, fromDay] = from.split('-').map(Number)
  const [toYear, toMonth, toDay] = to.split('-').map(Number)
  const months = (toYear - fromYear) * 12 + (toMonth - fromMonth) - (toDay < fromDay ? 1 : 0)
  return months < 0 ? 0 : months
}

/**
 * 길이를 "19년 3개월"처럼 적는다.
 *
 * 0인 자리는 적지 않는다. "0년 8개월"은 8개월을 읽는 데 방해만 되고, "19년 0개월"은 19년이라고
 * 쓰면 그만이다. 한 달도 차지 않았으면 남은 일수를 그대로 적는다 — 그 경우 "0개월"이라고 쓰면
 * 자료가 없다는 뜻으로 읽힌다.
 */
export function formatSpan(from: string, to: string): string {
  const months = monthsBetween(from, to)
  if (months === 0) {
    const days = Math.max(0, Math.round((toEpoch(to) - toEpoch(from)) / MILLIS_PER_DAY))
    return `${days}일`
  }

  const years = Math.floor(months / 12)
  const restMonths = months % 12
  if (years === 0) return `${restMonths}개월`
  if (restMonths === 0) return `${years}년`
  return `${years}년 ${restMonths}개월`
}

/**
 * 고른 대상들의 공통 구간을 한 축 위의 자리로 옮긴다.
 *
 * `selectedSectors`는 **고른 것만** 담고 표시 순서대로다. 목록 전체를 넘기면 고르지 않은 대상의
 * 상장일까지 축에 들어와 사실과 다른 그림이 된다.
 *
 * `today`를 인자로 받는 까닭은 검사가 시계에 매이지 않게 하기 위해서다.
 */
export function buildAvailabilityTimeline(
  selectedSectors: readonly SectorSummary[],
  today: string = localToday(),
): AvailabilityTimeline {
  if (selectedSectors.length === 0) return { status: 'empty', reason: 'noSelection' }

  let earliest = selectedSectors[0]
  let latest = selectedSectors[0]
  for (const sector of selectedSectors) {
    const listedOn = sector.representativeEtf.listedOn
    if (!ISO_DATE_SHAPE.test(listedOn)) return { status: 'empty', reason: 'noWindow' }
    if (listedOn < earliest.representativeEtf.listedOn) earliest = sector
    if (listedOn > latest.representativeEtf.listedOn) latest = sector
  }

  const commonStart = latest.representativeEtf.listedOn
  if (commonStart > today) return { status: 'empty', reason: 'noWindow' }

  const axisStart = earliest.representativeEtf.listedOn
  const axisEnd = today
  const span = toEpoch(axisEnd) - toEpoch(axisStart)
  if (span < 0) return { status: 'empty', reason: 'noWindow' }

  const tracks = selectedSectors.map((sector) => ({
    code: sector.code,
    name: sector.name,
    listedOn: sector.representativeEtf.listedOn,
  }))

  return {
    status: 'shown',
    axisStart,
    axisEnd,
    tracks,
    commonStart,
    // 오늘 상장한 한 대상처럼 축의 길이가 0이면 시작점을 왼쪽 끝에 둔다.
    commonStartRatio: span === 0 ? 0 : (toEpoch(commonStart) - toEpoch(axisStart)) / span,
    bindingSectorName: latest.name,
    commonSpanLabel: formatSpan(commonStart, axisEnd),
  }
}

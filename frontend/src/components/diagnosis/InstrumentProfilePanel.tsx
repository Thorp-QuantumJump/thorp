import { useEffect, useState } from 'react'
import { fetchInstrumentProfile, type InstrumentProfileResult } from '@/api/sectors'
import { NotReadyNotice } from '@/components/common/NotReadyNotice'
import type { InstrumentHolding, InstrumentProfile } from '@/types/sector'

/**
 * 대표 ETF가 어떤 상품인지 **같은 화면 그 자리에서** 펼쳐 본다(FR-010).
 *
 * 상세를 보려고 다른 화면으로 이동시키지 않는다. 섹터를 고르는 도중에 화면을 떠나면 고르던
 * 맥락이 끊기고, 돌아왔을 때 어디까지 봤는지 다시 찾아야 한다(원칙 II).
 *
 * **이 패널의 주인공은 보유 구성이다.** 사용자가 여기서 답을 얻으려는 질문은 "이 섹터를 고르면
 * 실제로 무엇을 사는가" 하나다. 운용사·기준 지수 같은 사실은 그 답을 뒷받침하는 자리로 두고,
 * 상위 보유 종목이 전체에서 차지하는 몫을 먼저 눈에 보이게 한다. 이름과 숫자를 줄지어 놓기만
 * 하면 "삼성전자 하나가 4분의 1"이라는 사실이 읽히지 않는다.
 *
 * 값을 가공하지 않는다. 출처 표시도 목록 화면의 상수가 아니라 **응답이 준 값**을 쓴다. 여기서는
 * 계약이 `sourceAttribution`을 주기 때문이다.
 *
 * 화면 문구는 이 작업이 확정하지 않는다. 문장이 놓일 자리와 나타나는 조건까지만 만들었고 문구는
 * UI 결정 항목이 정해질 때 채운다.
 */

/**
 * 얼마나 보일 것인가.
 *
 * - `summary`: 상품을 가려내는 데 필요한 것만 보인다. 설명 한 문단과 기준 지수·운용사·상장일이다
 * - `full`: 보유 구성과 거래대금, 자료의 한계까지 모두 보인다
 *
 * 두 상태가 **같은 조회 결과를 쓴다.** 펼쳤다 접을 때마다 다시 불러오면 사용자는 방금 읽던
 * 자리를 잃고, 서버는 같은 값을 거듭 내주게 된다.
 */
export type InstrumentProfileVariant = 'summary' | 'full'

type InstrumentProfilePanelProps = {
  /** 어느 섹터의 대표 상품을 보는가. */
  sectorCode: string
  /** 패널을 가리키는 id다. 펼침 버튼이 `aria-controls`로 이 값을 쓴다. */
  id: string
  /** 기본값은 `full`이다. 예전부터 이 패널을 쓰던 자리의 동작을 그대로 둔다. */
  variant?: InstrumentProfileVariant
  className?: string
}

/**
 * 구성 종목을 처음에 몇 줄까지 보일 것인가.
 *
 * 스무 줄이 한꺼번에 펼쳐지면 그 아래에 있는 사실이 화면 밖으로 밀린다. 나머지는 사용자가
 * 원할 때 `전체 보기`로 연다 — 감추는 것이 아니라 뒤로 미루는 것이다.
 */
const VISIBLE_HOLDING_COUNT = 5

/**
 * 비중 문자열을 0과 1 사이 숫자로 옮긴다. 읽을 수 없으면 0으로 본다.
 *
 * 막대 너비와 표시 비중이 **같은 값**을 쓰도록 여기 한 번만 옮긴다. 둘이 따로 계산하면 눈에
 * 보이는 크기와 적힌 숫자가 갈라진다.
 */
function toFraction(weight: string): number {
  const value = Number(weight)
  if (!Number.isFinite(value) || value < 0) return 0
  return Math.min(value, 1)
}

/**
 * 거래대금을 사람이 읽는 단위로 줄여 보인다.
 *
 * `357400000000`처럼 0이 열 개 넘게 붙은 숫자는 자릿수를 세어야 크기를 알 수 있다. 억과 조로
 * 줄이고 천 단위를 끊어 준다.
 *
 * **떨어지는 값에 `.0`을 붙이지 않는다.** `3574.0억`은 소수 한 자리까지 잰 값처럼 보이지만
 * 실제로는 그냥 3574억이다. 없는 정밀도를 흉내 내지 않는다.
 */
function formatWon(amount: string): string | null {
  const value = Number(amount)
  if (!Number.isFinite(value)) return null

  const trillion = 1_000_000_000_000
  const hundredMillion = 100_000_000

  const scaled = (unit: number, suffix: string) => {
    // 소수 한 자리까지 남기되 떨어지면 그 자리를 아예 쓰지 않는다. 천 단위는 끊어 준다.
    const text = (value / unit).toLocaleString('ko-KR', { maximumFractionDigits: 1 })
    return `${text}${suffix} 원`
  }

  if (Math.abs(value) >= trillion) return scaled(trillion, '조')
  if (Math.abs(value) >= hundredMillion) return scaled(hundredMillion, '억')
  return `${Math.round(value).toLocaleString('ko-KR')}원`
}

/**
 * ISO 시각을 한국 날짜로 옮긴다. 시각까지 보일 만큼 정밀한 자료가 아니다.
 *
 * **앞 열 글자를 자르지 않는다.** 그렇게 하면 UTC 날짜가 나온다. 지금은 수집이 한국 시각
 * 15:30에 찍혀 두 날짜가 같지만, 마감이 09:00 이전으로 바뀌면 사용자에게 하루 앞선 날짜를
 * 보이게 된다. 이 저장소의 다른 화면도 시간대를 명시해 옮긴다.
 */
function toDateText(isoInstant: string): string {
  const at = new Date(isoInstant)
  if (Number.isNaN(at.getTime())) return isoInstant

  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
    .format(at)
    .replace(/\.\s*$/, '')
    .replace(/\.\s*/g, '-')
}

/**
 * 실패한 까닭을 화면 문장으로 옮긴다.
 *
 * 화면 문구는 이 작업이 확정하지 않는다. 갈래와 나타나는 조건까지가 이 함수의 몫이다.
 */
function failureMessage(result: Extract<InstrumentProfileResult, { status: 'failed' }>): string {
  if (result.retryable) return '대표 상품 정보를 불러오지 못했습니다.'
  if (result.code === 'SECTOR_NOT_FOUND') return '이 섹터의 대표 상품 정보를 찾을 수 없습니다.'

  // 서버에 저장된 자료를 고쳐야 풀린다. 사용자가 다시 눌러서 될 일이 아니다.
  return '서버에 저장된 상품 자료에 문제가 있어 지금은 보여드릴 수 없습니다.'
}

type CompositionSegment = {
  key: string
  label: string
  /** 0과 1 사이다. 막대 너비와 표시 비중이 같은 값을 쓴다. */
  fraction: number
  /** 상위 종목이 아니라 "그 외"인가. */
  isRest: boolean
}

/**
 * 상위 보유 종목과 나머지를 한 줄로 잇는다.
 *
 * **나머지를 빼놓지 않는다.** 상위 몇 개만 보이면 사용자는 그것이 전부라고 읽는다. 합이 1을
 * 넘는 자료가 오면 나머지를 만들지 않는다. 없는 여유를 지어내는 것보다 그 자리를 비우는 편이 낫다.
 */
function buildComposition(holdings: readonly InstrumentHolding[]): CompositionSegment[] {
  const segments: CompositionSegment[] = holdings.map((holding, index) => ({
    key: `${holding.name}-${index}`,
    label: holding.name,
    fraction: toFraction(holding.weight),
    isRest: false,
  }))

  const covered = segments.reduce((sum, segment) => sum + segment.fraction, 0)
  const rest = 1 - covered
  if (rest > 0.0001) {
    segments.push({ key: '__rest__', label: '그 외', fraction: rest, isRest: true })
  }

  return segments
}

/** 펼친 뒤 실제로 보이는 내용이다. */
function ProfileBody({
  profile,
  variant,
}: {
  profile: InstrumentProfile
  variant: InstrumentProfileVariant
}) {
  const composition = buildComposition(profile.holdingsOverlap.topHoldings)
  const detailed = variant === 'full'

  /*
    구성 종목을 끝까지 펼쳤는가. 상세로 바꿀 때마다 접힌 자리에서 다시 시작한다 — 앞서 펼쳐
    두었다가 간단히 보기로 돌아간 사용자가, 다시 상세를 열었을 때 스무 줄을 마주하지 않게 한다.
  */
  const [allHoldingsShown, setAllHoldingsShown] = useState(false)
  const hiddenHoldingCount = Math.max(0, composition.length - VISIBLE_HOLDING_COUNT)
  const shownComposition =
    allHoldingsShown || hiddenHoldingCount === 0
      ? composition
      : composition.slice(0, VISIBLE_HOLDING_COUNT)

  return (
    <div data-slot="profile-body">
      {/*
        상품 이름을 여기서 되풀이하지 않는다. 카드 머리가 이미 보이고 있고, 아래 방법론 문단이
        기준 지수를 다시 설명한다.

        **이름 뒤에 조사를 붙이는 문장을 두지 않는다.** 받침이 있는지에 따라 은/는과 이/가가
        갈리는데, 상품 이름은 서버가 주는 값이라 어느 쪽이 맞는지 미리 알 수 없다. 한쪽으로
        고정하면 절반은 틀린 문장이 된다. 사실은 아래 목록으로 옮겨 조사 없이 보인다.
      */}

      {/*
        구성 막대와 아래 목록은 **같은 값**을 쓴다. 막대는 크기를 한눈에 보이고 목록은 정확한
        숫자를 준다. 막대만 두면 몇 퍼센트인지 알 수 없고, 목록만 두면 한 종목이 얼마나 큰지가
        읽히지 않는다. 막대는 목록이 이미 읽어 주는 값이라 보조 기술에서 숨긴다.
      */}
      {detailed && composition.length > 0 ? (
        <>
          <span data-slot="composition-bar" aria-hidden="true">
            {composition.map((segment) => (
              <span
                key={segment.key}
                data-slot="composition-segment"
                data-rest={segment.isRest}
                style={{ width: `${segment.fraction * 100}%` }}
              />
            ))}
          </span>

          <ul data-slot="holdings" role="list">
            {shownComposition.map((segment) => (
              <li key={segment.key} data-slot="holding" data-rest={segment.isRest}>
                <span data-slot="holding-name">{segment.label}</span>
                <span data-slot="holding-weight">{(segment.fraction * 100).toFixed(2)}%</span>
              </li>
            ))}
          </ul>

          {/*
            남은 종목은 숨긴 것이 아니라 뒤로 미룬 것이다. 몇 개가 더 있는지 버튼이 먼저 말한다.
          */}
          {hiddenHoldingCount > 0 && !allHoldingsShown ? (
            <button
              type="button"
              data-slot="holdings-more"
              onClick={() => setAllHoldingsShown(true)}
            >
              전체 보기 ({hiddenHoldingCount}개 더)
            </button>
          ) : null}
        </>
      ) : null}

      {/*
        구성이 지금 시점의 것이면 과거를 이 구성으로 설명하지 않는다. 이 문장이 없으면 사용자는
        과거 성과가 이 종목들로 만들어졌다고 읽는다(원칙 V).
      */}
      {detailed && profile.holdingsOverlap.asOfCurrentComposition ? (
        <p data-slot="composition-caveat">
          지금 시점의 보유 구성입니다. 과거 어느 날의 구성을 되살린 것이 아니므로, 과거 수익을 이
          종목들로 설명할 수 없습니다.
        </p>
      ) : null}

      {/*
        무엇을 담는 상품인지가 먼저다. 사실 표는 그 설명을 뒷받침하는 자리라 아래에 둔다.
      */}
      <p data-slot="profile-methodology">{profile.indexMethodologySummary}</p>

      <dl data-slot="profile-facts">
        <div>
          <dt>기준 지수</dt>
          <dd>{profile.benchmarkIndex}</dd>
        </div>
        <div>
          <dt>운용사</dt>
          <dd>{profile.manager}</dd>
        </div>
        <div>
          <dt>상장일</dt>
          <dd>{profile.listedOn}</dd>
        </div>
        {detailed ? (
          <div>
            <dt>3개월 일평균 거래대금</dt>
            <dd>{formatWon(profile.avgDailyValue3m) ?? '확인하지 못했습니다'}</dd>
          </div>
        ) : null}
      </dl>

      {/*
        이 상품 정보를 만든 **자료 버전**이 예시라는 뜻이다. 섹터 목록의 예시 표시(대응표 기준)와
        다른 것을 가리키므로 한 문장으로 합치지 않는다.
      */}
      {profile.isSample ? (
        <p data-slot="profile-sample">
          이 상품 정보는 아직 확정되지 않은 예시 자료로 채워져 있습니다.
        </p>
      ) : null}

      {detailed ? (
        <p data-slot="profile-survivorship">{profile.survivorshipNote}</p>
      ) : null}

      {/*
        자료 식별자는 화면에 날것으로 두지 않는다(FR-011). 사용자가 알아야 하는 것은 "언제까지의
        자료인가"이지 그 자료에 붙은 이름이 아니다.
      */}
      {detailed ? (
        <p data-slot="profile-source">
          출처: {profile.sourceAttribution} · {toDateText(profile.dataDeadline)}까지의 자료
        </p>
      ) : null}
    </div>
  )
}

export function InstrumentProfilePanel({
  sectorCode,
  id,
  variant = 'full',
  className,
}: InstrumentProfilePanelProps) {
  // null은 아직 결과가 없는 상태, 곧 불러오는 중이다. 목록 화면이 쓰는 표현과 같다.
  const [result, setResult] = useState<InstrumentProfileResult | null>(null)
  const [reloadCount, setReloadCount] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    let isCurrent = true

    setResult(null)
    void fetchInstrumentProfile(sectorCode, controller.signal).then((next) => {
      if (isCurrent) setResult(next)
    })

    return () => {
      isCurrent = false
      controller.abort()
    }
  }, [sectorCode, reloadCount])

  const reload = () => setReloadCount((count) => count + 1)

  return (
    <div className={className} id={id} data-slot="profile-panel" data-variant={variant}>
      {result === null ? (
        <p data-slot="profile-loading" role="status">
          대표 상품 정보를 불러오는 중입니다.
        </p>
      ) : null}

      {/*
        자료가 아직 없는 상태다. **빈 패널을 보이지 않는다.** 빈 자리는 사용자에게 고장으로
        읽힌다. 파이프라인이 자료를 채우면 달라지는 상태라 다시 불러오기를 준다.
      */}
      {result?.status === 'notReady' ? (
        <NotReadyNotice
          message="대표 상품 정보가 아직 준비 중입니다."
          action={{ label: '다시 불러오기', onAction: reload }}
        />
      ) : null}

      {/*
        실패는 세 갈래다. 다시 눌러 달라질 수 있으면 행동을 주고, 달라지지 않는 두 경우는 행동
        없이 문장만 둔다(research.md 결정 6).

        **없는 섹터와 망가진 자료를 같은 문장으로 알리지 않는다.** 둘 다 다시 눌러도 달라지지
        않지만 사용자가 해야 할 일이 다르다. 목록에 멀쩡히 보이는 섹터를 펼쳤는데 "찾을 수
        없습니다"라고 하면, 실제로는 서버 자료가 망가진 것인데 사용자는 자기가 잘못 눌렀다고
        읽는다. 이 엔드포인트는 저장된 대응표가 불변식을 어길 때 500을 낸다.
      */}
      {result?.status === 'failed' ? (
        <NotReadyNotice
          message={failureMessage(result)}
          action={result.retryable ? { label: '다시 불러오기', onAction: reload } : undefined}
        />
      ) : null}

      {result?.status === 'loaded' ? (
        <ProfileBody profile={result.profile} variant={variant} />
      ) : null}
    </div>
  )
}

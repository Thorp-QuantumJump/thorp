import { useEffect, useId, useRef } from 'react'
import { Check, Maximize2, Minimize2, Plus } from 'lucide-react'
import { NotReadyNotice } from '@/components/common/NotReadyNotice'
import { PriceTrend } from '@/components/diagnosis/PriceTrend'
import {
  buildComposition,
  formatWon,
  profileFailureMessage,
  toDateText,
} from '@/diagnosis/instrumentFormat'
import { useInstrumentPriceSeries } from '@/diagnosis/useInstrumentPriceSeries'
import { useInstrumentProfile } from '@/diagnosis/useInstrumentProfile'
import type { InstrumentProfile, SectorSummary } from '@/types/sector'

type InstrumentBrowserProps = {
  /** 이미 표시 순서로 정렬된 목록이다. */
  sectors: readonly SectorSummary[]
  /** 지금 보고 있는 대상이다. */
  viewing: SectorSummary
  /** 카드를 넓혀 담긴 종목과 자세한 설명까지 보고 있는가. */
  expanded: boolean
  isSelected: (code: string) => boolean
  onView: (code: string) => void
  onToggleSelected: (code: string, selected: boolean) => void
  onExpandedChange: (expanded: boolean) => void
  className?: string
}

/**
 * 투자 대상을 훑고 하나를 들여다보는 카드다. 목록과 상세가 **한 카드 안에** 있다.
 *
 * <b>카드는 두 크기를 오간다.</b> 접었을 때는 가격 흐름만 보여 여러 대상을 빠르게 견주게 하고(그
 * 선은 접은 채로도 부른다),
 * 오른쪽 위 단추로 넓히면 그 자리에서 담긴 종목과 자세한 설명까지 드러낸다. 넓히고 접는 동안
 * 보던 대상과 담아 둔 것은 그대로 남는다.
 *
 * <b>정보를 아래로 쌓지 않는다.</b> 넓힌 카드는 왼쪽에 목록, 가운데에 가격 흐름과 설명,
 * 오른쪽에 담긴 종목을 나란히 둔다. 같은 것을 세로로 이어 붙이면 아래쪽 사실이 화면 밖으로
 * 밀려, 넓혔는데도 스크롤로 찾아야 하는 일이 생긴다.
 *
 * <b>누르는 것과 담는 것을 가른다.</b> 왼쪽 목록을 누르면 보는 대상만 바뀌고, 백테스트 대상에
 * 담는 일은 가운데 머리의 단추 하나가 맡는다.
 */
export function InstrumentBrowser({
  sectors,
  viewing,
  expanded,
  isSelected,
  onView,
  onToggleSelected,
  onExpandedChange,
  className,
}: InstrumentBrowserProps) {
  const headingId = useId()
  const bodyId = useId()
  const listRef = useRef<HTMLUListElement>(null)

  /*
    상품 정보는 카드를 넓혔을 때만 부른다. 접힌 카드가 쓰는 사실(이름·기초지수·상장일)은 이미
    받아 둔 목록에 있으므로, 목록을 훑기만 하는 사용자는 이 왕복을 치르지 않는다.
  */
  const { result, reload } = useInstrumentProfile(viewing.code, expanded)

  /*
    가격 흐름은 접은 카드에서도 부른다. 접은 카드가 보여 주는 것이 바로 그 선이라, 펼칠 때까지
    미루면 가장 먼저 보이는 자리가 비어 있게 된다.
  */
  const priceSeries = useInstrumentPriceSeries(viewing.code)
  const selected = isSelected(viewing.code)

  /*
    지금 보고 있는 대상을 카드에 표시할 때는 목록 항목과 다른 이름(`data-viewing-code`)을 쓴다.
    같은 이름을 쓰면 "그 코드의 항목"을 찾는 쪽이 항목 대신 카드 전체를 먼저 집는다.
  */

  /*
    보고 있는 줄을 목록 안으로 끌어온다. 목록이 길어 잘리는 자리라, 화면을 다시 열거나 다른
    경로로 들어와 보는 대상이 정해지면 그 줄이 스크롤 밖에 있을 수 있다. 오른쪽에 그 상품이
    펼쳐져 있는데 왼쪽 목록에서는 보이지 않으면 사용자는 자기가 무엇을 누른 것인지 잃는다.

    `block: 'nearest'`라 이미 보이는 줄은 움직이지 않는다 — 목록을 눌러 옮길 때마다 화면이
    덜컹이지 않게 하려는 것이다.
  */
  useEffect(() => {
    const current = listRef.current?.querySelector('[aria-current="true"]')
    // 이 자리를 모든 환경이 구현하지는 않는다. 없으면 목록이 제자리에 있을 뿐이라 그냥 넘어간다.
    current?.scrollIntoView?.({ block: 'nearest' })
  }, [viewing.code])

  return (
    <section
      className={className}
      data-expanded={expanded}
      data-viewing-code={viewing.code}
      aria-labelledby={headingId}
    >
      {/*
        카드의 크기를 바꾸는 단추다. 아이콘 하나뿐이라 보조 기술이 읽을 이름을 직접 준다.
        `aria-expanded`가 지금 어느 크기인지, `aria-controls`가 무엇이 넓어지는지 알린다.
      */}
      <button
        type="button"
        data-slot="zoom"
        aria-label={expanded ? '간단히 보기' : '상세 보기'}
        aria-expanded={expanded}
        aria-controls={bodyId}
        onClick={() => onExpandedChange(!expanded)}
      >
        {expanded ? (
          <Minimize2 size={17} strokeWidth={1.8} aria-hidden="true" />
        ) : (
          <Maximize2 size={17} strokeWidth={1.8} aria-hidden="true" />
        )}
      </button>

      <div id={bodyId} data-slot="body">
        {/*
          `list-style: none`이 걸리면 Safari와 VoiceOver 조합에서 `<ul>`의 목록 역할이 사라진다.
          이 화면은 목록 의미에 기대고 있으므로 역할을 명시한다.
        */}
        <nav data-slot="list" aria-label="투자 대상 목록">
          <ul ref={listRef} role="list">
            {sectors.map((sector) => {
              const picked = isSelected(sector.code)
              return (
                <li key={sector.code} data-sector-code={sector.code} data-selected={picked}>
                  <button
                    type="button"
                    data-slot="pill"
                    aria-current={sector.code === viewing.code ? 'true' : undefined}
                    onClick={() => onView(sector.code)}
                  >
                    <span data-slot="pill-name">{sector.representativeEtf.name}</span>
                    {/*
                      담아 둔 대상이라는 표시다. 색만으로 알리지 않는다 — 형태로도 보이도록
                      체크 표시를 두고, 소리로만 듣는 사용자를 위해 글자를 함께 싣는다.
                    */}
                    {picked ? (
                      <span data-slot="pill-check">
                        <Check size={13} strokeWidth={2.4} aria-hidden="true" />
                        <span data-slot="sr-only">선택됨</span>
                      </span>
                    ) : null}
                  </button>
                </li>
              )
            })}
          </ul>
        </nav>

        <div data-slot="main">
          <header data-slot="main-header">
            <div data-slot="main-title">
              <h3 id={headingId} data-slot="detail-name">
                {viewing.representativeEtf.name}
              </h3>
              {/*
                접은 카드에서도 이 둘은 보인다. 목록 응답이 이미 주는 값이라 따로 부르지 않으며,
                무엇을 따라가는 상품인지가 이름 다음으로 먼저 필요한 사실이다.
              */}
              <p data-slot="main-subtitle">
                <span data-slot="main-fact">{viewing.name}</span>
                <span data-slot="main-fact">
                  {viewing.representativeEtf.benchmarkIndex}
                </span>
                <span data-slot="main-fact">
                  {viewing.representativeEtf.listedOn} 상장
                </span>
              </p>
            </div>

            {/*
              담고 빼는 자리다. 담긴 뒤에도 같은 자리에 남아 '선택됨'을 말하고, 한 번 더 누르면 뺀다.
              빼는 길은 오른쪽 선택 목록의 X가 따로 있어 '빼기'를 글자로 되풀이하지 않는다.
            */}
            <button
              type="button"
              data-slot="pick"
              data-selected={selected}
              aria-pressed={selected}
              onClick={() => onToggleSelected(viewing.code, !selected)}
            >
              {selected ? (
                <>
                  <Check size={15} strokeWidth={2.2} aria-hidden="true" />
                  <span>선택됨</span>
                </>
              ) : (
                <>
                  <Plus size={15} strokeWidth={2.2} aria-hidden="true" />
                  <span>투자 대상에 추가</span>
                </>
              )}
            </button>
          </header>

          <PriceTrend
            name={viewing.representativeEtf.name}
            result={priceSeries.result}
            onReload={priceSeries.reload}
            expanded={expanded}
          />

          {/*
            넓혔을 때만 나타나는 설명이다. 접힌 카드에 이 문단을 함께 두면, 빠르게 견주려고
            접어 둔 사용자가 다시 긴 글을 지나야 한다.
          */}
          {expanded ? <ProfileNarrative result={result} onReload={reload} /> : null}
        </div>

        {expanded ? <HoldingsColumn result={result} /> : null}
      </div>
    </section>
  )
}

/**
 * 넓힌 카드의 설명이다. 사실은 **가로로 늘어놓고** 문단은 그 아래 한 덩이로 둔다.
 *
 * 항목을 세로로 쌓으면 넓힌 카드의 남는 폭을 버리면서 정작 아래 사실은 화면 밖으로 밀려난다.
 */
function ProfileNarrative({
  result,
  onReload,
}: {
  result: ReturnType<typeof useInstrumentProfile>['result']
  onReload: () => void
}) {
  if (result === null) {
    return (
      <p data-slot="profile-loading" role="status">
        대표 상품 정보를 불러오는 중입니다.
      </p>
    )
  }

  /*
    자료가 아직 없는 상태다. **빈 자리를 보이지 않는다.** 빈 자리는 사용자에게 고장으로 읽힌다.
    파이프라인이 자료를 채우면 달라지는 상태라 다시 불러오기를 준다.
  */
  if (result.status === 'notReady') {
    return (
      <div data-slot="profile-notice">
        <NotReadyNotice
          message="대표 상품 정보가 아직 준비 중입니다."
          action={{ label: '다시 불러오기', onAction: onReload }}
        />
      </div>
    )
  }

  if (result.status === 'failed') {
    return (
      <div data-slot="profile-notice">
        <NotReadyNotice
          message={profileFailureMessage(result)}
          action={result.retryable ? { label: '다시 불러오기', onAction: onReload } : undefined}
        />
      </div>
    )
  }

  return <ProfileFacts profile={result.profile} />
}

function ProfileFacts({ profile }: { profile: InstrumentProfile }) {
  return (
    <div data-slot="profile-body">
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
        <div>
          <dt>3개월 일평균 거래대금</dt>
          <dd>{formatWon(profile.avgDailyValue3m) ?? '확인하지 못했습니다'}</dd>
        </div>
        <div>
          <dt>자료 시점</dt>
          <dd>{toDateText(profile.dataDeadline)}까지</dd>
        </div>
      </dl>

      <p data-slot="profile-methodology">{profile.indexMethodologySummary}</p>

      {/*
        이 상품 정보를 만든 **자료 버전**이 예시라는 뜻이다. 목록의 예시 표시(대응표 기준)와
        다른 것을 가리키므로 한 문장으로 합치지 않는다.
      */}
      {profile.isSample ? (
        <p data-slot="profile-sample">
          이 상품 정보는 아직 확정되지 않은 예시 자료로 채워져 있습니다.
        </p>
      ) : null}

      <p data-slot="profile-survivorship">{profile.survivorshipNote}</p>

      {/*
        자료 식별자는 화면에 날것으로 두지 않는다(FR-011). 사용자가 알아야 하는 것은 "언제까지의
        자료인가"이지 그 자료에 붙은 이름이 아니다.
      */}
      <p data-slot="profile-source">출처: {profile.sourceAttribution}</p>
    </div>
  )
}

/**
 * 담긴 종목이다. 넓힌 카드의 오른쪽 칸을 차지한다.
 *
 * 사용자가 여기서 답을 얻으려는 질문은 "이 대상을 고르면 실제로 무엇을 사는가" 하나다. 그래서
 * 이름과 비중을 한 줄씩 두되, 상위 종목이 전체에서 차지하는 몫을 막대로 먼저 보인다.
 */
function HoldingsColumn({
  result,
}: {
  result: ReturnType<typeof useInstrumentProfile>['result']
}) {
  const profile = result?.status === 'loaded' ? result.profile : null
  const composition = profile ? buildComposition(profile.holdingsOverlap.topHoldings) : []

  return (
    <aside data-slot="holdings" aria-labelledby="instrument-holdings-title">
      <h4 id="instrument-holdings-title" data-slot="holdings-title">
        포함된 종목
      </h4>

      {profile === null ? (
        <p data-slot="holdings-empty">
          {result === null ? '불러오는 중입니다.' : '담긴 종목을 보여드릴 수 없습니다.'}
        </p>
      ) : (
        <>
          {/*
            막대와 목록은 **같은 값**을 쓴다. 막대는 크기를 한눈에 보이고 목록은 정확한 숫자를
            준다. 막대는 목록이 이미 읽어 주는 값이라 보조 기술에서 숨긴다.
          */}
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

          <ul data-slot="holdings-list" role="list">
            {composition.map((segment) => (
              <li key={segment.key} data-slot="holding" data-rest={segment.isRest}>
                <span data-slot="holding-name">{segment.label}</span>
                <span data-slot="holding-weight">{(segment.fraction * 100).toFixed(2)}%</span>
              </li>
            ))}
          </ul>

          {/*
            구성이 지금 시점의 것이면 과거를 이 구성으로 설명하지 않는다. 이 문장이 없으면
            사용자는 과거 성과가 이 종목들로 만들어졌다고 읽는다(원칙 V).
          */}
          {profile.holdingsOverlap.asOfCurrentComposition ? (
            <p data-slot="composition-caveat">
              지금 시점의 보유 구성입니다. 과거 어느 날의 구성을 되살린 것이 아니므로, 과거 수익을
              이 종목들로 설명할 수 없습니다.
            </p>
          ) : null}
        </>
      )}
    </aside>
  )
}

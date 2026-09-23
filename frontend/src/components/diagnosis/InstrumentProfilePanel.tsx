import { NotReadyNotice } from '@/components/common/NotReadyNotice'
import {
  buildComposition,
  formatWon,
  profileFailureMessage,
  toDateText,
} from '@/diagnosis/instrumentFormat'
import { useInstrumentProfile } from '@/diagnosis/useInstrumentProfile'
import type { InstrumentProfile } from '@/types/sector'

/**
 * 대표 ETF가 어떤 상품인지 **펼친 그 자리에서** 보인다(FR-010).
 *
 * 결과 화면(005)이 후보 섹터마다 이 패널을 접어 두고 연 것만 그린다. 상세를 보려고 다른 화면으로
 * 이동시키지 않는다 — 결과를 읽던 맥락이 끊기면 어디까지 봤는지 다시 찾아야 한다(원칙 II).
 *
 * **투자 대상을 고르는 화면은 이 패널을 쓰지 않는다.** 그 화면은 같은 응답을 설명·사실·담긴
 * 종목 세 칸에 나눠 그리므로, 한 덩이로 그리는 이 패널과 배치가 다르다. 두 화면이 공유하는 것은
 * 조회(`useInstrumentProfile`)와 표시 규칙(`instrumentFormat`)이며 그것으로 충분하다.
 *
 * **이 패널의 주인공은 보유 구성이다.** 사용자가 여기서 답을 얻으려는 질문은 "이 섹터를 고르면
 * 실제로 무엇을 사는가" 하나다. 운용사·기준 지수 같은 사실은 그 답을 뒷받침하는 자리로 둔다.
 *
 * 값을 가공하지 않는다. 출처 표시도 목록 화면의 상수가 아니라 **응답이 준 값**을 쓴다.
 */

type InstrumentProfilePanelProps = {
  /** 어느 섹터의 대표 상품을 보는가. */
  sectorCode: string
  /** 패널을 가리키는 id다. 펼침 버튼이 `aria-controls`로 이 값을 쓴다. */
  id: string
  className?: string
}

/** 펼친 뒤 실제로 보이는 내용이다. */
function ProfileBody({ profile }: { profile: InstrumentProfile }) {
  const composition = buildComposition(profile.holdingsOverlap.topHoldings)

  return (
    <div data-slot="profile-body">
      {/*
        구성 막대와 아래 목록은 **같은 값**을 쓴다. 막대는 크기를 한눈에 보이고 목록은 정확한
        숫자를 준다. 막대만 두면 몇 퍼센트인지 알 수 없고, 목록만 두면 한 종목이 얼마나 큰지가
        읽히지 않는다. 막대는 목록이 이미 읽어 주는 값이라 보조 기술에서 숨긴다.
      */}
      {composition.length > 0 ? (
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
            {composition.map((segment) => (
              <li key={segment.key} data-slot="holding" data-rest={segment.isRest}>
                <span data-slot="holding-name">{segment.label}</span>
                <span data-slot="holding-weight">{(segment.fraction * 100).toFixed(2)}%</span>
              </li>
            ))}
          </ul>
        </>
      ) : null}

      {/*
        구성이 지금 시점의 것이면 과거를 이 구성으로 설명하지 않는다. 이 문장이 없으면 사용자는
        과거 성과가 이 종목들로 만들어졌다고 읽는다(원칙 V).
      */}
      {profile.holdingsOverlap.asOfCurrentComposition ? (
        <p data-slot="composition-caveat">
          지금 시점의 보유 구성입니다. 과거 어느 날의 구성을 되살린 것이 아니므로, 과거 수익을 이
          종목들로 설명할 수 없습니다.
        </p>
      ) : null}

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
      </dl>

      {/*
        상품 이름 뒤에 조사를 붙이는 문장을 두지 않는다. 받침이 있는지에 따라 은/는과 이/가가
        갈리는데, 상품 이름은 서버가 주는 값이라 어느 쪽이 맞는지 미리 알 수 없다.
      */}
      <p data-slot="profile-methodology">{profile.indexMethodologySummary}</p>

      {/*
        이 상품 정보를 만든 **자료 버전**이 예시라는 뜻이다. 섹터 목록의 예시 표시(대응표 기준)와
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
      <p data-slot="profile-source">
        출처: {profile.sourceAttribution} · {toDateText(profile.dataDeadline)}까지의 자료
      </p>
    </div>
  )
}

export function InstrumentProfilePanel({
  sectorCode,
  id,
  className,
}: InstrumentProfilePanelProps) {
  const { result, reload } = useInstrumentProfile(sectorCode)

  return (
    <div className={className} id={id} data-slot="profile-panel">
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
      */}
      {result?.status === 'failed' ? (
        <NotReadyNotice
          message={profileFailureMessage(result)}
          action={result.retryable ? { label: '다시 불러오기', onAction: reload } : undefined}
        />
      ) : null}

      {result?.status === 'loaded' ? <ProfileBody profile={result.profile} /> : null}
    </div>
  )
}

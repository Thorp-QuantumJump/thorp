import type { ReactNode } from 'react'
import { useSectorCatalogState } from '@/hooks/useSectorCatalogState'
import type { PublicResultDetail } from '@/types/result'
import { AnalysisPanel, type DetailCompareTarget, SettingsPanel, TradesPanel } from './DetailPanel'
import type { DrawdownWindow } from './drawdownWindow'
import { isZeroValue } from './formatResult'
import { IndexComparisonView } from './IndexComparisonView'
import { LOSS_WINDOW_NONE, LOSS_WINDOW_UNAVAILABLE, LossWindowView } from './LossWindowView'
import { ResultTabs } from './ResultTabs'

/**
 * 결과 화면의 여섯 탭(T098): 요약 | 결과 분석 | 설정·근거 | 전체 거래 내역 | 손실이 커진 때 | KOSPI 비교.
 *
 * <p>변동성 제한(004 목표 위험 맞춤)은 탭이 아니라 분석 조건과 '비교 대상 고르기'의 한 줄로 옮겼다 — 같은 전략의 켬·끔을
 * 그래프에 겹쳐 보는 것이 이 보기의 목적이라서다.
 *
 * <p>첫 화면에서 요약이 열려 있다. 요약의 내용(핵심 숫자·상태 문장)은 페이지가 만들어 `summary`로 넘긴다 —
 * 비교 대상 같은 페이지 상태에 묶여 있어서다. 그래프는 탭 위에, 해석 문장은 요약 탭에 있다. 섹터 목록은 여기서 한 번 받아 상세 탭 셋에
 * 넘긴다 — 탭마다 받으면 같은 목록을 세 번 받는다. 볼 결과가 없는 실행(계산 중·실패)에는 이 탭 묶음을 두지 않고
 * 페이지가 요약 내용만 그린다.
 *
 * <p>'손실이 커진 때'는 그래프 음영과 같은 구간(`lossWindow`)이 있어야 열린다. 없으면 왜 없는지를 탭 줄 아래에
 * 적는다 — 하락이 없던 결과와 구간을 확인하지 못한 결과는 다른 문장이다(FR-010, R-05).
 */
export function ResultDetailTabs({
  token,
  detail,
  summary: summaryContent,
  lossWindow,
  compare = null,
}: {
  token: string
  detail: PublicResultDetail
  /** 요약 탭의 내용. 핵심 숫자·상태 문장 */
  summary: ReactNode
  lossWindow: DrawdownWindow | null
  compare?: DetailCompareTarget | null
}) {
  const catalog = useSectorCatalogState()
  const { summary } = detail
  const lossReason = lossWindow
    ? undefined
    : summary && isZeroValue(summary.maxDrawdown)
      ? LOSS_WINDOW_NONE
      : LOSS_WINDOW_UNAVAILABLE

  return (
    <ResultTabs
      label="결과 보기"
      defaultTab="summary"
      tabs={[
        { id: 'summary', label: '요약', content: summaryContent },
        {
          id: 'analysis',
          label: '결과 분석',
          content: <AnalysisPanel token={token} detail={detail} catalog={catalog} />,
        },
        {
          id: 'settings',
          label: '설정·근거',
          content: (
            <SettingsPanel token={token} detail={detail} catalog={catalog} compare={compare} />
          ),
        },
        {
          id: 'trades',
          label: '전체 거래 내역',
          content: <TradesPanel token={token} detail={detail} catalog={catalog} />,
        },
        {
          id: 'loss',
          label: '손실이 커진 때',
          disabled: lossWindow === null,
          reason: lossReason,
          content: lossWindow ? (
            <LossWindowView token={token} lossWindow={lossWindow} items={detail.config.items} />
          ) : null,
        },
        { id: 'index', label: 'KOSPI 비교', content: <IndexComparisonView token={token} /> },
      ]}
    />
  )
}

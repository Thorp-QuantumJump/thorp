import { useSectorCatalogState } from '@/hooks/useSectorCatalogState'
import type { PublicResultDetail } from '@/types/result'
import { AnalysisPanel, type DetailCompareTarget, SettingsPanel, TradesPanel } from './DetailPanel'
import type { DrawdownWindow } from './drawdownWindow'
import { isZeroValue } from './formatResult'
import { IndexComparisonView } from './IndexComparisonView'
import { LOSS_WINDOW_NONE, LOSS_WINDOW_UNAVAILABLE, LossWindowView } from './LossWindowView'
import { ResultTabs } from './ResultTabs'
import { TargetRiskView } from './TargetRiskView'

/**
 * 결과 화면의 여섯 탭(T098): 결과 분석 | 설정·근거 | 전체 거래 내역 | 손실이 커진 때 | KOSPI 비교 | 목표 위험 맞춤.
 *
 * <p>첫 화면에서 결과 분석이 열려 있다. 핵심 숫자·그래프·해석 문장·상태 문장은 탭 위 페이지 몫이다. 섹터 목록은
 * 여기서 한 번 받아 상세 탭 셋에 넘긴다 — 탭마다 받으면 같은 목록을 세 번 받는다. 볼 결과가 없는 실행(계산 중·
 * 실패)에는 이 탭 묶음을 두지 않는다.
 *
 * <p>'손실이 커진 때'는 그래프 음영과 같은 구간(`lossWindow`)이 있어야 열린다. 없으면 왜 없는지를 탭 줄 아래에
 * 적는다 — 하락이 없던 결과와 구간을 확인하지 못한 결과는 다른 문장이다(FR-010, R-05).
 */
export function ResultDetailTabs({
  token,
  detail,
  lossWindow,
  compare = null,
}: {
  token: string
  detail: PublicResultDetail
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
      defaultTab="analysis"
      tabs={[
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
        { id: 'targetRisk', label: '목표 위험 맞춤', content: <TargetRiskView base={detail} /> },
      ]}
    />
  )
}

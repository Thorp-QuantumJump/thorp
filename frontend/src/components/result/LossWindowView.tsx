import { useCallback, useId } from 'react'
import { fetchResultPositions } from '@/api/result'
import { useResource } from '@/hooks/useResource'
import { useSectorCatalogState } from '@/hooks/useSectorCatalogState'
import type { ResultConfigItem } from '@/types/result'
import type { DrawdownWindow } from './drawdownWindow'
import { formatMoney, formatRatio } from './formatResult'
import { ResourceArea } from './ResourceArea'
import { buildSectorLabels } from './sectorLabels'
import { TradeList } from './TradeHistory'
import { InlineHelp } from '@/components/common/InlineHelp'
import styles from './resultDetail.module.css'

/**
 * '손실이 커진 때' 탭(FR-010, T066·T098).
 *
 * <p>그래프 음영과 **같은** 최대 하락 구간 하나(`findDrawdownWindow`)를 보인다. 여러 구간을 고르는 임계값은
 * 두지 않는다. 그 구간에 진입하거나 청산한 거래(`/trades?from=&to=`), 저점일의 보유(`/positions?date=`), 거래별
 * 비용을 보인다. 구간 손익이나 비용 합계는 004가 주는 값이 없어 만들지 않는다(원칙 VI).
 *
 * <p>구간이 없으면 이 탭은 열리지 않고 그 사유는 탭 줄에 있다(`ResultDetailTabs`). 아래 두 문장이 그 사유다.
 */

export const LOSS_WINDOW_UNAVAILABLE = '최대 하락 구간을 확인할 수 없어 이 보기를 열 수 없습니다.'
/** 하락이 없던 결과. 구간을 확인하지 못한 것과 다르므로 문장을 나눈다 */
export const LOSS_WINDOW_NONE = '분석 기간 동안 자산가치가 줄어든 적이 없어 볼 구간이 없습니다.'

type LossWindowViewProps = {
  token: string
  lossWindow: DrawdownWindow
  /** 결과의 섹터 후보. 종목 식별자에 섹터 이름을 붙이는 데 쓴다 */
  items: readonly ResultConfigItem[]
}

export function LossWindowView({ token, lossWindow, items }: LossWindowViewProps) {
  const titleId = useId()

  const load = useCallback(
    (signal: AbortSignal) => fetchResultPositions(token, lossWindow.troughDate, signal),
    [token, lossWindow.troughDate],
  )
  const positions = useResource(load)
  const catalog = useSectorCatalogState()
  const labels = buildSectorLabels(items, catalog.status === 'loaded' ? catalog.catalog : null)

  return (
    <section className={styles.stack} aria-labelledby={titleId}>
      <div className={styles.panelHeader}>
        <h2 id={titleId} className={styles.panelTitle}>
          손실이 커진 때: {lossWindow.peakDate} ~ {lossWindow.troughDate}
        </h2>
        <InlineHelp label="손실이 커진 때">
          <p>자산가치가 가장 높았던 날부터 가장 크게 줄어든 날까지의 구간입니다.</p>
          <p>그래프의 음영과 같은 구간이며, 회복한 날은 넣지 않았습니다.</p>
        </InlineHelp>
      </div>

      <div className={styles.item}>
        <h3 className={styles.itemTitle}>저점일의 보유</h3>
        <ResourceArea resource={positions} loadingText="보유 구성을 불러오는 중입니다.">
          {(data) => (
            <>
              {data.positions.length === 0 ? (
                <p className={styles.copy}>{data.snapshotDate} 기준 보유한 종목이 없습니다.</p>
              ) : (
                <div className={styles.tableScroll}>
                  <table className={styles.table}>
                    {/* 스냅샷은 보유가 바뀐 날에만 있어 요청한 날보다 앞선 날짜일 수 있다(004 §2.4). */}
                    <caption>{data.snapshotDate} 기준 보유 구성</caption>
                    <thead>
                      <tr>
                        <th scope="col">종목</th>
                        <th scope="col">수량</th>
                        <th scope="col">평가 금액</th>
                        <th scope="col">비중</th>
                        <th scope="col">평가 손익</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.positions.map((position) => (
                        <tr key={position.instrumentId}>
                          <td>{labels.instrument(position.instrumentId)}</td>
                          <td>{position.quantity}</td>
                          <td>{formatMoney(position.marketValue)}</td>
                          <td>{formatRatio(position.weight)}</td>
                          <td>{formatMoney(position.unrealizedPnl)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              {data.partial ? (
                <p className={styles.muted}>완료 범위까지의 기록입니다. 그 뒤는 계산되지 않았습니다.</p>
              ) : null}
            </>
          )}
        </ResourceArea>
      </div>

      <div className={styles.item}>
        <div className={styles.itemHead}>
          <h3 className={styles.itemTitle}>이 구간에 사고판 거래와 비용</h3>
          <InlineHelp label="이 구간에 사고판 거래와 비용">
            <p>진입이나 청산이 이 구간 안에 있는 거래입니다.</p>
            <p>구간 내내 들고만 있던 종목은 위 보유 구성에 있습니다.</p>
          </InlineHelp>
        </div>
        <TradeList
          token={token}
          filter={{ from: lossWindow.peakDate, to: lossWindow.troughDate }}
          labels={labels}
          caption="구간 거래"
        />
      </div>
    </section>
  )
}

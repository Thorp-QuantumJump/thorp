import { useState } from 'react'
import type { RegimeLinkResult } from '@/types/regimeLink'
import { lossContributionNote, turnoverBasisNote } from './fixedCopy'

/**
 * 사후 구간 상세 (T061의 펼침 영역).
 *
 * 기본 화면은 63거래일이고 여기에 21거래일 보조 평가·구간 손익 기여·보유 비중 시계열·거래 목록을
 * 둔다. **사후 성과로 정렬하지 않는다** — 받은 순서를 그대로 그린다.
 */
export function PostWindowDetail({ result }: { result: RegimeLinkResult }) {
  const [open, setOpen] = useState(false)
  const window21 = result.window21
  const window63 = result.window63
  // 전체 손익이 0이면 워커가 `lossContribution`을 싣지 않는다. 없는 것과 `null`을 모두 견디고
  // 0으로 채워 그리지 않는다 — 기여가 0이라는 뜻이 아니라 나눌 손익이 없다는 뜻이다.
  const contribution = Object.entries(window63?.lossContribution ?? {})

  return (
    <div>
      <button type="button" aria-expanded={open} onClick={() => setOpen((current) => !current)}>
        {open ? '구간 상세 닫기' : '구간 상세 보기'}
      </button>
      {open && (
        <div>
          {window21 && (
            <section aria-labelledby="regime-link-window21">
              <h4 id="regime-link-window21">21거래일 보조 평가</h4>
              <dl>
                <dt>수익률</dt>
                <dd>{window21.return}</dd>
                <dt>최대 하락 폭</dt>
                <dd>{window21.maxDrawdown}</dd>
                <dt>거래 횟수</dt>
                <dd>{window21.tradeCount}</dd>
                <dt>월당 거래 횟수</dt>
                <dd>{window21.tradesPerMonth}</dd>
                <dt>회전율(연환산)</dt>
                <dd>{window21.turnover}</dd>
                <dt>거래비용 합계</dt>
                <dd>{window21.costs.total}</dd>
              </dl>
            </section>
          )}
          <p>{turnoverBasisNote}</p>
          {contribution.length > 0 && (
            <section aria-labelledby="regime-link-contribution">
              <h4 id="regime-link-contribution">구간 손익 기여</h4>
              <p>{lossContributionNote}</p>
              <ul>
                {contribution.map(([sectorId, value]) => (
                  <li key={sectorId}>{`${sectorId} ${value}`}</li>
                ))}
              </ul>
            </section>
          )}
          {/*
            비중은 평가액 ÷ 자산이라 현금 몫이 빠져 **합이 1이 아닐 수 있다.** 정규화해 100%를
            맞추면 있지도 않은 배분을 보이게 되고, 남는 몫을 현금으로 적으려 해도 그 값이 응답에
            없다. 그래서 원그래프·누적 막대를 쓰지 않고 종목별로 나열만 한다.
          */}
          <section aria-labelledby="regime-link-holdings">
            <h4 id="regime-link-holdings">보유 비중</h4>
            <ul>
              {result.holdings.map((holding) => (
                <li key={holding.date}>
                  {holding.date}
                  <ul>
                    {Object.entries(holding.actual).map(([instrumentId, weight]) => (
                      <li key={instrumentId}>{`${instrumentId} ${weight}`}</li>
                    ))}
                  </ul>
                </li>
              ))}
            </ul>
          </section>
          <section aria-labelledby="regime-link-trades">
            <h4 id="regime-link-trades">거래 목록</h4>
            <ul>
              {result.trades.map((trade, index) => (
                <li key={`${trade.date}-${trade.instrumentId}-${index}`}>
                  {`${trade.date} ${trade.instrumentId} ${trade.side} ${trade.quantity} ${trade.price} ${trade.cost}`}
                </li>
              ))}
            </ul>
          </section>
        </div>
      )}
    </div>
  )
}

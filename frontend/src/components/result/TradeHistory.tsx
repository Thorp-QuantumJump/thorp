import { type FormEvent, useCallback, useId, useState } from 'react'
import { type ResultTradeQuery, fetchResultTrades } from '@/api/result'
import { Button } from '@/components/ui/button'
import { useResource } from '@/hooks/useResource'
import type { ResultConfigItem, ResultTrade } from '@/types/result'
import { TRADES_PAGE_SIZE } from './constants'
import { formatMoney } from './formatResult'
import { ResourceArea } from './ResourceArea'
import type { SectorLabels } from './sectorLabels'
import styles from './resultDetail.module.css'
import { DateField } from '@/components/common/DateField'

/**
 * 전체 거래 내역(FR-007, T065). 상세 패널 안에서 '거래 내역 보기'를 누를 때 처음 부른다(SC-001).
 *
 * <p>한 번에 받는 건수는 `TRADES_PAGE_SIZE` 한 곳에서 정한다(R-11). 종목·기간·상태로 좁히는 것은 서버 질의로
 * 넘긴다 — 받은 페이지를 화면에서 거르면 다른 페이지에 있는 거래가 빠진 것처럼 보인다(spec Edge Case).
 */

export type TradeFilter = Pick<ResultTradeQuery, 'instrumentId' | 'status' | 'from' | 'to'>

const EMPTY = '없음'

function moneyOrEmpty(value: string | null): string {
  return value === null ? EMPTY : formatMoney(value)
}

/** 거래 표. 보유 중인 거래의 청산 값은 `null`이며 0으로 채우지 않는다(004 §2.3). */
export function TradeTable({
  trades,
  labels,
  caption,
}: {
  trades: readonly ResultTrade[]
  labels: SectorLabels
  caption: string
}) {
  return (
    <div className={styles.tableScroll}>
      <table className={styles.table}>
        <caption>{caption}</caption>
        <thead>
          <tr>
            <th scope="col">진입일</th>
            <th scope="col">청산일</th>
            <th scope="col">종목</th>
            <th scope="col">수량</th>
            <th scope="col">진입가</th>
            <th scope="col">청산가</th>
            <th scope="col">수수료</th>
            <th scope="col">슬리피지 비용</th>
            <th scope="col">실현 손익</th>
            <th scope="col">사유</th>
          </tr>
        </thead>
        <tbody>
          {trades.map((trade) => (
            <tr key={trade.tradeId}>
              <td>{trade.entryDate}</td>
              <td>{trade.exitDate ?? '보유 중'}</td>
              <td>{labels.instrument(trade.instrumentId)}</td>
              <td>{trade.quantity}</td>
              <td>{formatMoney(trade.entryPrice)}</td>
              <td>{moneyOrEmpty(trade.exitPrice)}</td>
              <td>{formatMoney(trade.commission)}</td>
              <td>{formatMoney(trade.slippageCost)}</td>
              <td>{moneyOrEmpty(trade.realizedPnl)}</td>
              <td>{trade.reason}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * 질의 하나에 대한 페이지 조회와 표.
 *
 * <p>질의가 바뀌면 `key`로 `PagedTrades`를 새로 만든다. 페이지 번호뿐 아니라 **앞 조건으로 받아 둔 표까지**
 * 버리기 위해서다. 남겨 두면 새 조건을 받는 동안 앞 조건의 거래와 건수가 새 조건의 것처럼 보인다.
 * 값으로 비교하므로 부모가 다시 그려지는 것만으로는 새로 만들지 않는다.
 */
export function TradeList({
  token,
  filter,
  labels,
  caption,
}: {
  token: string
  filter: TradeFilter
  labels: SectorLabels
  caption: string
}) {
  const { instrumentId, status, from, to } = filter
  const filterKey = [instrumentId, status, from, to].join('|')

  return (
    <PagedTrades
      key={filterKey}
      token={token}
      filter={filter}
      labels={labels}
      caption={caption}
    />
  )
}

function PagedTrades({
  token,
  filter,
  labels,
  caption,
}: {
  token: string
  filter: TradeFilter
  labels: SectorLabels
  caption: string
}) {
  const { instrumentId, status, from, to } = filter
  const [page, setPage] = useState(0)

  const load = useCallback(
    (signal: AbortSignal) =>
      fetchResultTrades(
        token,
        { page, size: TRADES_PAGE_SIZE, instrumentId, status, from, to },
        signal,
      ),
    [token, page, instrumentId, status, from, to],
  )
  const trades = useResource(load)
  const loading = trades.status === 'loading'

  return (
    <ResourceArea resource={trades} loadingText="거래 내역을 불러오는 중입니다.">
      {(data) => {
        const { content, totalElements, totalPages, size, partial } = data
        // 부분 결과는 완료 범위 뒤의 거래가 없다. 비어 있어도 "없었다"고 단정하지 않는다(004 §2.5).
        const partialNote = partial ? (
          <p className={styles.muted}>완료 범위까지의 거래입니다. 그 뒤는 계산되지 않았습니다.</p>
        ) : null

        if (content.length === 0) {
          return (
            <>
              <p className={styles.copy}>
                {partial
                  ? '완료 범위 안에서는 이 조건에 해당하는 거래가 없습니다.'
                  : '이 조건에 해당하는 거래가 없습니다.'}
              </p>
              {partialNote}
            </>
          )
        }

        const first = data.page * size + 1
        const last = data.page * size + content.length

        return (
          <>
            <TradeTable trades={content} labels={labels} caption={caption} />
            {partialNote}
            <div className={styles.row}>
              <p className={styles.muted}>
                전체 {totalElements}건 중 {first}~{last}번째
              </p>
              {/* 받는 동안에는 누른 버튼을 그대로 두고 두 번 넘기지 않는다. 비활성하면 초점을 잃는다. */}
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={data.page <= 0}
                onClick={() => {
                  if (!loading) setPage(data.page - 1)
                }}
              >
                이전
              </Button>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={data.page + 1 >= totalPages}
                onClick={() => {
                  if (!loading) setPage(data.page + 1)
                }}
              >
                다음
              </Button>
            </div>
          </>
        )
      }}
    </ResourceArea>
  )
}

const statusLabels = [
  { value: '', label: '전체' },
  { value: 'OPEN', label: '보유 중' },
  { value: 'CLOSED', label: '청산함' },
] as const

type FilterDraft = { instrumentId: string; status: string; from: string; to: string }

const emptyDraft: FilterDraft = { instrumentId: '', status: '', from: '', to: '' }

function toFilter(draft: FilterDraft): TradeFilter {
  return {
    instrumentId: draft.instrumentId || undefined,
    status: draft.status === 'OPEN' || draft.status === 'CLOSED' ? draft.status : undefined,
    from: draft.from || undefined,
    to: draft.to || undefined,
  }
}

/** 상세 패널 안의 거래 내역 자리. 열기 전에는 아무것도 부르지 않는다. */
export function TradeHistory({
  token,
  items,
  labels,
}: {
  token: string
  items: readonly ResultConfigItem[]
  labels: SectorLabels
}) {
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState<FilterDraft>(emptyDraft)
  const [applied, setApplied] = useState<TradeFilter>(() => toFilter(emptyDraft))
  const [formError, setFormError] = useState<string | null>(null)
  const listId = useId()
  const formErrorId = useId()

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    // 서버도 400으로 거절하지만, 보내기 전에 알리면 사용자가 무엇을 고칠지 바로 안다.
    if (draft.from && draft.to && draft.from > draft.to) {
      setFormError('시작일이 종료일보다 늦습니다. 기간을 다시 골라 주세요.')
      return
    }
    setFormError(null)
    setApplied(toFilter(draft))
  }

  const update = (name: keyof FilterDraft) => (value: string) =>
    setDraft((current) => ({ ...current, [name]: value }))

  return (
    <div className={styles.item}>
      <div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          aria-expanded={open}
          aria-controls={open ? listId : undefined}
          onClick={() => setOpen((current) => !current)}
        >
          {open ? '거래 내역 접기' : '거래 내역 보기'}
        </Button>
      </div>

      {open ? (
        <div id={listId} className={styles.item}>
          <form className={styles.form} onSubmit={submit} aria-label="거래 내역 좁히기">
            <label className={styles.field}>
              종목
              <select
                value={draft.instrumentId}
                onChange={(event) => update('instrumentId')(event.target.value)}
              >
                <option value="">전체</option>
                {items.map((item) => (
                  <option key={item.instrumentId} value={item.instrumentId}>
                    {labels.instrument(item.instrumentId)}
                  </option>
                ))}
              </select>
            </label>
            <label className={styles.field}>
              상태
              <select value={draft.status} onChange={(event) => update('status')(event.target.value)}>
                {statusLabels.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </label>
            <label className={styles.field}>
              시작일
              <DateField
                label="시작일"
                value={draft.from}
                max={draft.to || undefined}
                aria-describedby={formError ? formErrorId : undefined}
                onChange={update('from')}
              />
            </label>
            <label className={styles.field}>
              종료일
              <DateField
                label="종료일"
                value={draft.to}
                min={draft.from || undefined}
                aria-describedby={formError ? formErrorId : undefined}
                onChange={update('to')}
              />
            </label>
            <Button type="submit" variant="outline" size="sm">
              조건 적용
            </Button>
          </form>
          {formError ? (
            <p id={formErrorId} className={styles.alert} role="alert">
              {formError}
            </p>
          ) : null}
          <TradeList token={token} filter={applied} labels={labels} caption="거래 내역" />
        </div>
      ) : null}
    </div>
  )
}

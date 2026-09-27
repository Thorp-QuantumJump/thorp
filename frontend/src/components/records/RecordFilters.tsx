import { useId, useState } from 'react'
import type { MyRecordKind, MyRecordListQuery, MyRecordSort, MyRecordStatus } from '@/types/myRecords'
import type { SectorCatalog } from '@/types/sector'
import type { StrategyView } from '@/types/strategyRegistry'
import { KIND_LABEL, SORT_OPTIONS, STATUS_GROUPS, STATUS_LABEL } from './recordLabels'
import styles from './records.module.css'
import { DateField } from '@/components/common/DateField'

type Props = {
  query: MyRecordListQuery
  onChange: (next: MyRecordListQuery) => void
  /** 001 섹터 목록. 아직 못 받았으면 null — 코드 대신 이름이 없을 뿐 필터 자체는 둔다 */
  catalog: SectorCatalog | null
  /** 003 등록표의 전략. 못 받았으면 null */
  strategies: StrategyView[] | null
}

const KINDS: MyRecordKind[] = ['ANALYSIS', 'SEARCH_BUNDLE', 'REGIME_SEARCH']

function toggle<T>(list: T[] | undefined, value: T, on: boolean): T[] | undefined {
  const current = list ?? []
  const next = on ? [...new Set([...current, value])] : current.filter((item) => item !== value)
  return next.length === 0 ? undefined : next
}

/**
 * 목록 필터와 정렬(FR-004). 모든 조작이 라벨 있는 표준 컨트롤이라 키보드로 다룰 수 있다(FR-021).
 *
 * <p>기본으로 보이는 것은 분석 기간과 정렬뿐이다. 종류·상태·섹터·전략은 "상세 필터" details 안에 접혀 있고,
 * 들어올 때 그중 하나라도 켜져 있으면 펼친 채 시작한다. 접혀 있어도 켜진 개수는 보인다.
 *
 * <p><b>정렬 선택지에 성과가 없다.</b> 수익률·하락 폭·비용으로 늘어놓으면 순위 화면이 된다(원칙 III, R7).
 * 섹터·전략 선택지는 001·003에서 받은 것이고 코드에 상수로 두지 않는다.
 */
export function RecordFilters({ query, onChange, catalog, strategies }: Props) {
  const sortId = useId()
  const fromId = useId()
  const toId = useId()
  // 접힌 쪽(종류·상태·섹터·전략)에 켜진 것의 수. 접혀 있어도 무엇인가 걸러지고 있음이 보여야 한다.
  const detailCount =
    (query.kind?.length ?? 0) +
    (query.status?.length ?? 0) +
    (query.sector?.length ?? 0) +
    (query.strategy?.length ?? 0)
  // 들어올 때 이미 고른 것이 있으면 펼친 채 시작한다. 그 뒤로는 사용자만 여닫는다 — 값이 바뀌어도
  // 건드리지 않으므로 prop 을 처음 한 번만 정한다(React 는 바뀌지 않은 prop 을 다시 쓰지 않는다).
  const [initiallyOpen] = useState(detailCount > 0)

  return (
    <form className={styles.filters} aria-label="기록 필터" onSubmit={(event) => event.preventDefault()}>
      {/* 첫 줄은 분석 기간과 정렬, 그 아래 접이식 상세 필터다. classic에서는 묶음이 없는 것처럼 흐른다. */}
      <div className={`${styles.filterColumn} ${styles.primary}`}>
        <fieldset className={`${styles.group} ${styles.side}`}>
          <legend>분석 기간</legend>
          <span className={styles.dateBox}>
            <label htmlFor={fromId}>시작</label>
            <DateField
              id={fromId}
              label="분석 기간 시작"
              value={query.periodFrom ?? ''}
              max={query.periodTo}
              onChange={(next) => onChange({ ...query, periodFrom: next || undefined })}
            />
          </span>
          <span className={styles.dateBox}>
            <label htmlFor={toId}>끝</label>
            <DateField
              id={toId}
              label="분석 기간 끝"
              value={query.periodTo ?? ''}
              min={query.periodFrom}
              onChange={(next) => onChange({ ...query, periodTo: next || undefined })}
            />
          </span>
        </fieldset>

        <div className={`${styles.group} ${styles.side}`}>
          <label htmlFor={sortId}>정렬</label>
          <select
            id={sortId}
            value={query.sort ?? 'SAVED_AT_DESC'}
            onChange={(event) => onChange({ ...query, sort: event.target.value as MyRecordSort })}
          >
            {SORT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      <details className={styles.details} open={initiallyOpen}>
        <summary>
          상세 필터
          {detailCount > 0 ? <span className={styles.count}>{detailCount}개 적용</span> : null}
        </summary>
        <div className={styles.filterColumn}>
          <fieldset className={styles.group}>
            <legend>종류</legend>
            {KINDS.map((kind) => (
              <label key={kind} className={styles.check}>
                <input
                  type="checkbox"
                  checked={query.kind?.includes(kind) ?? false}
                  onChange={(event) => onChange({ ...query, kind: toggle(query.kind, kind, event.target.checked) })}
                />
                {KIND_LABEL[kind]}
              </label>
            ))}
          </fieldset>

          <fieldset className={styles.group}>
            <legend>
              상태
              {/* 기본 보기는 완료 항목뿐이다 — 실패·취소는 상태 필터로 본다(006 FR-004). */}
              <span className={styles.legendHint}>아무것도 고르지 않으면 완료한 항목만 보입니다.</span>
            </legend>
            {STATUS_GROUPS.map((group) => (
              <div key={group.label} className={styles.statusRow}>
                <span className={styles.rowLabel}>{group.label}</span>
                {group.statuses.map((status) => (
                  <label key={status} className={styles.check}>
                    <input
                      type="checkbox"
                      checked={query.status?.includes(status) ?? false}
                      onChange={(event) =>
                        onChange({
                          ...query,
                          status: toggle<MyRecordStatus>(query.status, status, event.target.checked),
                        })
                      }
                    />
                    {STATUS_LABEL[status]}
                  </label>
                ))}
              </div>
            ))}
          </fieldset>

          {catalog ? (
            <fieldset className={styles.group}>
              <legend>섹터</legend>
              {catalog.sectors.map((sector) => (
                <label key={sector.code} className={styles.check}>
                  <input
                    type="checkbox"
                    checked={query.sector?.includes(sector.code) ?? false}
                    onChange={(event) =>
                      onChange({ ...query, sector: toggle(query.sector, sector.code, event.target.checked) })
                    }
                  />
                  {sector.name}
                </label>
              ))}
            </fieldset>
          ) : null}

          {strategies ? (
            <fieldset className={styles.group}>
              <legend>전략</legend>
              {strategies.map((strategy) => (
                <label key={strategy.strategyId} className={styles.check}>
                  <input
                    type="checkbox"
                    checked={query.strategy?.includes(strategy.strategyId) ?? false}
                    onChange={(event) =>
                      onChange({
                        ...query,
                        strategy: toggle(query.strategy, strategy.strategyId, event.target.checked),
                      })
                    }
                  />
                  {strategy.name}
                </label>
              ))}
            </fieldset>
          ) : null}
        </div>
      </details>
    </form>
  )
}

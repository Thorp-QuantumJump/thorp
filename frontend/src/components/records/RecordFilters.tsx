import { useId } from 'react'
import type { MyRecordKind, MyRecordListQuery, MyRecordSort, MyRecordStatus } from '@/types/myRecords'
import type { SectorCatalog } from '@/types/sector'
import type { StrategyView } from '@/types/strategyRegistry'
import { KIND_LABEL, SORT_OPTIONS, STATUS_LABEL, STATUS_OPTIONS } from './recordLabels'
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
 * <p><b>정렬 선택지에 성과가 없다.</b> 수익률·하락 폭·비용으로 늘어놓으면 순위 화면이 된다(원칙 III, R7).
 * 섹터·전략 선택지는 001·003에서 받은 것이고 코드에 상수로 두지 않는다.
 */
export function RecordFilters({ query, onChange, catalog, strategies }: Props) {
  const sortId = useId()
  const fromId = useId()
  const toId = useId()

  return (
    <form className={styles.filters} aria-label="기록 필터" onSubmit={(event) => event.preventDefault()}>
      {/* 왼쪽 반은 고르기 칩, 오른쪽 반은 분석 기간과 정렬이다. classic에서는 두 묶음이 없는 것처럼 흐른다. */}
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
          <legend>상태</legend>
          {STATUS_OPTIONS.map((status) => (
            <label key={status} className={styles.check}>
              <input
                type="checkbox"
                checked={query.status?.includes(status) ?? false}
                onChange={(event) =>
                  onChange({ ...query, status: toggle<MyRecordStatus>(query.status, status, event.target.checked) })
                }
              />
              {STATUS_LABEL[status]}
            </label>
          ))}
          <p className={styles.hint}>아무것도 고르지 않으면 완료한 항목만 보입니다.</p>
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

      <div className={styles.filterColumn}>
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
    </form>
  )
}

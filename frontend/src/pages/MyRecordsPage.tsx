import { FolderClock } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { fetchStrategyRegistry } from '@/api/strategyRegistry'
import { useAuth } from '@/auth/auth-context'
import { AppHeader } from '@/components/navigation/AppHeader'
import { RecordFilters } from '@/components/records/RecordFilters'
import { type RecordLabels, RecordRow } from '@/components/records/RecordRow'
import { Button } from '@/components/ui/button'
import { useMyRecords } from '@/hooks/useMyRecords'
import { useSectorCatalogState } from '@/hooks/useSectorCatalogState'
import type { MyRecordListQuery } from '@/types/myRecords'
import type { StrategyView } from '@/types/strategyRegistry'
import styles from './MyRecordsPage.module.css'

export const EMPTY_MESSAGE = '아직 저장된 기록이 없습니다.'
export const NO_MATCH_MESSAGE = '조건에 맞는 기록이 없습니다.'

const DEFAULT_QUERY: MyRecordListQuery = {}

function isDefault(query: MyRecordListQuery): boolean {
  return Object.values(query).every((value) => value === undefined)
}

/**
 * 내 기록 목록(`/records`, spec 006 US1). `ProtectedRoute` 안에 있어 여기 왔으면 로그인 사용자다(S12).
 *
 * <p>목록은 서버의 기본 보기(완료 항목, 저장일 최신순)를 그대로 보이고 필터·정렬은 서버에 맡긴다. 화면이 정렬하거나
 * 성과로 줄 세우지 않는다(원칙 III). 빈 목록은 경고가 아니라 한 문장과 진단 시작 연결이다(FR-001).
 *
 * <p>`?resultToken=`으로 들어오면(005 결과 화면의 '기록으로 내보내기', T045a) 그 항목으로 초점을 옮기고 펼쳐 둔다.
 */
export function MyRecordsPage() {
  const { user } = useAuth()
  const [params] = useSearchParams()
  const highlightToken = params.get('resultToken')
  const [query, setQuery] = useState<MyRecordListQuery>(DEFAULT_QUERY)
  const records = useMyRecords(query)
  const catalog = useSectorCatalogState()
  const [strategies, setStrategies] = useState<StrategyView[] | null>(null)
  const highlightRef = useRef<HTMLLIElement>(null)

  useEffect(() => {
    const controller = new AbortController()
    fetchStrategyRegistry(undefined, controller.signal).then(
      (registry) => setStrategies(registry.strategies),
      () => setStrategies(null),
    )
    return () => controller.abort()
  }, [])

  const labels = useMemo<RecordLabels>(() => {
    const sectors = catalog.status === 'loaded' ? catalog.catalog.sectors : []
    return {
      sector: (code) => sectors.find((sector) => sector.code === code)?.name ?? code,
      strategy: (id) => strategies?.find((strategy) => strategy.strategyId === id)?.name ?? id,
    }
  }, [catalog, strategies])

  useEffect(() => {
    if (records.status !== 'loaded' || !highlightToken) return
    const row = highlightRef.current
    if (!row) return
    row.scrollIntoView?.({ block: 'center' })
    row.focus()
  }, [records.status, highlightToken])

  return (
    <div className={styles.page}>
      <AppHeader />
      <main className={styles.main} aria-labelledby="records-title">
        <p className={styles.eyebrow}>
          <FolderClock size={15} aria-hidden="true" />
          MY RECORDS
        </p>
        <h1 id="records-title" className={styles.title}>
          {user?.nickname}님의 내 기록
        </h1>

        <RecordFilters
          query={query}
          onChange={setQuery}
          catalog={catalog.status === 'loaded' ? catalog.catalog : null}
          strategies={strategies}
        />

        {records.status === 'loading' ? (
          <p className={styles.status} role="status">
            기록을 불러오는 중입니다.
          </p>
        ) : null}

        {records.status === 'failed' ? (
          <div className={styles.error} role="alert">
            <p>{records.message}</p>
            <Button type="button" variant="outline" onClick={records.retry}>
              다시 시도
            </Button>
          </div>
        ) : null}

        {records.status === 'loaded' && records.items.length === 0 ? (
          isDefault(query) ? (
            <div className={styles.empty}>
              <p>{EMPTY_MESSAGE}</p>
              <Button asChild>
                <Link to="/diagnosis/sectors">전략 검증하기</Link>
              </Button>
            </div>
          ) : (
            <p className={styles.status}>{NO_MATCH_MESSAGE}</p>
          )
        ) : null}

        {records.status === 'loaded' && records.items.length > 0 ? (
          <>
            <ul className={styles.list} aria-label="내 기록">
              {records.items.map((item) => {
                const highlighted =
                  item.kind === 'ANALYSIS' && highlightToken !== null && item.resultToken === highlightToken
                return (
                  <RecordRow
                    key={item.recordId}
                    ref={highlighted ? highlightRef : undefined}
                    item={item}
                    labels={labels}
                    initiallyOpen={highlighted}
                  />
                )
              })}
            </ul>
            {records.nextCursor ? (
              <div className={styles.more}>
                <Button
                  type="button"
                  variant="outline"
                  disabled={records.loadingMore}
                  onClick={records.loadMore}
                >
                  {records.loadingMore ? '불러오는 중' : '더 보기'}
                </Button>
                {records.moreError ? <p role="alert">{records.moreError}</p> : null}
              </div>
            ) : null}
          </>
        ) : null}
      </main>
    </div>
  )
}

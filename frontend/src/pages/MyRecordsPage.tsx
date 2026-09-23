import { FolderClock } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getRegimeBaselineMonths } from '@/api/regime'
import { fetchStrategyRegistry } from '@/api/strategyRegistry'
import { useAuth } from '@/auth/auth-context'
import { AppHeader } from '@/components/navigation/AppHeader'
import { RecordFilters } from '@/components/records/RecordFilters'
import { type RecordLabels, RecordRow } from '@/components/records/RecordRow'
import { Button } from '@/components/ui/button'
import { useMyRecords } from '@/hooks/useMyRecords'
import { useSectorCatalogState } from '@/hooks/useSectorCatalogState'
import type { MyRecordListQuery } from '@/types/myRecords'
import type { RegimeField } from '@/types/regime'
import type { StrategyView } from '@/types/strategyRegistry'
import styles from './MyRecordsPage.module.css'

export const EMPTY_MESSAGE = '아직 저장된 기록이 없습니다.'
export const NO_MATCH_MESSAGE = '조건에 맞는 기록이 없습니다.'

const DEFAULT_QUERY: MyRecordListQuery = {}

/** 기본 보기인가. 정렬 드롭다운에서 기본값을 다시 고른 것은 조건을 건 것이 아니다. */
function isDefault(query: MyRecordListQuery): boolean {
  return Object.entries(query).every(
    ([key, value]) => value === undefined || (key === 'sort' && value === 'SAVED_AT_DESC'),
  )
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
  const [regimeFields, setRegimeFields] = useState<RegimeField[] | null>(null)
  // 이름 변경·삭제·내보내기의 시작과 실패를 알리는 <b>페이지 수준</b> 자리(FR-021). 줄마다 두면
  // 보조기기가 같은 말을 여러 번 읽는다. 펼친 층의 안내(RecordDetailPanel 등)는 그 자리의 것이라
  // 따로 둔다.
  const [notice, setNotice] = useState('')
  const highlightRef = useRef<HTMLLIElement>(null)
  // 초점 이동은 처음 받았을 때 한 번이다. 필터를 바꿔 다시 받을 때마다 튀면 사용자가 보던 자리를 잃는다.
  const highlightedOnce = useRef(false)

  useEffect(() => {
    const controller = new AbortController()
    fetchStrategyRegistry(undefined, controller.signal).then(
      (registry) => setStrategies(registry.strategies),
      () => setStrategies(null),
    )
    return () => controller.abort()
  }, [])

  // 경제환경 항목의 분야 이름. 010 분야 목록을 페이지에서 한 번 받아 패널마다 부르지 않는다. 못 받으면 코드 그대로다.
  useEffect(() => {
    const controller = new AbortController()
    getRegimeBaselineMonths(controller.signal).then(
      (months) => setRegimeFields(months.byField),
      () => setRegimeFields(null),
    )
    return () => controller.abort()
  }, [])

  const labels = useMemo<RecordLabels>(() => {
    const sectors = catalog.status === 'loaded' ? catalog.catalog.sectors : []
    return {
      sector: (code) => sectors.find((sector) => sector.code === code)?.name ?? code,
      strategy: (id) => strategies?.find((strategy) => strategy.strategyId === id)?.name ?? id,
      regimeField: (id) => regimeFields?.find((field) => field.id === id)?.name ?? id,
    }
  }, [catalog, strategies, regimeFields])

  useEffect(() => {
    if (records.status !== 'loaded' || !highlightToken || highlightedOnce.current) return
    const row = highlightRef.current
    if (!row) return
    highlightedOnce.current = true
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
          onChange={(next) => {
            // 끝난 조작의 문장을 비운다. 두지 않으면 notice 가 로딩 문장을 영영 가리고(둘이 한
            // 자리를 쓴다), 이미 끝난 일의 말이 다른 일을 하는 내내 남는다.
            setNotice('')
            setQuery(next)
          }}
          catalog={catalog.status === 'loaded' ? catalog.catalog : null}
          strategies={strategies}
        />

        {/* 라이브 영역은 내용보다 먼저 DOM 에 있어야 보조기기가 변경을 알아챈다 — 영역과 내용이
            함께 생기면 많은 스크린리더가 읽지 않는다. 페이지 수준 알림과 불러오는 중이라는 말이
            이 한 자리를 쓰므로, 질의가 바뀔 때 앞선 알림을 비운다(FR-021). */}
        <p className={styles.status} role="status">
          {notice || (records.status === 'loading' ? '기록을 불러오는 중입니다.' : '')}
        </p>

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
                    onRenamed={records.replaceItem}
                    onDeleted={records.removeItem}
                    announce={setNotice}
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

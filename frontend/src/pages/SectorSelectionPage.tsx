import { ChartNoAxesCombined } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { fetchSectorCatalog, type SectorCatalogResult } from '@/api/sectors'
import { NotReadyNotice } from '@/components/common/NotReadyNotice'
import { AvailabilityTimeline } from '@/components/diagnosis/AvailabilityTimeline'
import { InstrumentBrowser } from '@/components/diagnosis/InstrumentBrowser'
import { ListingWindowNotice } from '@/components/diagnosis/ListingWindowNotice'
import { OverwriteConfirmDialog } from '@/components/diagnosis/OverwriteConfirmDialog'
import { SelectedInstrumentsRail } from '@/components/diagnosis/SelectedInstrumentsRail'
import { AppHeader } from '@/components/navigation/AppHeader'
import { KRX_SOURCE_ATTRIBUTION } from '@/diagnosis/attribution'
import { buildAvailabilityTimeline } from '@/diagnosis/availabilityTimeline'
import { useLearningExampleEntry } from '@/diagnosis/learningExample'
import { describeListingWindow, pickSelectedSectors } from '@/diagnosis/listingWindow'
import { useSectorSelection } from '@/diagnosis/sector-selection-context'
import { SectorSelectionProvider } from '@/diagnosis/SectorSelectionProvider'
import { sortSectorsForDisplay } from '@/diagnosis/sectorOrder'
import { writeStoredSelection } from '@/diagnosis/selectionStorage'
import { MINIMUM_SECTOR_COUNT } from '@/hooks/useSectorSelection'
import { appendCaseToPath } from '@/marketflow/handoff'
import type { SectorSummary } from '@/types/sector'
import heading from '@/components/common/PageHeading.module.css'
import { SimilarPastMarket } from '@/components/diagnosis/SimilarPastMarket'
import { SectorNews } from '@/components/diagnosis/SectorNews'
import { MarketNow } from '@/components/diagnosis/MarketNow'
import styles from './SectorSelectionPage.module.css'

/**
 * 진단 흐름의 다음 단계다. 003이 소유하는 전략 선택 화면이며 `App.tsx`가 그 경로에 두었다.
 *
 * 값은 이 경로에 실어 보내지 않는다. 003은 같은 탭의 `sessionStorage`를 직접 읽으며, 그 읽는
 * 쪽이 `useSectorSelection`에서 이 화면과 **같은** `readStoredSelection`을 쓴다. 한쪽에서
 * 통과한 선택이 다른 쪽에서 막히지 않게 하려는 것이다.
 */
const NEXT_STEP_PATH = '/strategy'

type InstrumentBoardProps = {
  /** 이미 표시 순서로 정렬된 목록이다. */
  sectors: readonly SectorSummary[]
  isSample: boolean
}

/**
 * 목록·상세·선택 요약 세 영역을 잇는 자리다.
 *
 * 화면이 답해야 하는 질문이 셋이고 영역도 셋이다. 지금 무엇을 보고 있는가(가운데), 그것은 어떤
 * 상품인가(가운데), 지금까지 무엇을 골랐는가(오른쪽). 왼쪽 목록은 그 사이를 옮겨 다니는 길이다.
 *
 * **보는 일과 고르는 일을 가른다.** 목록을 누르면 보는 대상만 바뀌고, 백테스트 대상에 담는 것은
 * 상세 영역의 단추 하나다. 비중은 이 화면이 다루지 않는다 — 다음 단계가 소유한다.
 */
function InstrumentBoard({ sectors, isSample }: InstrumentBoardProps) {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const caseId = params.get('case')
  const initialSector = params.get('sector')
  const selection = useSectorSelection()
  const selectedCount = selection.selectedCodes.length

  /*
    011 진입이 실어 온 섹터는 **초기 선택**일 뿐이다(011 FR-005). 사용자가 이미 고른 것이 있으면
    건드리지 않는다 — 초기 선택이 사용자의 선택을 덮으면 사례 순위와 무관한 값이 입력을 지운다.
    목록에 없는 코드는 상태가 받지 않으므로 여기서 먼저 거른다.
  */
  const appliedInitialSector = useRef(false)
  const { toggleSector } = selection
  useEffect(() => {
    if (appliedInitialSector.current) return
    if (initialSector === null || selectedCount > 0) return
    if (!sectors.some((sector) => sector.code === initialSector)) return
    appliedInitialSector.current = true
    toggleSector(initialSector, true)
  }, [initialSector, sectors, selectedCount, toggleSector])

  /*
    지금 상세 영역이 보여 주는 항목이다. **고른 것과 다른 상태다.** 아직 아무것도 고르지 않은
    사용자도 무언가를 보고 있어야 하므로, 들어올 때부터 한 항목이 열려 있다. 빈 상세 영역은
    사용자에게 "여기서 무엇을 해야 하는가"를 묻게 만든다.
  */
  const [viewingCode, setViewingCode] = useState<string | null>(null)
  const viewing = useMemo(() => {
    const found = sectors.find((sector) => sector.code === viewingCode)
    if (found) return found
    // 011이 실어 온 섹터가 있으면 그것부터 보인다. 없으면 목록의 첫 항목이다.
    return sectors.find((sector) => sector.code === initialSector) ?? sectors[0] ?? null
  }, [initialSector, sectors, viewingCode])

  /*
    카드를 넓혀 보고 있는가. **항목마다 따로 기억하지 않는다.** 넓혀서 여러 상품을 견주는
    사용자는 다음 항목에서도 같은 크기로 보려 하므로, 옮길 때마다 접히면 그 일을 방해한다.
  */
  const [detailExpanded, setDetailExpanded] = useState(false)

  // 배우기(013)에서 예시를 밀어 넣는 경로다.
  const learningEntry = useLearningExampleEntry(sectors)

  /**
   * 인계에 실패한 상태다. 저장소가 막혀 003이 읽을 값을 남길 수 없을 때만 true가 된다.
   *
   * 이 경우 다음 화면으로 보내지 않는다. 보내면 사용자는 방금 고른 것이 사라진 빈 화면을 만난다.
   */
  const [handoffBlocked, setHandoffBlocked] = useState(false)

  // 006이 제안 문장을 정한 경우(목록에서 사라진 섹터)와 보관된 값 자체를 쓸 수 없던 경우를 나눈다.
  const missingCount = selection.dropped.filter((item) => item.reason === 'notInCatalog').length
  const unusableCount = selection.dropped.length - missingCount

  // 배우기 예시에서 끝내 채우지 못한 항목이다. 보관된 값을 복원할 때와 같은 구분을 쓴다.
  const exampleMissingCount = learningEntry.dropped.filter(
    (item) => item.reason === 'notInCatalog',
  ).length
  const exampleUnusableCount = learningEntry.dropped.length - exampleMissingCount

  /*
    사용자가 선택을 고치면 인계 실패 표시를 거둔다. 거두지 않으면 그 문장이 세션 내내 남아, 지금
    상태를 두고 하는 말처럼 읽힌다. 실제로는 그때 한 번 넘기지 못했다는 지난 사실이다.
  */
  useEffect(() => {
    setHandoffBlocked(false)
  }, [selection.selectedCodes])

  /*
    상장일 안내는 고른 대상의 상장일만 견준다. 목록 전체를 넘기면 고르지 않은 섹터의 상장일까지
    최댓값 후보가 되어 사실과 다른 날짜를 말하게 된다.
  */
  const selectedSectors = useMemo(
    () => pickSelectedSectors(sectors, selection.selectedCodes),
    [sectors, selection.selectedCodes],
  )
  // 공통 구간의 판정과 표시 재료를 한 번에 만든다. 같은 안내를 선택 요약에서 되풀이하지 않는다.
  const timeline = useMemo(() => buildAvailabilityTimeline(selectedSectors), [selectedSectors])
  // 아직 상장하지 않은 대상 때문에 구간이 전혀 없으면 폭 0인 그림 대신 기존 경고 문장을 쓴다.
  const listingWindow = useMemo(() => describeListingWindow(selectedSectors), [selectedSectors])

  /**
   * 다음 단계로 넘긴다. **넘기기 전에 값을 손대지 않는다**(FR-013, SC-013).
   *
   * 인계 수단은 탭 세션 보관 항목 하나다. URL이나 navigation state로 따로 싣지 않는다. 새로고침
   * 으로 화면이 다시 뜨는 경우와 같은 경로를 쓰기 위해서다(contracts/selection-handoff.md).
   * 섹터 이름과 대표 ETF 이름은 싣지 않는다 — 003이 `GET /sectors`로 직접 받는다.
   *
   * 비중은 이 화면이 정하지 않지만 인계 페이로드의 항목이라 **지금 상태 그대로** 싣는다. 고르고
   * 빼는 동안 선택 상태가 유지해 온 값이며, 사용자가 고칠 자리는 다음 화면에 있다.
   *
   * 값을 만들 당시의 `mapVersion`을 함께 남긴다. 003·004가 재현 기록에 넣을 수 있게 하기
   * 위해서다(원칙 VIII).
   */
  const proceed = useCallback(() => {
    const persisted = writeStoredSelection({
      mapVersion: selection.mapVersion,
      selectedCodes: [...selection.selectedCodes],
      weightTenths: { ...selection.weightTenths },
      weightsTouched: selection.weightsTouched,
    })

    if (!persisted) {
      setHandoffBlocked(true)
      return
    }

    setHandoffBlocked(false)
    void navigate(appendCaseToPath(NEXT_STEP_PATH, caseId))
  }, [caseId, navigate, selection])

  /*
    KRX 자료를 쓰는 화면에는 출처를 표시한다(원칙 X). 상세를 펼쳐야만 보이는 구조는 목록만 보는
    사용자에게 미표시와 같으므로 목록 화면에 둔다. 탐색 카드 안 오른쪽 아래에 두어 그 카드의 자료라는
    것이 읽히게 하고, 카드를 그리지 못할 때만 카드 자리에 홀로 둔다.
  */
  const attribution = <p className={styles.attribution}>출처: {KRX_SOURCE_ATTRIBUTION}</p>

  return (
    <>
      <div className={styles.board} data-detail-expanded={detailExpanded}>
        {/*
          머리글을 따로 두지 않는다. 화면 제목이 이미 무엇을 하는 자리인지 말하고 있어, 그 아래
          "투자 대상 목록"을 한 번 더 적으면 같은 말이 두 번 읽힌다. 보조 기술에는 영역 이름만 준다.
        */}
        <section className={styles.browser} aria-label="투자 대상 탐색">

          {/*
            목록이 예시 대응표에서 왔다는 표시다(FR-015). 상품 정보의 예시 표시와 가리키는 대상이
            다르므로 한 문장으로 합치지 않는다.
          */}
          {isSample ? (
            <p className={styles.sampleNotice}>
              이 투자 대상 목록은 아직 확정되지 않은 예시 대응표에서 왔습니다.
            </p>
          ) : null}

          {/*
            보관해 둔 값을 그대로 쓰지 못한 경우다. 조용히 넘어가면 사용자가 무엇을 잃었는지 알 수
            없다(원칙 VI). 까닭이 다르면 다른 문장으로 알린다.
          */}
          {selection.restoreProblem !== null ? (
            <p className={styles.restoreNotice} role="status">
              이전에 고른 투자 대상을 불러오지 못해 빈 상태로 시작합니다.
            </p>
          ) : null}
          {missingCount > 0 ? (
            <p className={styles.restoreNotice} role="status">
              이전에 골랐던 투자 대상 {missingCount}개가 지금 목록에 없어 선택에서 빠졌습니다. 아래
              목록에서 대체할 대상을 직접 골라 주세요.
            </p>
          ) : null}
          {unusableCount > 0 ? (
            <p className={styles.restoreNotice} role="status">
              이전에 골랐던 투자 대상 {unusableCount}개는 보관된 값을 그대로 쓸 수 없어 선택에서
              빠졌습니다.
            </p>
          ) : null}
          {exampleMissingCount > 0 ? (
            <p className={styles.restoreNotice} role="status">
              배우기 예시의 투자 대상 {exampleMissingCount}개가 지금 목록에 없어 채우지 않았습니다.
            </p>
          ) : null}
          {exampleUnusableCount > 0 ? (
            <p className={styles.restoreNotice} role="status">
              배우기 예시의 투자 대상 {exampleUnusableCount}개는 실려 온 값을 그대로 쓸 수 없어
              채우지 않았습니다.
            </p>
          ) : null}

          {/*
            고른 대상의 상장 구간과 공통 구간이다. 탐색 카드 **바로 위**에 둔다 — 대상을 넣고 빼는
            자리에서 그 결과가 바로 보여야 한다. 하나만 골라도 그 대상의 시작일부터 오늘까지를
            보여 주며, 아무것도 고르지 않았을 때만 자리를 만들지 않는다.
          */}
          <AvailabilityTimeline className={styles.timeline} timeline={timeline} />
          {listingWindow.status === 'unavailable' ? (
            <ListingWindowNotice
              className={styles.listingNotice}
              listingWindow={listingWindow}
            />
          ) : null}

          {viewing ? (
            <InstrumentBrowser
              className={styles.browserCard}
              sectors={sectors}
              viewing={viewing}
              expanded={detailExpanded}
              isSelected={selection.isSelected}
              onView={setViewingCode}
              onToggleSelected={selection.toggleSector}
              onExpandedChange={setDetailExpanded}
              source={`출처: ${KRX_SOURCE_ATTRIBUTION}`}
            />
          ) : (
            attribution
          )}

          {/*
            지금 국내 시장(KOSPI·VKOSPI, 009 FR-017). 과거 사례와 견주어 볼 출발점이지 검색의 입력이 아니다.
            탐색 카드 바로 아래에 지수를 가로로 펼친다. 선택 요약 끝에 두면 'STEP 02 - 전략 고르기' 뒤로 밀려 잘 읽히지 않았다.
          */}
          <section className={styles.marketStrip} aria-labelledby="market-now-title">
            <h2 id="market-now-title">
              <ChartNoAxesCombined size={14} aria-hidden="true" />
              지금 국내 시장
            </h2>
            <MarketNow />
          </section>
        </section>

        <SelectedInstrumentsRail
          className={styles.rail}
          selected={selectedSectors}
          minimumCount={MINIMUM_SECTOR_COUNT}
          compact={detailExpanded}
          onRemove={(code) => selection.toggleSector(code, false)}
          onProceed={proceed}
          proceedDisabled={selectedCount < MINIMUM_SECTOR_COUNT}
        >

          {/*
            보관이 막혀 003이 읽을 값을 남기지 못한 경우다. 조용히 넘기면 사용자는 선택이 사라진 빈
            화면을 만난다(원칙 VI). 이 화면 안에서는 선택이 그대로 남아 있으므로 화면을 막지는 않는다.
          */}
          {handoffBlocked ? (
            <p className={styles.constraintNotice} role="alert">
              브라우저가 이 탭의 저장을 막고 있어 다음 단계로 값을 넘기지 못했습니다. 비공개 창이라면
              일반 창에서 다시 시도해 주세요.
            </p>
          ) : null}
        </SelectedInstrumentsRail>

        {/*
          배우기 예시가 기존 선택을 덮어써도 되는지 묻는다(FR-012). 묻는 중인 예시가 있을 때만
          열린다. 아무것도 고르지 않은 상태로 들어왔으면 묻지 않고 이미 채워져 있다(SC-011).
        */}
        <OverwriteConfirmDialog
          open={learningEntry.pending !== null}
          exampleSectorCount={learningEntry.pending?.codes.length ?? 0}
          onConfirm={learningEntry.confirmOverwrite}
          onCancel={learningEntry.cancelOverwrite}
        />
      </div>

      {/*
        투자 대상 아래 한 줄에 둘을 나란히 둔다. 왼쪽은 Thorp의 핵심 흐름(002 FR-018) — 고른 섹터를 지금과
        비슷했던 과거 시장 기간으로 백테스트한다. 오른쪽은 뉴스(FR-019)로 기본 '전체', 섹터별로
        좁혀 볼 수 있다. 둘 다 받지 못해도 투자 대상 고르기는 막히지 않는다.
      */}
      <div className={styles.context}>
        <SimilarPastMarket caseId={caseId} />
        <SectorNews />
      </div>
    </>
  )
}

/**
 * 진단의 첫 화면이다. 섹터마다 연결된 대표 ETF를 목록으로 보이고, 고른 항목의 상품 정보를 같은
 * 화면에서 확인하게 한다.
 *
 * 조회 상태를 **모두 정상 화면으로** 처리한다. 불러오는 중 / 목록 / 발행됐지만 섹터 0개 /
 * 대응표 미발행 / 실패. 어느 경우에도 빈 목록이나 처리되지 않은 오류 화면으로 떨어지지 않는다
 * (FR-017, spec Edge Cases).
 *
 * 행동 버튼은 **다시 눌러 달라질 수 있는 상태에만** 준다(research.md 결정 6). 그 판정은 오류
 * 이름을 아는 조회 모듈이 `retryable`로 실어 온다. 저장된 대응표가 망가진 500처럼 다시 눌러도
 * 달라지지 않는 상태에서는 버튼을 두지 않는다 — 두면 사용자가 같은 버튼만 반복해 누르게 된다.
 *
 * 목록이 있는 상태에서만 선택 상태를 만든다.
 */
export function SectorSelectionPage() {
  // null은 아직 결과가 없는 상태, 곧 불러오는 중이다.
  const [result, setResult] = useState<SectorCatalogResult | null>(null)
  const [reloadCount, setReloadCount] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    let isCurrent = true

    setResult(null)
    void fetchSectorCatalog(controller.signal).then((next) => {
      if (isCurrent) setResult(next)
    })

    return () => {
      isCurrent = false
      controller.abort()
    }
  }, [reloadCount])

  const reload = useCallback(() => setReloadCount((count) => count + 1), [])

  // 서버 응답의 배열 순서에 기대지 않고 화면이 표시 순서를 정한다(research.md 결정 2).
  const orderedSectors = useMemo(
    () => (result?.status === 'loaded' ? sortSectorsForDisplay(result.catalog.sectors) : []),
    [result],
  )

  return (
    <div className={styles.page}>
      <AppHeader />
      <main className={styles.main} aria-labelledby="sector-selection-title">
        {/* 시장 흐름 화면과 같은 머리: 제목은 왼쪽, 안내 문장은 오른쪽 인용 모양이다(S15P21E205-451). */}
        <header className={styles.hero}>
          <div>
            <p className={heading.eyebrow} aria-hidden="true">STEP 01</p>
            <h1 id="sector-selection-title" className={styles.title}>
              투자 대상 <em className={heading.emphasis}>고르기</em>
            </h1>
          </div>
          <p className={styles.lead}>
            {/* 뜻이 나뉘는 자리에서 줄을 바꾼다. 폭에 맡기면 낱말 하나만 아랫줄로 떨어진다. */}
            <span>백테스트에 쓸 ETF를 고릅니다.</span>
            <span>목록에서 누르면 투자 대상에 담기고, 다시 누르면 빠집니다.</span>
          </p>
        </header>

        {result === null ? (
          <p className={styles.loading} role="status">
            투자 대상 목록을 불러오는 중입니다.
          </p>
        ) : null}

        {/*
          대응표 미발행과 연결 실패는 같은 표시 컴포넌트를 쓰고 문장만 다르다. 둘 다 다시
          시도하면 달라질 수 있는 상태이므로 행동 하나를 준다. 이 상태에서는 아무것도 고를 수
          없으므로 다음 단계로 가는 수단 자체를 두지 않는다(FR-017).
        */}
        {result?.status === 'mapNotReady' ? (
          <NotReadyNotice
            className={styles.notice}
            message="투자 대상 목록이 아직 준비 중입니다."
            action={{ label: '다시 불러오기', onAction: reload }}
          />
        ) : null}

        {/*
          대응표는 발행되었는데 그 안에 섹터가 없는 경우다. 백엔드가 대응표 미발행과 일부러
          구분해 내보내므로 화면도 따로 알린다. 파이프라인이 항목을 채우면 달라지는 상태라
          다시 불러오기를 준다.
        */}
        {result?.status === 'emptyCatalog' ? (
          <NotReadyNotice
            className={styles.notice}
            message="투자 대상 목록이 아직 비어 있습니다."
            action={{ label: '다시 불러오기', onAction: reload }}
          />
        ) : null}

        {/*
          실패는 두 갈래다. 다시 불러 달라질 수 있으면 행동을 주고, 서버 자료가 망가진 경우처럼
          달라지지 않으면 행동 없이 문장만 둔다.
        */}
        {result?.status === 'failed' ? (
          <NotReadyNotice
            className={styles.notice}
            message={
              result.retryable
                ? '투자 대상 목록을 불러오지 못했습니다.'
                : '서버에 저장된 섹터 자료에 문제가 있어 지금은 목록을 보여드릴 수 없습니다.'
            }
            action={result.retryable ? { label: '다시 불러오기', onAction: reload } : undefined}
          />
        ) : null}

        {/*
          선택 상태는 목록이 실제로 있을 때만 존재한다. 목록을 받지 못한 상태에서 고를 수 있는
          대상이 없으므로 그 경우에는 상태 자체를 만들지 않는다.
        */}
        {result?.status === 'loaded' ? (
          <SectorSelectionProvider
            sectors={result.catalog.sectors}
            mapVersion={result.catalog.mapVersion}
          >
            <InstrumentBoard sectors={orderedSectors} isSample={result.catalog.isSample} />
          </SectorSelectionProvider>
        ) : null}
      </main>
    </div>
  )
}

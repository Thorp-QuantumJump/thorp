import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { fetchSectorCatalog, type SectorCatalogResult } from '@/api/sectors'
import { NotReadyNotice } from '@/components/common/NotReadyNotice'
import { ListingWindowNotice } from '@/components/diagnosis/ListingWindowNotice'
import { OverwriteConfirmDialog } from '@/components/diagnosis/OverwriteConfirmDialog'
import { SectorListItem } from '@/components/diagnosis/SectorListItem'
import { SelectionMeaningNotice } from '@/components/diagnosis/SelectionMeaningNotice'
import { WeightTotal } from '@/components/diagnosis/WeightTotal'
import { AppHeader } from '@/components/navigation/AppHeader'
import { KRX_SOURCE_ATTRIBUTION } from '@/diagnosis/attribution'
import { useLearningExampleEntry } from '@/diagnosis/learningExample'
import { describeListingWindow, pickSelectedSectors } from '@/diagnosis/listingWindow'
import { useSectorSelection } from '@/diagnosis/sector-selection-context'
import { SectorSelectionProvider } from '@/diagnosis/SectorSelectionProvider'
import { sortSectorsForDisplay } from '@/diagnosis/sectorOrder'
import { writeStoredSelection } from '@/diagnosis/selectionStorage'
import { appendCaseToPath } from '@/marketflow/handoff'
import type { SectorSummary } from '@/types/sector'
import styles from './SectorSelectionPage.module.css'

/**
 * 진단 흐름의 다음 단계다. 003이 소유하는 전략 선택 화면이며 `App.tsx`가 그 경로에 두었다.
 *
 * 값은 이 경로에 실어 보내지 않는다. 003은 같은 탭의 `sessionStorage`를 직접 읽으며, 그 읽는
 * 쪽이 `useSectorSelection`에서 이 화면과 **같은** `readStoredSelection`·`summarizeWeights`를
 * 쓴다. 한쪽에서 통과한 선택이 다른 쪽에서 막히지 않게 하려는 것이다.
 */
const NEXT_STEP_PATH = '/strategy'

type SectorCatalogBoardProps = {
  /** 이미 표시 순서로 정렬된 목록이다. */
  sectors: readonly SectorSummary[]
  isSample: boolean
}

/**
 * 목록과 선택 상태를 잇는 자리다. 화면 문구는 이 작업이 확정하지 않는다. 문장이 놓일 자리와
 * 나타나는 조건까지만 만들었고 문구는 UI 결정 항목이 정해질 때 채운다.
 *
 * 안내가 나타나는 조건은 research.md 결정 7의 표를 그대로 따른다.
 */
function SectorCatalogBoard({ sectors, isSample }: SectorCatalogBoardProps) {
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

  // 배우기(013)에서 예시를 밀어 넣는 경로다. 013이 아직 없어도 입구는 여기 있다.
  const learningEntry = useLearningExampleEntry(sectors)

  /**
   * 인계에 실패한 상태다. 저장소가 막혀 003이 읽을 값을 남길 수 없을 때만 true가 된다.
   *
   * 이 경우 다음 화면으로 보내지 않는다. 보내면 사용자는 방금 입력한 값이 사라진 빈 화면을 만난다.
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
    사용자가 값을 고치면 인계 실패 표시를 거둔다. 거두지 않으면 그 문장이 세션 내내 남아, 지금
    상태를 두고 하는 말처럼 읽힌다. 실제로는 그때 한 번 넘기지 못했다는 지난 사실이다.
  */
  useEffect(() => {
    setHandoffBlocked(false)
  }, [selection.selectedCodes, selection.weightTenths])

  /*
    상장일 안내는 선택한 섹터의 상장일만 견준다. 목록 전체를 넘기면 고르지 않은 섹터의 상장일까지
    최댓값 후보가 되어 사실과 다른 날짜를 말하게 된다.
  */
  const listingWindow = useMemo(
    () => describeListingWindow(pickSelectedSectors(sectors, selection.selectedCodes)),
    [sectors, selection.selectedCodes],
  )

  /**
   * 다음 단계로 넘긴다. **넘기기 전에 값을 손대지 않는다**(FR-013, SC-013).
   *
   * 합계를 100%로 맞추는 조정, 표시 정밀도를 넘어선 반올림, 0%인 섹터 제외를 모두 하지 않는다.
   * 여기서 하는 일은 지금 상태를 그대로 보관하고 이동하는 것뿐이다.
   *
   * 인계 수단은 탭 세션 보관 항목 하나다. URL이나 navigation state로 따로 싣지 않는다. 새로고침
   * 으로 화면이 다시 뜨는 경우와 같은 경로를 쓰기 위해서다(contracts/selection-handoff.md).
   * 섹터 이름과 대표 ETF 이름은 싣지 않는다 — 003이 `GET /sectors`로 직접 받는다. 비중도 백분율
   * 소수로 바꾸지 않는다. 004 메시지 계약으로의 변환은 003의 몫이다.
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

  return (
    <section className={styles.catalog} aria-labelledby="sector-list-title">
      <h2 id="sector-list-title" className={styles.listTitle}>
        섹터 목록
      </h2>

      {/*
        목록이 예시 대응표에서 왔다는 표시다(FR-015). 이 문장은 아래 "비중 초깃값이 예시"라는
        표시와 가리키는 대상이 다르므로 한 문장으로 합치지 않는다. 합치면 SC-007 위반이다.
      */}
      {isSample ? (
        <p className={styles.sampleNotice}>
          이 섹터 목록은 아직 확정되지 않은 예시 대응표에서 왔습니다.
        </p>
      ) : null}

      {/*
        보관해 둔 값을 그대로 쓰지 못한 경우다. 조용히 넘어가면 사용자가 무엇을 잃었는지 알 수
        없다(원칙 VI). 무엇을 제안할지는 006이 정했다(FR-008) — 아래 문장이 그것이다.
      */}
      {selection.restoreProblem !== null ? (
        <p className={styles.restoreNotice} role="status">
          이전에 입력한 섹터와 비중을 불러오지 못해 빈 상태로 시작합니다.
        </p>
      ) : null}
      {/*
        까닭이 다르면 다른 문장으로 알린다. 셋을 "목록에 없어 빠졌다" 한 문장으로 합치면 목록에
        멀쩡히 있는 섹터를 두고 없다고 말하게 된다.
      */}
      {/*
        사라진 섹터의 대체 제안은 006이 정했다(FR-008, research R4): 대체 섹터를 자동으로 고르지 않고 사용자가
        아래 목록에서 직접 고르게 한다. 이 화면은 진입 경로를 모르므로("기록에서 왔다" vs "앞선 선택을 복원했다")
        앞문장은 두 경로에 다 참인 말로 두고, 뒷문장이 006의 제안이다. "저장 당시"·"대응표"는 기록에서 온 사람에게만
        참인 말이라 쓰지 않는다.
      */}
      {missingCount > 0 ? (
        <p className={styles.restoreNotice} role="status">
          이전에 골랐던 섹터 {missingCount}개가 지금 목록에 없어 선택에서 빠졌습니다. 아래 목록에서 대체 섹터를
          직접 골라 주세요.
        </p>
      ) : null}
      {unusableCount > 0 ? (
        <p className={styles.restoreNotice} role="status">
          이전에 골랐던 섹터 {unusableCount}개는 보관된 값을 그대로 쓸 수 없어 선택에서
          빠졌습니다.
        </p>
      ) : null}

      {/*
        배우기에서 온 예시 가운데 채우지 못한 것이 있으면 알린다. 조용히 넘어가면 사용자는 예시가
        섹터 셋을 실어 왔는데 왜 둘만 채워졌는지, 합계가 왜 100%가 아닌지 알 수 없다(원칙 VI).
      */}
      {exampleMissingCount > 0 ? (
        <p className={styles.restoreNotice} role="status">
          배우기 예시의 섹터 {exampleMissingCount}개가 지금 목록에 없어 채우지 않았습니다.
        </p>
      ) : null}
      {exampleUnusableCount > 0 ? (
        <p className={styles.restoreNotice} role="status">
          배우기 예시의 섹터 {exampleUnusableCount}개는 실려 온 비중을 그대로 쓸 수 없어 채우지
          않았습니다.
        </p>
      ) : null}

      {/*
        선택 전후 같은 자리를 유지해 카드 목록이 갑자기 밀리지 않게 한다. 선택 뒤에는 초깃값이
        입력 편의를 위한 예시이며 투자 권고가 아니라는 사실을 알린다(FR-005, 원칙 III).
      */}
      <p className={styles.initialValueNotice} aria-live="polite">
        {selectedCount > 0
          ? '채워진 비중은 입력 편의를 위한 예시이며 투자 권고가 아닙니다. 직접 고쳐서 쓰세요.'
          : '섹터를 선택하면 카드 안에서 비중을 바로 조절할 수 있습니다.'}
      </p>

      {/*
        `list-style: none`이 걸리면 Safari와 VoiceOver 조합에서 `<ul>`의 목록 역할이 사라진다.
        이 화면은 목록 의미에 기대고 있으므로(항목이 `<li>`다) 역할을 명시해 보조 기술 사용자만
        "목록, 항목 몇 개" 안내를 못 받는 일이 없게 한다.
      */}
      <ul className={styles.list} role="list">
        {sectors.map((sector) => {
          const selected = selection.isSelected(sector.code)
          return (
            <SectorListItem
              key={sector.code}
              className={styles.item}
              sector={sector}
              selected={selected}
              onSelectedChange={(next) => selection.toggleSector(sector.code, next)}
              weight={
                selected
                  ? {
                      valueTenths: selection.weightTenths[sector.code] ?? 0,
                      onCommit: (weightTenths) => selection.setWeight(sector.code, weightTenths),
                      locked: selection.isLocked(sector.code),
                      onLockedChange: (locked) => selection.toggleLock(sector.code, locked),
                    }
                  : undefined
              }
            />
          )
        })}
      </ul>

      {/* 합계와 남은 비중은 비중을 입력하는 자리 바로 아래에 둔다. 별도 알림 영역으로 빼지 않는다. */}
      {selectedCount > 0 ? (
        <WeightTotal
          className={styles.total}
          totalTenths={selection.totalTenths}
          remainderTenths={selection.remainderTenths}
          isBalanced={selection.isBalanced}
          onDistributeEven={selection.distributeEvenly}
        />
      ) : null}

      {/*
        상장일 차이로 공통 비교 기간이 줄어든다는 사실이다(FR-008). 선택 요약 바로 옆에 두어
        합계를 보는 자리에서 함께 읽히게 한다. 접어 두지 않는다(원칙 II).
      */}
      <ListingWindowNotice className={styles.listingNotice} listingWindow={listingWindow} />

      {/*
        비중 입력 묶음의 꼬리다. 고른 섹터가 무엇을 뜻하는지(FR-009)와 0%인 섹터가 어떻게
        다뤄지는지(FR-016)를 알린다.
      */}
      <SelectionMeaningNotice
        className={styles.meaningNotice}
        selectedCount={selectedCount}
        hasZeroWeight={selection.hasZeroWeight}
      />

      {/* 섹터를 하나만 고르면 비교할 대상이 없다(FR-004). */}
      {selectedCount === 1 ? (
        <p className={styles.constraintNotice} role="status">
          비교하려면 섹터를 두 개 이상 골라야 합니다.
        </p>
      ) : null}

      {/*
        합계가 맞지 않으면 넘어가지 않는다는 사실을 한 문장으로 알린다(FR-007). **그 자리에서
        비중을 자동으로 조정하지 않는다.** 얼마가 모자라거나 넘쳤는지는 위의 합계 표시가 이미
        말하고 있으므로 여기서는 다음 단계가 막힌 까닭만 말한다.
      */}
      {selectedCount >= 2 && !selection.isBalanced ? (
        <p className={styles.constraintNotice} role="status">
          비중의 합계를 100%로 맞추면 다음 단계로 넘어갈 수 있습니다.
        </p>
      ) : null}

      {/*
        보관이 막혀 003이 읽을 값을 남기지 못한 경우다. 조용히 넘기면 사용자는 입력이 사라진 빈
        화면을 만난다(원칙 VI). 이 화면 안에서는 값이 그대로 남아 있으므로 화면을 막지는 않는다.
      */}
      {handoffBlocked ? (
        <p className={styles.constraintNotice} role="alert">
          브라우저가 이 탭의 저장을 막고 있어 다음 단계로 값을 넘기지 못했습니다. 비공개 창이라면
          일반 창에서 다시 시도해 주세요.
        </p>
      ) : null}

      {/*
        다음 단계로 넘어가는 자리다. 활성 조건은 섹터 두 개 이상과 합계 100%다(data-model.md
        파생값). 조건을 채우지 못했을 때 버튼을 숨기지 않는 까닭은, 무엇을 더 해야 다음으로 갈 수
        있는지가 위 두 문장과 함께 읽혀야 하기 때문이다.
      */}
      {selectedCount > 0 ? (
        <button
          type="button"
          className={styles.proceed}
          onClick={proceed}
          disabled={!selection.canProceed}
          data-slot="proceed"
        >
          전략 고르러 가기
        </button>
      ) : null}

      {/*
        KRX 자료를 쓰는 화면에는 출처를 표시한다(원칙 X). 대표 상품 정보 패널을 펼쳐야만 보이는
        구조는 목록만 보는 사용자에게 미표시와 같으므로 목록 화면에 둔다.
      */}
      <p className={styles.attribution}>출처: {KRX_SOURCE_ATTRIBUTION}</p>

      {/*
        배우기 예시가 기존 입력을 덮어써도 되는지 묻는다(FR-012). 묻는 중인 예시가 있을 때만
        열린다. 입력이 하나도 없는 상태로 들어왔으면 묻지 않고 이미 채워져 있다(SC-011).
      */}
      <OverwriteConfirmDialog
        open={learningEntry.pending !== null}
        exampleSectorCount={learningEntry.pending?.codes.length ?? 0}
        onConfirm={learningEntry.confirmOverwrite}
        onCancel={learningEntry.cancelOverwrite}
      />
    </section>
  )
}

/**
 * 전략 진단의 첫 화면이다. 섹터 목록을 받아 이름·대표 ETF 이름·업종/테마 구분과 함께 보인다.
 *
 * 조회 상태를 **모두 정상 화면으로** 처리한다. 불러오는 중 / 목록 / 발행됐지만 섹터 0개 /
 * 대응표 미발행 / 실패. 어느 경우에도 빈 목록이나 처리되지 않은 오류 화면으로 떨어지지 않는다
 * (FR-017, spec Edge Cases).
 *
 * 행동 버튼은 **다시 눌러 달라질 수 있는 상태에만** 준다(research.md 결정 6). 그 판정은 오류
 * 이름을 아는 조회 모듈이 `retryable`로 실어 온다. 저장된 대응표가 망가진 500처럼 다시 눌러도
 * 달라지지 않는 상태에서는 버튼을 두지 않는다 — 두면 사용자가 같은 버튼만 반복해 누르게 된다.
 *
 * 목록이 있는 상태에서만 선택 상태를 만든다. 섹터를 고르면 그 자리에 비중 입력 칸이 나타나고
 * 값은 탭 세션 동안 남는다(FR-005, FR-014). 상장일 안내와 다음 단계 버튼은 M4가, 대표 상품
 * 정보 펼침은 M5가 더한다.
 *
 * 화면 문구는 이 작업이 확정하지 않는다. 문장이 놓일 자리와 나타나는 조건까지만 만들었고
 * 문구는 UI 결정 항목이 정해질 때 채운다.
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
        <h1 id="sector-selection-title" className={styles.title}>
          섹터 고르기
        </h1>
        <p className={styles.lead}>비교하고 싶은 섹터를 고릅니다.</p>

        {result === null ? (
          <p className={styles.loading} role="status">
            섹터 목록을 불러오는 중입니다.
          </p>
        ) : null}

        {/*
          대응표 미발행과 연결 실패는 같은 표시 컴포넌트를 쓰고 문장만 다르다. 둘 다 다시
          시도하면 달라질 수 있는 상태이므로 행동 하나를 준다. 이 상태에서는 섹터를 고를 수
          없으므로 다음 단계로 가는 수단 자체를 두지 않는다(FR-017).
        */}
        {result?.status === 'mapNotReady' ? (
          <NotReadyNotice
            className={styles.notice}
            message="섹터 목록이 아직 준비 중입니다."
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
            message="섹터 목록이 아직 비어 있습니다."
            action={{ label: '다시 불러오기', onAction: reload }}
          />
        ) : null}

        {/*
          실패는 두 갈래다. 다시 불러 달라질 수 있으면 행동을 주고, 서버 자료가 망가진 경우처럼
          달라지지 않으면 행동 없이 문장만 둔다. **문구는 이 작업이 확정하지 않는다.** 갈래와
          나타나는 조건까지만 만들고 문장은 UI 결정 항목이 정해질 때 채운다.
        */}
        {result?.status === 'failed' ? (
          <NotReadyNotice
            className={styles.notice}
            message={
              result.retryable
                ? '섹터 목록을 불러오지 못했습니다.'
                : '서버에 저장된 섹터 자료에 문제가 있어 지금은 목록을 보여드릴 수 없습니다.'
            }
            action={
              result.retryable ? { label: '다시 불러오기', onAction: reload } : undefined
            }
          />
        ) : null}

        {/*
          선택 상태는 목록이 실제로 있을 때만 존재한다. 목록을 받지 못한 상태에서 고를 수 있는
          섹터가 없으므로 그 경우에는 상태 자체를 만들지 않는다.
        */}
        {result?.status === 'loaded' ? (
          <SectorSelectionProvider
            sectors={result.catalog.sectors}
            mapVersion={result.catalog.mapVersion}
          >
            <SectorCatalogBoard sectors={orderedSectors} isSample={result.catalog.isSample} />
          </SectorSelectionProvider>
        ) : null}
      </main>
    </div>
  )
}

import { useCallback, useEffect, useMemo, useState } from 'react'
import { fetchSectorCatalog, type SectorCatalogResult } from '@/api/sectors'
import { NotReadyNotice } from '@/components/common/NotReadyNotice'
import { SectorListItem } from '@/components/diagnosis/SectorListItem'
import { AppHeader } from '@/components/navigation/AppHeader'
import { KRX_SOURCE_ATTRIBUTION } from '@/diagnosis/attribution'
import { sortSectorsForDisplay } from '@/diagnosis/sectorOrder'
import styles from './SectorSelectionPage.module.css'

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
 * 이 묶음은 **목록을 보이는 데까지**가 완결이다. 섹터를 고르는 체크박스와 비중 입력은 M3이,
 * 대표 상품 정보 펼침은 M5가, 다음 단계 버튼은 M4가 더한다.
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

        {result?.status === 'loaded' ? (
          <section className={styles.catalog} aria-labelledby="sector-list-title">
            <h2 id="sector-list-title" className={styles.listTitle}>
              섹터 목록
            </h2>

            {/*
              목록이 예시 대응표에서 왔다는 표시다(FR-015). 이 문장은 FR-005의 "비중 초깃값이
              예시"라는 표시와 가리키는 대상이 다르므로 한 문장으로 합치지 않는다. 합치면
              SC-007 위반이다. 초깃값 쪽 표시는 M3이 비중 입력 묶음의 머리에 따로 둔다.
            */}
            {result.catalog.isSample ? (
              <p className={styles.sampleNotice}>
                이 섹터 목록은 아직 확정되지 않은 예시 대응표에서 왔습니다.
              </p>
            ) : null}

            {/*
              `list-style: none`이 걸리면 Safari와 VoiceOver 조합에서 `<ul>`의 목록 역할이
              사라진다. 이 화면은 목록 의미에 기대고 있으므로(항목이 `<li>`다) 역할을 명시해
              보조 기술 사용자만 "목록, 항목 몇 개" 안내를 못 받는 일이 없게 한다.
            */}
            <ul className={styles.list} role="list">
              {orderedSectors.map((sector) => (
                <SectorListItem key={sector.code} className={styles.item} sector={sector} />
              ))}
            </ul>

            {/*
              KRX 자료를 쓰는 화면에는 출처를 표시한다(원칙 X). 대표 상품 정보 패널을 펼쳐야만
              보이는 구조는 목록만 보는 사용자에게 미표시와 같으므로 목록 화면에 둔다.
            */}
            <p className={styles.attribution}>출처: {KRX_SOURCE_ATTRIBUTION}</p>
          </section>
        ) : null}
      </main>
    </div>
  )
}

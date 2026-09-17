import { useEffect, useState } from 'react'
import { fetchSectorCatalog, type SectorCatalogResult } from '@/api/sectors'
import {
  formatWeightTenths,
  parseWeightInput,
  TOTAL_WEIGHT_TENTHS,
} from '@/diagnosis/weights'
import type { AdminDraftExample, AdminDraftExampleSector } from '@/types/learningAdmin'
import styles from './ExampleEditor.module.css'

/**
 * 글의 예시 배분을 편집한다(013 data-model §5, T063).
 *
 * <p><b>비중 단위는 002 계약을 그대로 쓴다.</b> 화면은 소수 한 자리 %로 받고 `weightTenths` 정수로
 * 보관한다(1000이 100%). 013이 다른 단위를 쓰면 예시를 002로 넘길 때 변환이 생긴다.
 *
 * <p><b>합계가 100%가 아니어도 저장을 막지 않는다.</b> 합계·섹터 개수·대응표 확인은 게시 전 검사가
 * 하며(R7), 초안은 쓰다 만 값을 담을 수 있어야 한다. 화면은 지금 합계를 보이고 게시에서 거절된다는
 * 사실만 알린다.
 *
 * <p><b>대응표를 읽지 못해도 기존 행을 지우지 않는다.</b> 새로 고를 수 없다는 한 문장만 보인다.
 * 화면이 값을 비우면 운영자가 저장을 누르는 순간 예시가 사라진다.
 */

interface ExampleEditorProps {
  example: AdminDraftExample | null
  onChange: (example: AdminDraftExample | null) => void
}

type CatalogState = { status: 'loading' } | SectorCatalogResult

export function ExampleEditor({ example, onChange }: ExampleEditorProps) {
  const [catalog, setCatalog] = useState<CatalogState>({ status: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    void fetchSectorCatalog(controller.signal).then((result) => {
      if (active) setCatalog(result)
    })
    return () => {
      active = false
      controller.abort()
    }
  }, [])

  const openSectors =
    catalog.status === 'loaded' || catalog.status === 'emptyCatalog' ? catalog.catalog.sectors : []
  const canPickSector = openSectors.length > 0
  const sectors = example?.sectors ?? []
  const totalTenths = sectors.reduce((sum, sector) => sum + sector.weightTenths, 0)

  const replaceSectors = (next: AdminDraftExampleSector[]) => onChange({ sectors: next })

  const updateAt = (index: number, patch: Partial<AdminDraftExampleSector>) => {
    replaceSectors(
      sectors.map((sector, position) => (position === index ? { ...sector, ...patch } : sector)),
    )
  }

  return (
    <section className={styles.editor} aria-labelledby="example-editor-title">
      <h3 id="example-editor-title">예시 배분</h3>

      {example === null ? (
        <>
          <p className={styles.empty}>예시가 없습니다. 게시하려면 예시가 있어야 합니다.</p>
          <button type="button" onClick={() => replaceSectors([])}>
            예시 만들기
          </button>
        </>
      ) : (
        <>
          <p className={styles.guide}>
            비중은 소수 한 자리 %로 입력합니다. 합계가 100%가 아니면 게시에서 거절됩니다.
          </p>

          {catalog.status === 'mapNotReady' || catalog.status === 'failed' ? (
            <p className={styles.mapUnavailable}>
              섹터 대응표를 확인할 수 없어 섹터를 새로 고를 수 없습니다. 이미 넣은 행은 그대로
              둡니다.
            </p>
          ) : null}

          <ul className={styles.list}>
            {sectors.map((sector, index) => (
              // 같은 코드를 잠시 두 번 고를 수 있으므로 코드로 키를 만들지 않는다.
              <li key={index} className={styles.row}>
                <label className={styles.field}>
                  <span>섹터</span>
                  <select
                    value={sector.code}
                    disabled={!canPickSector}
                    onChange={(event) => updateAt(index, { code: event.target.value })}
                  >
                    {/* 대응표에 없는 코드도 선택지로 만들어 값을 잃지 않게 한다. */}
                    {openSectors.every((open) => open.code !== sector.code) ? (
                      <option value={sector.code}>{sector.code || '(고르지 않음)'}</option>
                    ) : null}
                    {openSectors.map((open) => (
                      <option key={open.code} value={open.code}>
                        {open.name} ({open.code})
                      </option>
                    ))}
                  </select>
                </label>

                <label className={styles.field}>
                  <span>비중 (%)</span>
                  <input
                    type="text"
                    inputMode="decimal"
                    value={formatWeightTenths(sector.weightTenths)}
                    onChange={(event) => {
                      const parsed = parseWeightInput(event.target.value)
                      // 아직 입력 중이거나 규칙을 벗어난 값은 마지막 값을 그대로 둔다.
                      if (parsed.status === 'parsed') {
                        updateAt(index, { weightTenths: parsed.weightTenths })
                      }
                    }}
                  />
                </label>

                <button
                  type="button"
                  onClick={() => replaceSectors(sectors.filter((_, position) => position !== index))}
                  aria-label={`${index + 1}번째 섹터 삭제`}
                >
                  삭제
                </button>
              </li>
            ))}
          </ul>

          <p className={styles.total}>
            합계 {formatWeightTenths(totalTenths)}%
            {totalTenths === TOTAL_WEIGHT_TENTHS ? '' : ' (100.0%가 아닙니다)'}
          </p>

          <div className={styles.actions}>
            <button
              type="button"
              disabled={!canPickSector}
              onClick={() =>
                replaceSectors([...sectors, { code: openSectors[0]?.code ?? '', weightTenths: 0 }])
              }
            >
              섹터 추가
            </button>
            <button type="button" onClick={() => onChange(null)}>
              예시 비우기
            </button>
          </div>
        </>
      )}
    </section>
  )
}

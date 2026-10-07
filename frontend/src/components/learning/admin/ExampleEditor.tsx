import { useEffect, useId, useState } from 'react'
import { fetchSectorCatalog, type SectorCatalogResult } from '@/api/sectors'
import { formatWeightTenths, parseWeightInput, TOTAL_WEIGHT_TENTHS } from '@/diagnosis/weights'
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
  /**
   * 게시하려면 예시가 있어야 하는가. V1 초안은 있어야 하고, V2 초안은 운영자가 넣을지 고른다(013 FR-003,
   * v2 규칙 `exampleRequired: false`). 기본은 1판 화면처럼 있어야 한다
   */
  required?: boolean
}

type CatalogState = { status: 'loading' } | SectorCatalogResult

interface WeightFieldProps {
  /** 0.1%p 단위 정수다. 표시할 때만 10으로 나눈다 */
  valueTenths: number
  /** 받아들인 값만 올라온다. 거부된 입력은 여기까지 오지 않는다 */
  onCommit: (weightTenths: number) => void
}

/**
 * 예시 한 줄의 비중 칸이다. 002 `components/diagnosis/WeightInput`의 숫자 칸과 같은 방식이다.
 *
 * <p><b>치고 있는 문자열을 확정된 값과 따로 둔다.</b> 확정된 값을 글자마다 `formatWeightTenths`로 다시
 * 그리면 "5"가 "5.0"이 되고 커서가 끝으로 가서, 다음 글자 "0"이 "5.00"으로 읽혀 50%를 넣을 수
 * 없다. 사용자가 친 글자를 화면이 몰래 바꾸는 것이다(constitution 원칙 VI).
 *
 * <p><b>판정은 `diagnosis/weights.ts` 한 곳이다.</b> 받지 않은 입력은 말없이 버리지 않고 그 칸 옆에 한
 * 문장으로 알린다. 칸을 벗어나면 확정된 값의 표시로 돌아간다.
 */
function WeightField({ valueTenths, onCommit }: WeightFieldProps) {
  const inputId = useId()
  const errorId = useId()
  /** `null`이면 치는 중이 아니며 화면은 확정된 값을 보인다 */
  const [draft, setDraft] = useState<string | null>(null)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  const settle = () => {
    setDraft(null)
    setErrorMessage(null)
  }

  return (
    <div className={styles.field}>
      <label htmlFor={inputId}>비중 (%)</label>
      <input
        id={inputId}
        type="text"
        inputMode="decimal"
        value={draft ?? formatWeightTenths(valueTenths)}
        onChange={(event) => {
          const raw = event.target.value
          setDraft(raw)
          const parsed = parseWeightInput(raw)
          if (parsed.status === 'parsed') {
            setErrorMessage(null)
            onCommit(parsed.weightTenths)
            return
          }
          // 빈 칸은 0으로 보지 않는다. 아직 입력 중이므로 이전 값을 그대로 둔다.
          setErrorMessage(parsed.status === 'rejected' ? parsed.message : null)
        }}
        onBlur={settle}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault()
            settle()
          }
        }}
        aria-invalid={errorMessage === null ? undefined : true}
        aria-describedby={errorMessage === null ? undefined : errorId}
      />
      {errorMessage === null ? null : (
        <span id={errorId} role="alert" className={styles.weightError}>
          {errorMessage}
        </span>
      )}
    </div>
  )
}

export function ExampleEditor({ example, onChange, required = true }: ExampleEditorProps) {
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
          <p className={styles.empty}>
            {required
              ? '예시가 없습니다. 게시하려면 예시가 있어야 합니다.'
              : "예시가 없습니다. 예시는 넣지 않아도 되며, 없으면 글에 '이 예시로 전략 진단하기'가 없습니다."}
          </p>
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

          {/*
            대응표는 열렸는데 섹터가 없는 것은 대응표를 확인하지 못한 것과 다른 사실이다. 같은 문장으로
            묶지 않되, 고를 수 없는 이유는 반드시 알린다. 이유 없이 막힌 버튼을 두지 않는다.
          */}
          {catalog.status === 'emptyCatalog' ? (
            <p className={styles.mapUnavailable}>
              열린 대응표에 섹터가 없어 새로 고를 수 없습니다. 이미 넣은 행은 그대로 둡니다.
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

                <WeightField
                  valueTenths={sector.weightTenths}
                  onCommit={(weightTenths) => updateAt(index, { weightTenths })}
                />

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

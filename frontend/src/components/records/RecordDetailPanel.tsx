import { useEffect, useRef, useState } from 'react'
import { ApiClientError } from '@/api/client'
import { fetchMyRecord } from '@/api/myRecords'
import { closePanelOnEscape } from '@/components/result/panelEscape'
import { Button } from '@/components/ui/button'
import type { MyRecordDetail, MyRecordReferenceVersions } from '@/types/myRecords'
import type { VersionMark } from '@/types/result'
import type { RecordLabels } from './RecordRow'
import { formatSavedAt } from './recordLabels'
import styles from './records.module.css'

type State =
  | { status: 'loading' }
  | { status: 'loaded'; detail: MyRecordDetail }
  | { status: 'failed'; message: string }

export const NOT_RECORDED = '기록되지 않음'
const PROVISIONAL = ' (잠정)'

function mark(value: VersionMark | undefined): string {
  return value ? `${value.version}${value.provisional ? PROVISIONAL : ''}` : NOT_RECORDED
}

/** 계약 "referenceVersions의 출처" 순서. 없는 키는 '기록되지 않음'이다 — 값을 지어내지 않는다(원칙 VI). */
function versionRows(versions: MyRecordReferenceVersions): [string, string][] {
  const rows: [string, string][] = [
    ['자료', versions.datasetVersion ?? NOT_RECORDED],
    ['대응표', versions.sectorEtfMapVersion ?? NOT_RECORDED],
    ['등록표', versions.registryVersion ?? NOT_RECORDED],
    ['비용', mark(versions.costProfileVersion)],
    ['규칙', mark(versions.rulesVersion)],
    ['엔진', versions.engineVersion ?? NOT_RECORDED],
  ]
  if (versions.modelVersion) rows.push(['모델', versions.modelVersion])
  return rows
}

/**
 * 펼쳐 보는 층(FR-005, 원칙 II 정보 세 층). 행에서 열고 Esc로 닫으며 초점은 여는 버튼으로 돌아간다(FR-021).
 *
 * <p>참조 버전은 서버가 원본에서 이름만 바꿔 준 값이다. `worker`·`dependencies`는 오지 않으므로 보일 자리도 없다.
 * `mappingDrift`·`registryDrift`는 서버가 판정한 문장을 그대로 보인다 — 화면이 버전 문자열을 비교하지 않는다.
 * 판정할 근거가 없어 키가 없으면 아무 문장도 보이지 않는다(`changed: false`와 같지 않다).
 */
export function RecordDetailPanel({
  id,
  recordId,
  labels,
  onClose,
  onLoaded,
}: {
  id: string
  recordId: string
  labels: RecordLabels
  onClose: () => void
  /** 상세를 받으면 행에 알린다 — 행의 '설정 바꿔 비교'가 다시 받지 않게 */
  onLoaded?: (detail: MyRecordDetail) => void
}) {
  const [state, setState] = useState<State>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const titleRef = useRef<HTMLHeadingElement>(null)
  const titleId = `${id}-title`

  useEffect(() => {
    titleRef.current?.focus()
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    setState({ status: 'loading' })
    fetchMyRecord(recordId, controller.signal).then(
      (detail) => {
        if (!active) return
        setState({ status: 'loaded', detail })
        onLoaded?.(detail)
      },
      (error: unknown) => {
        if (!active) return
        setState({
          status: 'failed',
          message:
            error instanceof ApiClientError
              ? error.message
              : '상세를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
        })
      },
    )
    return () => {
      active = false
      controller.abort()
    }
  }, [recordId, attempt, onLoaded])

  return (
    <section
      id={id}
      className={styles.panel}
      aria-labelledby={titleId}
      onKeyDown={closePanelOnEscape(onClose)}
    >
      <div className={styles.panelHead}>
        <h3 id={titleId} ref={titleRef} tabIndex={-1} className={styles.panelTitle}>
          저장 당시 조건
        </h3>
        <Button type="button" variant="outline" size="sm" onClick={onClose}>
          닫기
        </Button>
      </div>

      {state.status === 'loading' ? (
        <p role="status" className={styles.hint}>
          상세를 불러오는 중입니다.
        </p>
      ) : null}

      {state.status === 'failed' ? (
        <div role="alert" className={styles.alert}>
          <p>{state.message}</p>
          <Button type="button" variant="outline" size="sm" onClick={() => setAttempt((n) => n + 1)}>
            다시 시도
          </Button>
        </div>
      ) : null}

      {state.status === 'loaded' ? <DetailBody detail={state.detail} labels={labels} /> : null}
    </section>
  )
}

function DetailBody({ detail, labels }: { detail: MyRecordDetail; labels: RecordLabels }) {
  const drift = detail.mappingDrift
  const registry = detail.registryDrift
  const payload = detail.reopenPayload

  return (
    <>
      {drift?.changed && drift.message ? (
        <p role="status" className={styles.notice}>
          {drift.message}
          {drift.sectorCodes.length > 0 ? ` (${drift.sectorCodes.map(labels.sector).join(', ')})` : null}
        </p>
      ) : null}
      {registry?.outOfRange ? (
        <p role="status" className={styles.notice}>
          저장된 설정값 중 현재 등록표 허용 범위 밖인 항목이 있습니다:{' '}
          {registry.fields.map((field) => field.axis).join(', ')}
        </p>
      ) : null}

      <dl className={styles.facts}>
        <dt>참조 버전</dt>
        <dd>
          <dl className={styles.versions}>
            {versionRows(detail.referenceVersions).map(([label, value]) => (
              <div key={label}>
                <dt>{label}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
        </dd>
        {payload ? (
          <>
            <dt>실제 평가 기간</dt>
            <dd>
              {payload.effectiveStart} ~ {payload.effectiveEnd}
            </dd>
            <dt>준비 기간</dt>
            <dd>{payload.warmupTradingDays}거래일</dd>
          </>
        ) : null}
        <dt>마지막으로 본 시각</dt>
        <dd>
          {detail.lastViewedAt ? (
            <time dateTime={detail.lastViewedAt}>{formatSavedAt(detail.lastViewedAt)}</time>
          ) : (
            NOT_RECORDED
          )}
        </dd>
      </dl>
    </>
  )
}

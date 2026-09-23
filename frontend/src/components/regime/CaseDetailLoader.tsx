import { useEffect, useState, type ReactNode } from 'react'
import { getRegimeCaseDetail } from '@/api/regime'
import type { RegimeCaseDetail as Detail, RegimeCaseSummary, RegimeMode } from '@/types/regime'
import { CaseDetail } from './CaseDetail'
import * as copy from './fixedCopy'
import { LoadError } from './LoadError'

interface Props { searchId: string; summary: RegimeCaseSummary; mode: RegimeMode; fieldNames: Record<string, string>; postPeriodSlot?: ReactNode }

/** 상세 요청은 검색 식별자에 고정해 다른 기준월의 같은 사례를 섞지 않는다. */
export function CaseDetailLoader({ searchId, summary, mode, fieldNames, postPeriodSlot }: Props) {
  const [detail, setDetail] = useState<Detail | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [retry, setRetry] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    getRegimeCaseDetail(summary.endMonth, { searchId }, controller.signal)
      .then((next) => {
        if (controller.signal.aborted) return
        if (next.searchId !== searchId || next.endMonth !== summary.endMonth) {
          setError(new Error(copy.detailMismatchMessage))
          return
        }
        setDetail(next)
        setError(null)
      })
      .catch((caught: unknown) => { if (!controller.signal.aborted) setError(caught) })
    return () => controller.abort()
  }, [searchId, summary.endMonth, retry])

  if (error) return <LoadError error={error} onRetry={() => setRetry((value) => value + 1)} />
  if (!detail) return <p role="status">{copy.loadingCaseDetail}</p>
  return <CaseDetail detail={detail} summary={summary} mode={mode} fieldNames={fieldNames} postPeriodSlot={postPeriodSlot} />
}

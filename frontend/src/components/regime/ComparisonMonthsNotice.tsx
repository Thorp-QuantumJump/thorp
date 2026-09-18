import type { RegimeComparisonMonths } from '@/types/regime'
import { Disclosure } from './Disclosure'

interface Props { comparisonMonths: RegimeComparisonMonths[]; context: string }

export function ComparisonMonthsNotice({ comparisonMonths, context }: Props) {
  const omitted = comparisonMonths.filter((item) => item.omitted.length > 0)
  if (omitted.length === 0) return null
  return (
    <Disclosure label="빠진 비교 시점" context={context} tone="warning">
      {omitted.map((item) => (
        <p key={item.indicatorId}>{item.note}</p>
      ))}
    </Disclosure>
  )
}

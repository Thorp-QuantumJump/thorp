import type { RegimeSearchResult } from '@/types/regime'
import { Disclosure } from './Disclosure'

interface Props { result: RegimeSearchResult; context: string }

export function SearchStatusBadge({ result, context }: Props) {
  const tone = result.status === 'NOT_COMPUTABLE' ? 'error' : 'warning'
  return (
    <Disclosure label="검색 상태" context={context} tone={tone}>
      <p>{result.statusNote}</p>
    </Disclosure>
  )
}
